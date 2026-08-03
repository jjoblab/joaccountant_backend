#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_modern_steps.py — Étapes métier du seed moderne.

Importé par `seed_modern.py`. Contient les fonctions asynchrones qui créent
les entités via l'ApiClient httpx et peuplent le SeedResult.
"""

from __future__ import annotations

import asyncio
import io
import json
import random
import time
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta
from typing import Any, Optional

import httpx
from rich.progress import (
    BarColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
)

from seed_modern import (
    ApiClient,
    FY_END_DEFAULT,
    FY_START_DEFAULT,
    PROFILES,
    SeedResult,
    banner,
    err,
    info,
    ok,
    step_panel,
    warn,
)
from seed_modern import BusinessProfile  # type: ignore


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def _random_date_in_fy(rng: random.Random, start: date = FY_START_DEFAULT, end: date = FY_END_DEFAULT) -> date:
    """Date aléatoire dans l'exercice fiscal."""
    delta = (end - start).days
    return start + timedelta(days=rng.randint(0, delta))


def _pick_account(accounts: dict, *preferred_codes: str) -> Optional[str]:
    """Retourne l'ID du compte dont le code commence par un des préfixes."""
    for code_prefix in preferred_codes:
        for code, acc in accounts.items():
            if code.startswith(code_prefix) and acc.get("id"):
                return acc["id"]
    return None


# ──────────────────────────────────────────────────────────────────────────────
# 1. Attente du backend (avec timeout)
# ──────────────────────────────────────────────────────────────────────────────

def wait_for_backend_sync(base_url: str, timeout_s: int = 90) -> bool:
    """Attend que le backend soit prêt (health check + OpenAPI)."""
    mgmt_url = base_url.replace(":8080", ":8081").rstrip("/")
    start = time.time()
    while time.time() - start < timeout_s:
        # Test 1 : actuator/health sur port management (8081)
        try:
            with urllib.request.urlopen(f"{mgmt_url}/actuator/health", timeout=3) as r:
                if r.status == 200 and b'"status":"UP"' in r.read():
                    return True
        except Exception:
            pass
        # Test 2 : OpenAPI sur port app (8080)
        try:
            with urllib.request.urlopen(f"{base_url.rstrip('/')}/v3/api-docs", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(2)
    return False


# ──────────────────────────────────────────────────────────────────────────────
# 2. Enregistrement + login
# ──────────────────────────────────────────────────────────────────────────────

async def step_register_user(api: ApiClient, profile: BusinessProfile, suffix: str) -> tuple[str, str]:
    """Crée un utilisateur owner et renvoie (email, password)."""
    email = f"{profile.company_name.lower().replace(' ', '.').replace('&', 'et')}.owner.{suffix}@example.ht"
    password = f"{profile.company_name[:8].replace(' ', '')}#2026"
    body = {
        "email": email,
        "password": password,
        "fullName": f"{profile.company_name} Owner",
        "locale": "fr",
    }
    resp = await api.post("/api/v1/auth/register", body)
    if resp.status_code == 409:
        # Utilisateur existe déjà — on tente le login direct
        ok(f"Utilisateur existe déjà ({email}) — login direct")
        return email, password
    if resp.status_code >= 400:
        # Tenter login direct (cas register auto-login désactivé)
        pass
    data = api.ensure_ok(resp, "register user") if resp.status_code < 400 else {}
    token = data.get("accessToken")
    if token:
        api.token = token
        ok(f"Utilisateur créé + auto-login")
    return email, password


async def step_login(api: ApiClient, email: str, password: str) -> None:
    """Authentifie et stocke le JWT."""
    if api.token:
        return  # déjà authentifié via register
    resp = await api.post("/api/v1/auth/login", {"email": email, "password": password})
    data = api.ensure_ok(resp, "login")
    token = data.get("accessToken") or data.get("access_token")
    if not token:
        raise RuntimeError(f"No access token in login response: {data}")
    api.token = token
    ok(f"JWT obtenu ({len(token)} chars)")


# ──────────────────────────────────────────────────────────────────────────────
# 3. Création de l'entreprise + wizard 4 étapes
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_company(api: ApiClient, profile: BusinessProfile, result: SeedResult) -> None:
    """Crée l'entreprise + wizard complet (4 étapes) + PATCH /legal."""
    # Étape 1 : POST /companies (identité)
    body = {
        "name": profile.company_name,
        "country": profile.country,
        "functionalCurrency": profile.currency,
        "organizationNature": profile.organization_nature,
        "legalForm": profile.legal_form,
    }
    resp = await api.post("/api/v1/companies", body)
    data = api.ensure_ok(resp, "create company")
    company_obj = data.get("company", data)
    company_id = company_obj.get("id")
    if not company_id:
        raise RuntimeError(f"No company id in response: {data}")
    api.company_id = company_id
    result.company_id = company_id
    result.company_name = profile.company_name
    result.wizard_step = 1
    # Stocker le nouveau JWT (claim companies à jour)
    new_token = data.get("accessToken")
    if new_token:
        api.token = new_token
        ok(f"Entreprise créée — companyId={company_id[:8]}... (JWT rafraîchi)")
    else:
        ok(f"Entreprise créée — companyId={company_id[:8]}...")

    # PATCH /legal (nif + address)
    if profile.nif or profile.address:
        legal_body = {"nif": profile.nif, "address": profile.address}
        try:
            resp = await api.patch(f"/api/v1/companies/{company_id}/legal", legal_body)
            if resp.status_code < 300:
                ok(f"Champs légaux (NIF, adresse) persistés")
            else:
                warn(f"Mise à jour champs légaux ignorée : HTTP {resp.status_code}")
        except Exception as e:
            warn(f"PATCH /legal échoué (non bloquant) : {e}")

    # Étape 2 : PATCH /wizard/2 (activité + type métier)
    step2_body = {
        "primaryActivityLabel": profile.primary_activity,
        "businessTypeCode": profile.business_type_code,
        "sector": profile.sector,
        "extraAttributes": {},
        "customModules": [],
    }
    resp = await api.patch(f"/api/v1/companies/{company_id}/wizard/2", step2_body)
    api.ensure_ok(resp, "wizard step 2")
    result.wizard_step = 2
    ok(f"Wizard étape 2 — {profile.business_type_code}")

    # Étape 3 : PATCH /wizard/3 (comptabilité + fiscalité)
    start_year = FY_START_DEFAULT.year if profile.fy_start_month == 10 else date.today().year
    step3_body = {
        "accountingFrameworkId": profile.framework_id,
        "fiscalYearStartMonth": profile.fy_start_month,
        "fiscalYearStartYear": start_year,
        "fiscalYearLabel": f"Exercice {start_year}-{start_year + 1}",
        "vatMode": "DEBIT",
        "numberingPrefixes": {
            "SALES_INVOICE": "FAC",
            "JOURNAL_ENTRY": "EC",
            "PURCHASE_INVOICE": "FA",
        },
    }
    resp = await api.patch(f"/api/v1/companies/{company_id}/wizard/3", step3_body)
    api.ensure_ok(resp, "wizard step 3")
    result.wizard_step = 3
    ok(f"Wizard étape 3 — framework {profile.framework_id[:8]}... + FY {profile.fy_start_month}/{start_year}")

    # Étape 4 : POST /wizard/complete (activation atomique)
    complete_body = {"mfaCode": None, "expenseCategories": [], "contributionRules": []}
    resp = await api.post(f"/api/v1/companies/{company_id}/wizard/complete", complete_body)
    if resp.status_code == 409:
        warn("Wizard déjà complété (409) — on continue")
    else:
        api.ensure_ok(resp, "wizard complete")
    result.wizard_completed = True
    data = resp.json() if resp.status_code < 300 else {}
    result.modules_activated = data.get("activatedModules", [])
    result.chart_of_accounts_count = data.get("chartOfAccountsCreated", 0)
    result.fiscal_year_id = data.get("fiscalYearId", "")
    result.journals_created = data.get("journalCodesCreated", [])
    result.sequences_created = data.get("sequencesCreated", 0)
    result.tax_rules_created = data.get("taxRulesCreated", 0)
    ok(f"Wizard complété — {len(result.modules_activated)} modules, "
       f"{result.chart_of_accounts_count} comptes, "
       f"{result.sequences_created} séquences")

    # Création explicite des séquences documentaires (idempotente — 409/422 OK)
    # v9.4 fix — Le wizard crée déjà ces séquences, donc on récupère 422 "already exists"
    # qu'on traite comme un 409 idempotent (succès).
    seq_payloads = [
        ("SALES_INVOICE", "VT", "FAC"),
        ("SALES_INVOICE", "DEFAULT", "FAC"),
        ("JOURNAL_ENTRY", "OD", "EC"),
        ("JOURNAL_ENTRY", "VT", "EC"),
        ("JOURNAL_ENTRY", "AC", "EC"),
        ("JOURNAL_ENTRY", "BQ", "EC"),
        ("JOURNAL_ENTRY", "PA", "EC"),
        ("JOURNAL_ENTRY", "DEFAULT", "EC"),
        ("CREDIT_NOTE", "DEFAULT", "AV"),
        ("PURCHASE_INVOICE", "AC", "FA"),
        ("PURCHASE_INVOICE", "DEFAULT", "FA"),
        ("PAYSLIP", "PA", "BUL"),
    ]
    created_seq = 0
    for doc_type, scope, prefix in seq_payloads:
        body = {
            "documentType": doc_type,
            "scopeKey": scope,
            "prefix": prefix,
            "paddingLength": 5,
            "annualReset": True,
            "year": start_year,
        }
        resp = await api.post(f"/api/v1/companies/{company_id}/document-numbering/sequences", body)
        if resp.status_code < 300:
            created_seq += 1
        elif resp.status_code in (409, 422):
            pass  # idempotent — déjà créée par le wizard
        else:
            warn(f"Séquence {doc_type}/{scope} non créée : HTTP {resp.status_code}")
    ok(f"{created_seq} séquences documentaires créées (restent en 409/422 idempotent)")


# ──────────────────────────────────────────────────────────────────────────────
# 4. Récupération du plan comptable
# ──────────────────────────────────────────────────────────────────────────────

async def step_get_accounts(api: ApiClient, company_id: str) -> dict:
    """Récupère le plan comptable de l'entreprise."""
    resp = await api.get(f"/api/v1/companies/{company_id}/chart-of-accounts")
    if resp.status_code >= 400:
        warn(f"Chart of accounts non récupéré (HTTP {resp.status_code}) — fallback codes standard")
        return {}
    data = resp.json()
    # La réponse peut être une liste directe ou {"accounts": [...]}
    accounts_list = data if isinstance(data, list) else data.get("accounts", data.get("content", []))
    accounts: dict[str, dict] = {}
    for acc in accounts_list:
        code = acc.get("code") or acc.get("number")
        if code:
            accounts[code] = acc
    ok(f"{len(accounts)} comptes chargés")
    return accounts


# ──────────────────────────────────────────────────────────────────────────────
# 5. Création des tiers (clients + fournisseurs) — en parallèle
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_third_parties(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    result: SeedResult,
) -> dict[str, list[dict]]:
    """Crée clients et fournisseurs séquentiellement (évite la race condition
    sur le compte collectif 411 auto-créé par le backend).

    NOTE : le backend ThirdPartiesService auto-crée un compte collectif (411 Clients / 401 Fournisseurs)
    à la première création de tiers. En parallèle, plusieurs requêtes tentent de créer ce compte
    simultanément → violation de la contrainte unique `uc_account_company_code` (23505).
    On séquentialise donc ces créations.
    """
    all_parties = profile.clients_retail + profile.clients_wholesale + profile.suppliers

    async def _create_one(tp: dict, idx: int) -> Optional[dict]:
        body = {
            "name": tp["name"],
            "email": tp["email"],
            "address": tp.get("address"),
            "type": tp["type"],
            "country": profile.country,
        }
        idem = api.idem_key("tp", idx)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/third-parties",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            return None
        data = resp.json()
        tp_id = data.get("id")
        if not tp_id:
            return None
        return {"id": tp_id, **tp}

    # Séquentiel (pas de parallèle) — le backend auto-crée un compte collectif
    # à chaque premier tiers, et les créations parallèles causent une race condition.
    created = []
    for idx, tp in enumerate(all_parties):
        result_one = await _create_one(tp, idx)
        if result_one:
            created.append(result_one)

    clients = [c for c in created if c["type"] == "CLIENT"]
    suppliers = [c for c in created if c["type"] == "SUPPLIER"]
    donors = [c for c in created if c["type"] == "DONOR"]

    result.clients_count = len(clients) + len(donors)  # donors traités comme clients
    result.suppliers_count = len(suppliers)
    ok(f"{len(clients)} clients + {len(donors)} donors + {len(suppliers)} fournisseurs créés")
    return {"clients": clients + donors, "suppliers": suppliers}


# ──────────────────────────────────────────────────────────────────────────────
# 6. Articles de stock (inventory)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_inventory_items(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    result: SeedResult,
    accounts: dict,
) -> list[dict]:
    """Crée les articles de stock (si le profil en a).

    Séquentiel : le backend auto-crée des comptes de stock (331) au premier article,
    ce qui causerait une race condition en parallèle.

    v9.4 fix — Le backend CreateItemRequest exige :
    - unitOfMeasure (NotBlank) — pas "unit"
    - inventoryAccountId (NotNull UUID) — compte de stock (331...)
    - cogsAccountId (NotNull UUID) — compte de COGS (601...)
    On résout ces comptes depuis le plan comptable.
    """
    if not profile.articles:
        info("Pas d'articles pour ce profil — skip inventory")
        return []
    # Résoudre les comptes requis
    inventory_account_id = _pick_account(accounts, "331", "33", "30", "31", "32")
    cogs_account_id = _pick_account(accounts, "601", "603", "60")
    if not inventory_account_id or not cogs_account_id:
        warn(f"Comptes stock/COGS non trouvés (inventory={inventory_account_id}, cogs={cogs_account_id}) — skip inventory")
        return []

    articles = profile.articles

    async def _create_one(art: dict, idx: int) -> Optional[dict]:
        body = {
            "sku": art["sku"],
            "label": art["label"],
            "unitOfMeasure": art.get("unit", "U"),
            "costingMethod": "WEIGHTED_AVERAGE",
            "reorderThreshold": str(art.get("reorder", 5)),
            "inventoryAccountId": inventory_account_id,
            "cogsAccountId": cogs_account_id,
        }
        idem = api.idem_key("inv", idx)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/inventory/items",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            if idx < 3:
                warn(f"Article {art['sku']} non créé : HTTP {resp.status_code} - {resp.text[:200]}")
            return None
        data = resp.json()
        item_id = data.get("id")
        if not item_id:
            return None
        return {"id": item_id, **art}

    # Séquentiel pour éviter la race sur les comptes de stock auto-créés
    created = []
    for idx, art in enumerate(articles):
        result_one = await _create_one(art, idx)
        if result_one:
            created.append(result_one)
    result.inventory_items_count = len(created)
    ok(f"{len(created)} articles créés (compte stock={inventory_account_id[:8]}..., COGS={cogs_account_id[:8]}...)")
    return created


# ──────────────────────────────────────────────────────────────────────────────
# 7. Immobilisations
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_fixed_assets(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    result: SeedResult,
    accounts: dict,
) -> list[dict]:
    """Crée les immobilisations.

    Séquentiel : le backend auto-crée des comptes d'immobilisation (244, etc.) au premier asset,
    ce qui causerait une race condition en parallèle.

    v9.4 fix — Le backend CreateAssetRequest exige :
    - acquisitionDate (NotNull LocalDate)
    - assetAccountId (NotNull UUID) — compte d'immobilisation (244, 245...)
    - depreciationExpenseAccountId (NotNull UUID) — compte de dotation (681...)
    - accumulatedDepreciationAccountId (NotNull UUID) — compte d'amortissement cumulé (284...)
    """
    if not profile.fixed_assets:
        info("Pas d'immobilisations pour ce profil — skip fixed-assets")
        return []
    # Résoudre les comptes requis
    asset_account_id = _pick_account(accounts, "244", "245", "24", "22", "23")
    depreciation_expense_account_id = _pick_account(accounts, "681", "68", "6")
    accumulated_depreciation_account_id = _pick_account(accounts, "284", "285", "28", "29")
    if not all([asset_account_id, depreciation_expense_account_id, accumulated_depreciation_account_id]):
        warn(f"Comptes immobilisation non trouvés — skip fixed-assets "
             f"(asset={asset_account_id}, deprec={depreciation_expense_account_id}, accum={accumulated_depreciation_account_id})")
        return []

    async def _create_one(fa: dict, idx: int) -> Optional[dict]:
        body = {
            "label": fa["label"],
            "acquisitionDate": FY_START_DEFAULT.isoformat(),
            "acquisitionCost": str(fa["cost"]),
            "usefulLifeMonths": fa["useful_life_months"],
            "residualValue": str(fa.get("residual", 0)),
            "depreciationMethod": "STRAIGHT_LINE",
            "assetAccountId": asset_account_id,
            "depreciationExpenseAccountId": depreciation_expense_account_id,
            "accumulatedDepreciationAccountId": accumulated_depreciation_account_id,
        }
        idem = api.idem_key("fa", idx)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/fixed-assets",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            if idx < 3:
                warn(f"Immobilisation '{fa['label'][:30]}' non créée : HTTP {resp.status_code} - {resp.text[:200]}")
            return None
        data = resp.json()
        fa_id = data.get("id")
        if not fa_id:
            return None
        return {"id": fa_id, **fa}

    # Séquentiel pour éviter la race sur les comptes d'immobilisation auto-créés
    created = []
    for idx, fa in enumerate(profile.fixed_assets):
        result_one = await _create_one(fa, idx)
        if result_one:
            created.append(result_one)
    result.fixed_assets_count = len(created)
    ok(f"{len(created)} immobilisations créées (asset={asset_account_id[:8]}..., deprec={depreciation_expense_account_id[:8]}...)")
    return created


# ──────────────────────────────────────────────────────────────────────────────
# 8. Factures de vente (avec workflow issue/paid)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_invoices(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    clients: list[dict],
    articles: list[dict],
    result: SeedResult,
    n_invoices: int = 10,
    rng_seed: int = 42,
) -> list[dict]:
    """Crée des factures de vente étalées sur l'exercice, avec workflow issue/paid."""
    if not clients or not articles:
        info("Pas de clients ou d'articles — skip invoices")
        return []
    rng = random.Random(rng_seed)
    created_invoices: list[dict] = []

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        TimeElapsedColumn(),
        console=__import__("seed_modern").console,
        transient=True,
    ) as progress:
        task = progress.add_task(f"[cyan]Factures de vente ({n_invoices})", total=n_invoices)

        for i in range(n_invoices):
            client = rng.choice(clients)
            issue_date = _random_date_in_fy(rng)
            due_date = issue_date + timedelta(days=rng.choice([15, 30, 45, 60]))

            # 1 à 5 lignes par facture
            n_lines = rng.randint(1, min(5, len(articles)))
            chosen_articles = rng.sample(articles, n_lines)
            lines = []
            for art in chosen_articles:
                is_wholesale = client.get("name") in [c["name"] for c in profile.clients_wholesale]
                base_price = art["price"]
                discount = rng.choice([0, 0, 5, 10]) if is_wholesale else 0
                qty = rng.randint(1, 20) if is_wholesale else rng.randint(1, 3)
                lines.append({
                    "description": art["label"],
                    "quantity": qty,
                    "unitPrice": base_price,
                    "discountPercent": discount,
                    "taxRate": 10,  # TVA Haïti 10% standard
                })

            body = {
                "thirdPartyId": client["id"],
                "type": "STANDARD",
                "issueDate": issue_date.isoformat(),
                "dueDate": due_date.isoformat(),
                "currency": profile.currency,
                "lines": lines,
                "creditNoteForInvoiceId": None,
            }
            idem = api.idem_key("inv-sales", i)
            resp = await api.post(
                f"/api/v1/companies/{company_id}/invoices",
                body,
                headers={"Idempotency-Key": idem},
            )
            if resp.status_code >= 400:
                progress.advance(task)
                continue
            inv_data = resp.json()
            inv_id = inv_data.get("id")
            if not inv_id:
                progress.advance(task)
                continue
            balance_due = inv_data.get("balanceDue") or inv_data.get("totalAmount") or 0
            # v9.4 fix — Normalize status to uppercase string for consistent comparison
            inv_status = str(inv_data.get("status", "DRAFT")).upper()
            created_invoices.append({"id": inv_id, "balance_due": balance_due, "issue_date": issue_date, "status": inv_status})

            # Workflow aléatoire : 70% PAID, 20% ISSUED, 10% DRAFT
            days_ago = (date.today() - issue_date).days
            if days_ago > 30:
                roll = rng.random()
                if roll < 0.75:
                    # Issue + mark paid
                    await api.post(f"/api/v1/companies/{company_id}/invoices/{inv_id}/issue")
                    amount = balance_due if balance_due and balance_due > 0 else 1
                    await api.post(
                        f"/api/v1/companies/{company_id}/invoices/{inv_id}/record-payment",
                        {"amount": amount},
                    )
                    # Update status in our list
                    created_invoices[-1]["status"] = "PAID"
                elif roll < 0.95:
                    await api.post(f"/api/v1/companies/{company_id}/invoices/{inv_id}/issue")
                    created_invoices[-1]["status"] = "ISSUED"
            else:
                if rng.random() < 0.5:
                    await api.post(f"/api/v1/companies/{company_id}/invoices/{inv_id}/issue")
                    created_invoices[-1]["status"] = "ISSUED"

            progress.advance(task)

    result.sales_invoices_count = len(created_invoices)
    ok(f"{len(created_invoices)} factures de vente créées")
    return created_invoices


# ──────────────────────────────────────────────────────────────────────────────
# 9. Factures d'achat (unified /invoices?direction=PURCHASE)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_purchase_invoices(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    suppliers: list[dict],
    articles: list[dict],
    result: SeedResult,
    n_invoices: int = 5,
    rng_seed: int = 43,
) -> list[dict]:
    """Crée des factures d'achat via /invoices?direction=PURCHASE."""
    if not suppliers:
        info("Pas de fournisseurs — skip purchase invoices")
        return []
    rng = random.Random(rng_seed)
    created: list[dict] = []
    articles_for_purchase = articles if articles else []

    for i in range(n_invoices):
        supplier = rng.choice(suppliers)
        issue_date = _random_date_in_fy(rng)
        due_date = issue_date + timedelta(days=rng.choice([30, 45, 60]))

        if articles_for_purchase:
            n_lines = rng.randint(1, min(5, len(articles_for_purchase)))
            chosen = rng.sample(articles_for_purchase, n_lines)
            lines = []
            for art in chosen:
                qty = rng.randint(5, 50)
                lines.append({
                    "description": art["label"],
                    "quantity": qty,
                    "unitPrice": art["cost"],
                    "taxRate": 10,
                })
        else:
            # Pour les profils sans stock (ONG, services) — facture générique
            amount = rng.randint(15000, 250000)
            lines = [{
                "description": f"Prestation de services — {supplier['name']}",
                "quantity": 1,
                "unitPrice": amount,
                "taxRate": 10,
            }]

        body = {
            "thirdPartyId": supplier["id"],
            "type": "STANDARD",
            "supplierReference": f"FAC-{supplier['name'][:3].upper()}-{issue_date.strftime('%Y%m')}-{i+1:03d}",
            "issueDate": issue_date.isoformat(),
            "dueDate": due_date.isoformat(),
            "currency": profile.currency,
            "lines": lines,
        }
        idem = api.idem_key("inv-purch", i)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/invoices?direction=PURCHASE",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            continue
        pi_data = resp.json()
        pi_id = pi_data.get("id")
        if not pi_id:
            continue
        balance_due = pi_data.get("balanceDue") or pi_data.get("totalAmount") or 0
        created.append({"id": pi_id, "balance_due": balance_due, "issue_date": issue_date})

        # Receive (issue) la facture
        await api.post(f"/api/v1/companies/{company_id}/invoices/{pi_id}/issue")
        # Paiement fournisseur pour les factures anciennes
        days_ago = (date.today() - issue_date).days
        if days_ago > 30 and balance_due and balance_due > 0:
            await api.post(
                f"/api/v1/companies/{company_id}/invoices/{pi_id}/record-payment",
                {"amount": balance_due},
            )

    result.purchase_invoices_count = len(created)
    ok(f"{len(created)} factures d'achat créées")
    return created


# ──────────────────────────────────────────────────────────────────────────────
# 10. Capital d'ouverture (écriture OD)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_capital_opening(
    api: ApiClient,
    company_id: str,
    accounts: dict,
    profile: BusinessProfile,
    result: SeedResult,
) -> None:
    """Crée l'écriture de capital d'ouverture (journal OD)."""
    bank_account = _pick_account(accounts, "521", "52", "57")
    capital_account = _pick_account(accounts, "101", "10")
    if not bank_account or not capital_account:
        warn("Comptes capital/banque non trouvés — skip capital d'ouverture")
        return
    stock_account = _pick_account(accounts, "331", "33", "30", "31")
    vehicle_account = _pick_account(accounts, "244", "24", "22", "23")

    capital_total = 5_000_000  # 5M HTG / USD selon profile
    bank_part = 3_500_000
    stock_part = 1_000_000 if stock_account else 0
    vehicle_part = 500_000 if vehicle_account else 0
    # Recalculer capital_total pour équilibrer
    capital_total = bank_part + stock_part + vehicle_part

    lines = [
        {"accountCode": _account_code(accounts, bank_account), "thirdPartyId": None,
         "debit": bank_part, "credit": 0, "description": "Apport en capital libéré (banque)"},
    ]
    if stock_account:
        lines.append({"accountCode": _account_code(accounts, stock_account), "thirdPartyId": None,
                      "debit": stock_part, "credit": 0, "description": "Stock initial"})
    if vehicle_account:
        lines.append({"accountCode": _account_code(accounts, vehicle_account), "thirdPartyId": None,
                      "debit": vehicle_part, "credit": 0, "description": "Véhicule existant"})
    lines.append({"accountCode": _account_code(accounts, capital_account), "thirdPartyId": None,
                  "debit": 0, "credit": capital_total,
                  "description": "Capital social (apports des associés)"})

    body = {
        "journalCode": "OD",  # v9.4 fix — AN n'existe pas, utiliser OD
        "entryDate": FY_START_DEFAULT.isoformat(),
        "description": "Écriture d'ouverture — Capital social + apports initiaux",
        "lines": lines,
        "sourceModule": "MANUAL",
    }
    idem = api.idem_key("capital", 0)
    resp = await api.post(
        f"/api/v1/companies/{company_id}/accounting-engine/journal-entries",
        body,
        headers={"Idempotency-Key": idem},
    )
    if resp.status_code < 300:
        ok(f"Capital d'ouverture créé : {capital_total:,} {profile.currency} "
           f"(banque {bank_part:,} + stock {stock_part:,} + véhicule {vehicle_part:,}) ← capital {capital_total:,}")
    else:
        warn(f"Capital d'ouverture non créé : HTTP {resp.status_code}")


def _account_code(accounts: dict, account_id: str) -> Optional[str]:
    """Retourne le code du compte depuis son ID."""
    for code, acc in accounts.items():
        if acc.get("id") == account_id:
            return code
    return None


# ──────────────────────────────────────────────────────────────────────────────
# 11. Commandes fournisseurs (purchase-orders)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_purchase_orders(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    suppliers: list[dict],
    articles: list[dict],
    result: SeedResult,
    n_pos: int = 3,
    rng_seed: int = 44,
) -> None:
    """Crée des commandes fournisseurs avec change-status (query param).

    v9.4 fix — Le backend CreatePurchaseOrderRequest exige :
    - supplierId (NotNull UUID) — pas "thirdPartyId"
    - orderNumber (NotBlank String) — numéro de commande unique
    - orderDate (NotNull LocalDate)
    - lines[].itemId (optionnel), description, quantity, unitPrice (pas de taxRate)
    """
    if not suppliers or not articles:
        info("Pas de fournisseurs/articles — skip purchase orders")
        return
    rng = random.Random(rng_seed)
    created_count = 0
    for i in range(n_pos):
        supplier = rng.choice(suppliers)
        order_date = _random_date_in_fy(rng)
        n_lines = rng.randint(1, min(5, len(articles)))
        chosen = rng.sample(articles, n_lines)
        lines = []
        for art in chosen:
            qty = rng.randint(5, 50)
            lines.append({
                "itemId": art.get("id"),
                "description": art["label"],
                "quantity": qty,
                "unitPrice": str(art["cost"]),
            })
        body = {
            "supplierId": supplier["id"],
            "orderNumber": f"PO-{i+1:03d}-{rng.randint(1000, 9999)}",
            "orderDate": order_date.isoformat(),
            "currency": profile.currency,
            "lines": lines,
        }
        idem = api.idem_key("po", i)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/purchase-orders",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            if i < 3:
                warn(f"PO #{i+1} non créée : HTTP {resp.status_code} - {resp.text[:200]}")
            continue
        po_id = resp.json().get("id")
        if not po_id:
            continue
        created_count += 1
        # v9.4 fix — status en query param, pas dans le body
        days_ago = (date.today() - order_date).days
        if days_ago > 15:
            await api.post(
                f"/api/v1/companies/{company_id}/purchase-orders/{po_id}/change-status?status=SUBMITTED"
            )
        if days_ago > 30:
            await api.post(
                f"/api/v1/companies/{company_id}/purchase-orders/{po_id}/change-status?status=RECEIVED"
            )

    result.purchase_orders_count = created_count
    ok(f"{created_count} commandes fournisseurs créées")


# ──────────────────────────────────────────────────────────────────────────────
# 12. Notes de frais (expenses)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_expense_reports(
    api: ApiClient,
    company_id: str,
    accounts: dict,
    profile: BusinessProfile,
    result: SeedResult,
    n_expenses: int = 3,
    rng_seed: int = 45,
) -> None:
    """Crée des notes de frais (submit + approve pour les anciennes)."""
    if not accounts:
        info("Plan comptable vide — skip expense reports")
        return
    rng = random.Random(rng_seed)
    expense_account = _pick_account(accounts, "625", "62", "605", "60")
    if not expense_account:
        warn("Compte de charge non trouvé — skip expense reports")
        return
    created = 0
    templates = [
        {"description": "Déplacement client Pétion-Ville — taxi", "amount_range": (500, 3500)},
        {"description": "Repas d'affaires — déjeuner équipe", "amount_range": (1500, 8500)},
        {"description": "Fournitures de bureau (stylos, cahiers)", "amount_range": (800, 4500)},
        {"description": "Carburant véhicule de service", "amount_range": (3500, 12000)},
        {"description": "Communications téléphoniques pro", "amount_range": (1200, 5500)},
    ]
    for i in range(n_expenses):
        tpl = rng.choice(templates)
        amount = round(rng.uniform(*tpl["amount_range"]), 2)
        exp_date = _random_date_in_fy(rng)
        body = {
            "description": tpl["description"],
            "expenseDate": exp_date.isoformat(),
            "currency": profile.currency,
            "lines": [{
                "description": tpl["description"],
                "amount": amount,
                "expenseAccountId": expense_account,
            }],
        }
        idem = api.idem_key("exp", i)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/expense-reports",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            continue
        exp_id = resp.json().get("id")
        if not exp_id:
            continue
        created += 1
        # Submit + approve pour les anciennes
        days_ago = (date.today() - exp_date).days
        if days_ago > 7:
            await api.post(f"/api/v1/companies/{company_id}/expense-reports/{exp_id}/submit")
        if days_ago > 15:
            await api.post(f"/api/v1/companies/{company_id}/expense-reports/{exp_id}/approve")

    result.expense_reports_count = created
    ok(f"{created} notes de frais créées")


# ──────────────────────────────────────────────────────────────────────────────
# 13. Employés + campagnes de paie
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_employees_and_payroll(
    api: ApiClient,
    company_id: str,
    profile: BusinessProfile,
    result: SeedResult,
    accounts: dict,
    n_employees: int = 3,
    rng_seed: int = 46,
) -> None:
    """Crée des employés + campagnes de paie (avril→septembre 2026).

    v9.4 fix — Le backend CreateEmployeeRequest exige :
    - employeeNumber (NotBlank)
    - hireDate (NotNull LocalDate)
    - baseSalary (NotNull @Positive BigDecimal)
    - contractType (NotNull ContractType enum: PERMANENT|FIXED_TERM|CONSULTANT)
    - collectiveAccountId (UUID) si thirdPartyName fourni (compte collectif employés, classe 42)
    On passe thirdPartyName + collectiveAccountId pour laisser le backend créer le tiers.
    """
    rng = random.Random(rng_seed)
    first_names = ["Jean", "Marie", "Pierre", "Jacqueline", "Robert", "Carline", "Wilner", "Mimose"]
    last_names = ["Pierre", "Joseph", "Charles", "Louis", "Dorcely", "Beauvais", "Saintilien", "Telfort"]
    positions = [
        {"title": "Comptable senior", "gross_salary": 75000},
        {"title": "Vendeur", "gross_salary": 35000},
        {"title": "Magasinier", "gross_salary": 32000},
        {"title": "Livreur", "gross_salary": 28000},
        {"title": "Directeur commercial", "gross_salary": 120000},
        {"title": "Caissier", "gross_salary": 30000},
    ]

    # Résoudre le compte collectif employés (classe 42)
    collective_account_id = _pick_account(accounts, "421", "422", "42", "4")
    if not collective_account_id:
        warn("Compte collectif employés (42x) non trouvé — skip employees")
        return

    employees_created: list[str] = []
    for i in range(n_employees):
        first = rng.choice(first_names)
        last = rng.choice(last_names)
        position = rng.choice(positions)
        body = {
            "thirdPartyName": f"{first} {last}",
            "collectiveAccountId": collective_account_id,
            "employeeNumber": f"EMP-{i+1:03d}-{rng.randint(1000, 9999)}",
            "position": position["title"],
            "department": rng.choice(["Comptabilité", "Ventes", "Logistique", "Direction"]),
            "hireDate": FY_START_DEFAULT.isoformat(),
            "baseSalary": str(position["gross_salary"]),
            "salaryCurrency": profile.currency,
            "contractType": "PERMANENT",
        }
        idem = api.idem_key("emp", i)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/employees",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code < 300:
            emp_id = resp.json().get("id")
            if emp_id:
                employees_created.append(emp_id)
        elif i < 3:
            warn(f"Employé {first} {last} non créé : HTTP {resp.status_code} - {resp.text[:200]}")
    result.employees_count = len(employees_created)

    # Campagnes de paie — avril à septembre 2026 (6 mois)
    payroll_months = [
        (2026, 4), (2026, 5), (2026, 6),
        (2026, 7), (2026, 8), (2026, 9),
    ]
    payroll_created = 0
    for year, month in payroll_months:
        body = {
            "year": year,
            "month": month,
            "description": f"Paie {month:02d}/{year}",
        }
        idem = api.idem_key("payroll", year * 100 + month)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/payroll-runs",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code >= 400:
            continue
        run_id = resp.json().get("id")
        if not run_id:
            continue
        # Calculate + approve + pay
        try:
            await api.post(f"/api/v1/companies/{company_id}/payroll-runs/{run_id}/calculate")
            await api.post(f"/api/v1/companies/{company_id}/payroll-runs/{run_id}/approve")
            await api.post(f"/api/v1/companies/{company_id}/payroll-runs/{run_id}/pay")
            payroll_created += 1
        except Exception:
            pass

    result.payroll_runs_count = payroll_created
    ok(f"{len(employees_created)} employés + {payroll_created} campagnes de paie créés "
       f"(collectiveAccount={collective_account_id[:8]}...)")


# ──────────────────────────────────────────────────────────────────────────────
# 14. Écritures manuelles (salaires, loyers, charges)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_journal_entries(
    api: ApiClient,
    company_id: str,
    accounts: dict,
    profile: BusinessProfile,
    result: SeedResult,
    n_entries: int = 5,
    rng_seed: int = 47,
) -> None:
    """Crée des écritures manuelles (OD)."""
    if not accounts:
        info("Plan comptable vide — skip journal entries")
        return
    rng = random.Random(rng_seed)
    bank_account = _pick_account(accounts, "521", "52", "57")
    salary_account = _pick_account(accounts, "661", "66")
    rent_account = _pick_account(accounts, "622", "62")
    charges_account = _pick_account(accounts, "627", "626", "62")
    if not bank_account:
        warn("Compte banque non trouvé — skip journal entries")
        return

    templates = []
    if salary_account:
        templates.append({
            "description": "Salaires du mois (net)",
            "amount_range": (45000, 180000),
            "debit_code": _account_code(accounts, salary_account),
            "credit_code": _account_code(accounts, bank_account),
        })
    if rent_account:
        templates.append({
            "description": "Loyer bureau (mensuel)",
            "amount_range": (25000, 75000),
            "debit_code": _account_code(accounts, rent_account),
            "credit_code": _account_code(accounts, bank_account),
        })
    if charges_account:
        templates.append({
            "description": "Charges diverses (eau, électricité)",
            "amount_range": (8000, 35000),
            "debit_code": _account_code(accounts, charges_account),
            "credit_code": _account_code(accounts, bank_account),
        })
    if not templates:
        warn("Aucun template exploitable — skip journal entries")
        return

    created = 0
    for i in range(n_entries):
        tpl = rng.choice(templates)
        amount = round(rng.uniform(*tpl["amount_range"]), 2)
        entry_date = _random_date_in_fy(rng)
        body = {
            "journalCode": "OD",
            "entryDate": entry_date.isoformat(),
            "description": f"{tpl['description']} — {entry_date.strftime('%d/%m/%Y')}",
            "lines": [
                {"accountCode": tpl["debit_code"], "thirdPartyId": None,
                 "debit": amount, "credit": 0, "description": tpl["description"]},
                {"accountCode": tpl["credit_code"], "thirdPartyId": None,
                 "debit": 0, "credit": amount, "description": "Contrepartie"},
            ],
            "sourceModule": "MANUAL",
        }
        idem = api.idem_key("od", i)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/accounting-engine/journal-entries",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code < 300:
            created += 1

    result.journal_entries_count += created
    ok(f"{created} écritures manuelles créées")


# ──────────────────────────────────────────────────────────────────────────────
# 15. Écritures bancaires (BQ)
# ──────────────────────────────────────────────────────────────────────────────

async def step_create_bank_movements(
    api: ApiClient,
    company_id: str,
    accounts: dict,
    profile: BusinessProfile,
    result: SeedResult,
    n_movements: int = 5,
    rng_seed: int = 48,
) -> None:
    """Crée des écritures bancaires (BQ) : virements, dépôts, retraits, frais."""
    if not accounts:
        info("Plan comptable vide — skip bank movements")
        return
    bank_account = _pick_account(accounts, "521", "52", "57")
    if not bank_account:
        warn("Compte banque non trouvé — skip bank movements")
        return
    bank_code = _account_code(accounts, bank_account)
    interest_account = _pick_account(accounts, "650", "65", "66")
    bank_charges_account = _pick_account(accounts, "627", "626", "62")
    cash_account = _pick_account(accounts, "570", "57", "530")

    rng = random.Random(rng_seed)
    templates = []
    if interest_account:
        templates.append({
            "description": "Intérêts bancaires créditeurs",
            "amount_range": (1500, 8000),
            "debit_code": bank_code,
            "credit_code": _account_code(accounts, interest_account),
        })
    if bank_charges_account:
        templates.append({
            "description": "Frais bancaires (tenue de compte)",
            "amount_range": (300, 2500),
            "debit_code": _account_code(accounts, bank_charges_account),
            "credit_code": bank_code,
        })
    if cash_account:
        cash_code = _account_code(accounts, cash_account)
        templates.append({
            "description": "Retrait espèces pour petite caisse",
            "amount_range": (5000, 50000),
            "debit_code": cash_code,
            "credit_code": bank_code,
        })
        templates.append({
            "description": "Dépôt espèces en banque (recettes journée)",
            "amount_range": (10000, 150000),
            "debit_code": bank_code,
            "credit_code": cash_code,
        })

    if not templates:
        warn("Aucun template bancaire — skip")
        return

    created = 0
    for i in range(n_movements):
        tpl = rng.choice(templates)
        amount = round(rng.uniform(*tpl["amount_range"]), 2)
        entry_date = _random_date_in_fy(rng)
        body = {
            "journalCode": "BQ",
            "entryDate": entry_date.isoformat(),
            "description": f"{tpl['description']} — {entry_date.strftime('%d/%m/%Y')}",
            "lines": [
                {"accountCode": tpl["debit_code"], "thirdPartyId": None,
                 "debit": amount, "credit": 0, "description": tpl["description"]},
                {"accountCode": tpl["credit_code"], "thirdPartyId": None,
                 "debit": 0, "credit": amount, "description": "Contrepartie"},
            ],
            "sourceModule": "MANUAL",
        }
        idem = api.idem_key("bq", i)
        resp = await api.post(
            f"/api/v1/companies/{company_id}/accounting-engine/journal-entries",
            body,
            headers={"Idempotency-Key": idem},
        )
        if resp.status_code < 300:
            created += 1

    result.journal_entries_count += created
    ok(f"{created} écritures bancaires (BQ) créées")

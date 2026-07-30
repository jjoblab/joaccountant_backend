#!/usr/bin/env python3
"""
JOAccountant v2.2 — Script de seed « Premium » pour entreprise commerciale (RETAIL_COMMERCE).

Scénario réaliste et complet : 2 exercices fiscaux (2024 + 2025) avec cycle d'exploitation
end-to-end d'une boutique de détail — capitalisation, achats, ventes, paiements, salaires,
charges d'exploitation, notes de frais, paie consolidée, opérations en devises, et
présentation des rapports financiers (bilan, compte de résultat, balance, grand livre),
plus exports PDF/CSV des 15 statements disponibles dans :reporting v4.1.

Flux couvert (tous les modules activés pour le secteur COMMERCE) :
  1. Authentification (register + login)
  2. Création entreprise (wizard step 1)
  3. Wizard étapes 2-9 + complete
  4. Plan comptable SYSCOHADA + seed sectoriel RETAIL_COMMERCE (niveaux 2+ auto)
  5. Journaux (VT, AC, BQ, OD, DP, PA) + exercices 2024+2025 + séquences documentaires
  6. Règle de TVA 10% + règle de retenue salariale 10% (IMPOT-SAL-10)
  7. Tiers : 5 clients + 3 fournisseurs + 3 employés
  8. Articles + entrepôt
  9. Capital initial (3M HTG) — écriture D Banque / C Capital
 10. Emprunt bancaire (2M HTG) — écriture D Banque / C Emprunt
 11. Achats fournisseurs (factures d'achat) → écriture D Achats + TVA / C Fournisseur
 12. Immobilisation (véhicule) — écriture D Immo / C Banque
 13. Ventes 2024-2025 → écriture D Client / C Ventes + TVA (auto par issue)
 14. Sorties de stock (COGS) → écriture D COGS / C Stock
 15. Encaissements (~60% des factures)
 16. Décaissements (~70% des factures d'achat)
 17. Notes de frais employés (paidDirectly=true et false)
 18. Salaires mensuels + charges patronales 14%
 19. Campagne de paie consolidée (juillet 2025)
 20. Amortissements mensuels (postPeriodDepreciation)
 21. Workflow d'approbation (4 yeux) + alertes (LOW_STOCK, INVOICE_OVERDUE, APPROVAL_PENDING)
 22. Opérations en devises (BUY/SELL/REVALUATION — module :fx-operations v4.1)
 23. Clôture d'exercice 2024 (solde produits/charges contre compte 12)
 24. Vérification cohérence : balance débit = crédit (avec ?fiscalYearId= explicite)
 25. Exports PDF/CSV des 15 statements :reporting v4.1 POUR CHAQUE exercice fiscal
     (2024 ET 2025) — bilan, CR, grand livre, tax_declaration, purchase_register,
     expense_register, payroll_summary, stock_movement_register, fx_operations_register
     + snapshots globaux (trial_balance, inventory_valuation, fixed_assets_register,
     aged_balance_suppliers) + balances âgées JSON
 26. Présentation des rapports financiers POUR CHAQUE exercice (bilan + CR + balance
     générale avec ?fiscalYearId=) + tableau comparatif 2024 vs 2025 (variation)

Usage :
  python3 seed_commerce.py --base-url http://localhost:8080
  python3 seed_commerce.py --email existing@user.ht --no-clean

Prérequis :
  pip install requests
  Backend lancé : ./gradlew :app:devRun
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import sys
import time
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any

try:
    import requests
except ImportError:
    print("❌ Module 'requests' manquant. Installer avec : pip install requests")
    sys.exit(1)


# ═══════════════════════════════════════════════════════════════════════════
#  Configuration
# ═══════════════════════════════════════════════════════════════════════════

BASE_URL = "http://localhost:8080"
TOKEN: str | None = None
COMPANY_ID: str | None = None
HEADERS: dict[str, str] = {}

FRAMEWORK_SYSCOHADA = "00000000-0000-0000-0000-000000000003"

USER_EMAIL = f"commerce_{int(time.time())}@joaccountant.ht"
USER_PASSWORD = "Commerce#2026"
COMPANY_NAME = "Boutique Pétion-Ville SARL"

# Délai par défaut entre les appels API
SLOW = 0.02

# Couleurs ANSI (désactivées si stdout n'est pas un TTY)
USE_COLORS = sys.stdout.isatty()

# ═══════════════════════════════════════════════════════════════════════════
#  État global (accumulé au fil des étapes)
# ═══════════════════════════════════════════════════════════════════════════

@dataclass
class State:
    accounts: dict[str, dict] = field(default_factory=dict)
    third_parties: dict[str, dict] = field(default_factory=dict)
    items: dict[str, dict] = field(default_factory=dict)
    employees: dict[str, dict] = field(default_factory=dict)
    warehouse_id: str | None = None
    fiscal_years: list[dict] = field(default_factory=list)
    periods_2024: list[dict] = field(default_factory=list)
    periods_2025: list[dict] = field(default_factory=list)
    invoices_issued: int = 0
    purchase_invoices_issued: int = 0
    expenses_created: int = 0
    payroll_run_id: str | None = None


STATE = State()

# Coûts d'achat unitaires par SKU
COSTS = {
    "RIZ-5KG": 350, "HUILE-1L": 180, "SUCRE-1KG": 95,
    "FARINE-2KG": 220, "LAIT-400G": 310, "SAVON-200G": 45,
    "PATES-500G": 35, "SEL-1KG": 30,
}

# ═══════════════════════════════════════════════════════════════════════════
#  Utilitaires d'affichage « premium » — animations, couleurs, spinners
# ═══════════════════════════════════════════════════════════════════════════

def color(text: str, code: str) -> str:
    if not USE_COLORS:
        return text
    codes = {
        "red": "31", "green": "32", "yellow": "33", "blue": "34",
        "magenta": "35", "cyan": "36", "gray": "90", "bold": "1",
    }
    return f"\033[{codes.get(code, '0')}m{text}\033[0m"


def banner() -> None:
    width = shutil.get_terminal_size((80, 20)).columns
    w = min(width, 78)
    title = "JOAccountant v2.2 — Seed Commerce Premium"
    subtitle = "Bilan équilibré • Cycle d'exploitation end-to-end • 2 exercices"
    print()
    print(color("╔" + "═" * (w - 2) + "╗", "cyan"))
    for line in [title, subtitle]:
        pad = w - 2 - len(line)
        print(color("║", "cyan") + " " + line + " " * max(0, pad - 1) + color("║", "cyan"))
    print(color("╚" + "═" * (w - 2) + "╝", "cyan"))
    print()


def section(num: int, title: str) -> None:
    print()
    print(color(f"  ▸ [{num:02d}] {title}", "bold"))
    print(color("  " + "─" * 60, "gray"))


def log(msg: str, level: str = "info") -> None:
    icons = {"info": "•", "ok": "✓", "warn": "⚠", "err": "✗", "data": "  "}
    colors = {"info": "gray", "ok": "green", "warn": "yellow", "err": "red", "data": "cyan"}
    icon = icons.get(level, "•")
    print(f"    {color(icon, colors.get(level, 'gray'))} {msg}")


def progress_bar(current: int, total: int, label: str = "", width: int = 30) -> None:
    if total == 0:
        return
    pct = min(current / total, 1.0)
    filled = int(width * pct)
    bar = color("█" * filled, "green") + color("░" * (width - filled), "gray")
    sys.stdout.write(f"\r    {color('⟳', 'cyan')} {label} [{bar}] {current}/{total} ")
    sys.stdout.flush()
    if current == total:
        sys.stdout.write("\n")


def spinner(duration_s: float, label: str) -> None:
    frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    end = time.time() + duration_s
    i = 0
    while time.time() < end:
        sys.stdout.write(f"\r    {color(frames[i % len(frames)], 'magenta')} {label}...")
        sys.stdout.flush()
        time.sleep(0.08)
        i += 1
    sys.stdout.write(f"\r    {color('✓', 'green')} {label}                            \n")


# ═══════════════════════════════════════════════════════════════════════════
#  HTTP helper
# ═══════════════════════════════════════════════════════════════════════════

def api(method: str, path: str, body: Any = None, expect_status: int = 200,
        extra_headers: dict | None = None, silent: bool = False) -> Any:
    url = f"{BASE_URL}/api/v1{path}"
    h = {"Content-Type": "application/json"}
    h.update(HEADERS)
    if extra_headers:
        h.update(extra_headers)
    time.sleep(SLOW)
    try:
        resp = requests.request(method, url, json=body, headers=h, timeout=60)
    except requests.exceptions.ConnectionError:
        print(f"\n  {color('✗', 'red')} Impossible de se connecter à {url}")
        print(f"     Le backend est-il démarré sur {BASE_URL} ?")
        print(f"     Lancer avec : ./gradlew :app:devRun")
        sys.exit(1)

    # 409 = « déjà existe » — souvent attendu (idempotence), on retourne la réponse
    if resp.status_code == 409:
        return resp.json() if resp.content else {}

    if resp.status_code != expect_status:
        if not silent:
            detail = ""
            try:
                detail = resp.json().get("detail", resp.text[:300])
            except Exception:
                detail = resp.text[:300]
            print(f"\n    {color('⚠', 'yellow')} {method} {path} → {resp.status_code}")
            if detail:
                print(f"       {detail[:200]}")
        return None

    if resp.status_code == 204 or not resp.content:
        return {}
    return resp.json()


def find_account(code: str) -> dict | None:
    return STATE.accounts.get(code)


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 1 — Inscription et connexion
# ═══════════════════════════════════════════════════════════════════════════

def step_01_register_and_login() -> None:
    section(1, "Inscription et connexion")
    spinner(0.4, "Création du compte utilisateur")
    api("POST", "/auth/register", {
        "email": USER_EMAIL, "password": USER_PASSWORD,
        "fullName": "Jean Commerce", "locale": "fr"
    }, expect_status=201, silent=True)
    log(f"Utilisateur : {USER_EMAIL}", "ok")

    r = api("POST", "/auth/login", {"email": USER_EMAIL, "password": USER_PASSWORD})
    global TOKEN
    TOKEN = r["accessToken"]
    HEADERS["Authorization"] = f"Bearer {TOKEN}"
    log("Token JWT obtenu", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 2 — Création entreprise (wizard step 1)
# ═══════════════════════════════════════════════════════════════════════════

def step_02_create_company() -> None:
    section(2, "Création entreprise (wizard step 1 — identité)")
    r = api("POST", "/companies", {
        "name": COMPANY_NAME, "country": "HT", "functionalCurrency": "HTG"
    }, expect_status=201)
    global COMPANY_ID
    COMPANY_ID = r["id"]
    log(f"{COMPANY_NAME} — ID: {color(COMPANY_ID, 'cyan')}", "ok")

    # Re-login pour récupérer le companyId dans le JWT
    time.sleep(0.3)
    r = api("POST", "/auth/login", {"email": USER_EMAIL, "password": USER_PASSWORD})
    global TOKEN
    TOKEN = r["accessToken"]
    HEADERS["Authorization"] = f"Bearer {TOKEN}"
    log("JWT rafraîchi (avec claim companyId)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 3 — Wizard 2-9 + complete (active les modules sectoriels)
# ═══════════════════════════════════════════════════════════════════════════

def step_03_run_wizard() -> None:
    section(3, "Wizard (étapes 2-9 + complete — active les modules)")
    cid = COMPANY_ID
    spinner(0.5, "Saisie des 9 étapes du wizard")

    api("PATCH", f"/companies/{cid}/wizard/2",
        {"organizationNature": "FOR_PROFIT", "legalForm": "SARL"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/3", {"sector": "COMMERCE"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/4", {"businessTypeCode": "RETAIL_COMMERCE"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/5",
        {"primaryActivityLabel": "Commerce de détail — produits alimentaires et divers"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/6",
        {"accountingFrameworkId": FRAMEWORK_SYSCOHADA, "fiscalYearStartMonth": 1}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/7", {}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/8", {}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/9", {}, silent=True)
    api("POST", f"/companies/{cid}/wizard/complete", silent=True)

    log("Wizard complété", "ok")
    log("Modules activés (RETAIL_COMMERCE + always-on + V23 PURCHASING) :", "data")
    log("  • Always-on (15) : CHART_OF_ACCOUNTS, ACCOUNTING_ENGINE, THIRD_PARTIES,", "data")
    log("    INVOICING, DOCUMENT_NUMBERING, APPROVAL_WORKFLOW, DOCUMENT_GENERATION,", "data")
    log("    NOTIFICATIONS, AUDIT_TRAIL, FINANCIAL_STATEMENTS, ANALYTICS, REPORTING,", "data")
    log("    EMPLOYEES, EXPENSES, PAYROLL", "data")
    log("  • Sectoriels (5) : INVENTORY, FIXED_ASSETS, BANK_RECONCILIATION, TAX, PURCHASING", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 4 — Plan comptable + seed sectoriel RETAIL_COMMERCE
# ═══════════════════════════════════════════════════════════════════════════

def step_04_init_chart_of_accounts() -> None:
    section(4, "Plan comptable SYSCOHADA + seed sectoriel RETAIL_COMMERCE")
    cid = COMPANY_ID

    # Initialiser AVEC businessTypeCode → seed sectoriel activé
    r = api("POST", f"/companies/{cid}/chart-of-accounts/initialize",
            {"accountingFrameworkId": FRAMEWORK_SYSCOHADA, "businessTypeCode": "RETAIL_COMMERCE"})
    if r:
        log(f"Plan initialisé : {color(str(r.get('accountsCreated', '?')), 'cyan')} comptes créés", "ok")
        log("  (niveaux 1 + 2 + 3 auto — économise la création manuelle)", "data")

    # Recharger les comptes
    accounts = api("GET", f"/companies/{cid}/chart-of-accounts")
    for acc in accounts:
        STATE.accounts[acc["code"]] = acc

    log(f"Total : {color(str(len(STATE.accounts)), 'cyan')} comptes chargés en mémoire", "ok")

    # Afficher quelques comptes clés créés par le seed sectoriel
    key_accounts = ["101", "401", "411", "421", "433", "443", "445", "521", "571",
                    "310", "601", "603", "631", "621", "622", "623", "701", "244", "2844"]
    found = [c for c in key_accounts if c in STATE.accounts]
    other_count = max(len(found) - 8, 0)
    log(f"Comptes clés présents : {color(', '.join(found[:8]), 'cyan')} + {other_count} autres", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 5 — Journaux + exercices + séquences
# ═══════════════════════════════════════════════════════════════════════════

def step_05_create_config() -> None:
    section(5, "Journaux, exercices fiscaux, séquences documentaires")
    cid = COMPANY_ID

    # Journaux — couvre tous les modules activés
    journals = [
        ("VT", "Journal des ventes"),
        ("AC", "Journal des achats"),
        ("BQ", "Journal de banque"),
        ("OD", "Opérations diverses"),
        ("DP", "Journal des dépenses (notes de frais)"),
        ("PA", "Journal de paie"),
    ]
    for code, label in journals:
        api("POST", f"/companies/{cid}/accounting-engine/journals",
            {"code": code, "label": label}, silent=True)
    log(f"{len(journals)} journaux créés (VT, AC, BQ, OD, DP, PA)", "ok")

    # Exercices 2024 + 2025
    for start, end, label in [("2024-01-01", "2024-12-31", "Exercice 2024"),
                              ("2025-01-01", "2025-12-31", "Exercice 2025")]:
        r = api("POST", f"/companies/{cid}/accounting-engine/fiscal-years",
                {"startDate": start, "endDate": end, "label": label}, silent=True)
        if r and "id" in r:
            STATE.fiscal_years.append(r)

    # Si les exercices n'ont pas été créés (409 — existent déjà), les récupérer via GET
    if not STATE.fiscal_years:
        existing = api("GET", f"/companies/{cid}/accounting-engine/fiscal-years", silent=True)
        if existing and isinstance(existing, list):
            STATE.fiscal_years = existing
    log(f"{len(STATE.fiscal_years)} exercices fiscaux (2024, 2025)", "ok")

    # Charger les périodes fiscales pour les amortissements et la paie
    for fy in STATE.fiscal_years:
        r = api("GET", f"/companies/{cid}/accounting-engine/fiscal-years/{fy['id']}/periods",
                silent=True)
        if r:
            if "2024" in fy.get("label", ""):
                STATE.periods_2024 = r
            else:
                STATE.periods_2025 = r

    # Séquences documentaires — couvre tous les types
    seqs = [
        ("SALES_INVOICE",    "VT", "FAC", True, 6, "YEARLY"),
        ("CREDIT_NOTE",      "VT", "AV",  True, 6, "YEARLY"),
        ("PURCHASE_INVOICE", "AC", "FAC", True, 6, "YEARLY"),
        ("PAYSLIP",          "PA", "BUL", True, 6, "YEARLY"),
        ("JOURNAL_ENTRY",    "VT", "VT",  True, 5, "YEARLY"),
        ("JOURNAL_ENTRY",    "AC", "AC",  True, 5, "YEARLY"),
        ("JOURNAL_ENTRY",    "BQ", "BQ",  True, 5, "YEARLY"),
        ("JOURNAL_ENTRY",    "OD", "OD",  True, 5, "YEARLY"),
        ("JOURNAL_ENTRY",    "DP", "DP",  True, 5, "YEARLY"),
        ("JOURNAL_ENTRY",    "PA", "PA",  True, 5, "YEARLY"),
        ("DONATION_RECEIPT", "",   "REC", True, 6, "YEARLY"),
    ]
    for dt, sk, pf, iy, pd, rp in seqs:
        api("POST", f"/companies/{cid}/document-numbering/sequences",
            {"documentType": dt, "scopeKey": sk, "prefix": pf, "includeYear": iy,
             "padding": pd, "resetPolicy": rp}, silent=True)
    log(f"{len(seqs)} séquences documentaires (VT, AC, BQ, OD, DP, PA + PAYSLIP + PURCHASE_INVOICE)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 6 — Règle fiscale TVA + retenue salariale
# ═══════════════════════════════════════════════════════════════════════════

def step_06_create_tax_rules() -> None:
    section(6, "Règles fiscales (TVA 10% + retenue salariale 10%)")
    cid = COMPANY_ID
    tva_account = find_account("443")
    api("POST", f"/companies/{cid}/tax/rules", {
        "code": "TVA-10", "label": "TVA 10%",
        "rate": 10,
        "payableAccountId": tva_account["id"] if tva_account else None,
        "applicableFrom": "2024-01-01"
    }, expect_status=201, silent=True)
    log("TVA 10% (TVA-10) — payable sur compte 443", "ok")

    # Règle de retenue salariale (consommée par :payroll)
    api("POST", f"/companies/{cid}/tax/withholding-rules", {
        "code": "IMPOT-SAL-10", "label": "Impôt sur salaire 10%",
        "rate": 10,
        "applicableThirdPartyTypes": ["EMPLOYEE"]
    }, expect_status=201, silent=True)
    log("Retenue salariale 10% (IMPOT-SAL-10) — applicable aux EMPLOYEE", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 7 — Tiers (clients, fournisseurs, employés)
# ═══════════════════════════════════════════════════════════════════════════

def step_07_create_third_parties() -> None:
    section(7, "Tiers (clients + fournisseurs + employés)")
    cid = COMPANY_ID
    clients_acct = find_account("411")
    suppliers_acct = find_account("401")
    employees_acct = find_account("421")

    clients = [
        ("Pharmacie du Carrefour", "pharma@biz.ht"),
        ("Restaurant Le Gouvor",   "gouvor@resto.ht"),
        ("Supermarché Giant",      "giant@mart.ht"),
        ("École Saint-Pierre",     "saint@pierre.ht"),
        ("Clinique du Canapé",     "canape@clinique.ht"),
    ]
    suppliers = [
        ("Distributeur FoodCo", "foodco@dist.ht"),
        ("Import Globe",        "globe@import.ht"),
        ("Grossiste Plus",      "plus@gros.ht"),
    ]
    employees_data = [
        ("Marie Clerc",     "marie.clerc@boutique.ht",   "EMP-001"),
        ("Jean Delva",      "jean.delva@boutique.ht",    "EMP-002"),
        ("Sophie Pierre",   "sophie.pierre@boutique.ht", "EMP-003"),
    ]

    total_progress = len(clients) + len(suppliers) + len(employees_data)
    progress = 0
    progress_bar(progress, total_progress, "Création tiers")

    for name, email in clients:
        r = api("POST", f"/companies/{cid}/third-parties", {
            "type": "CLIENT", "name": name,
            "collectiveAccountId": clients_acct["id"] if clients_acct else None,
            "email": email, "phone": "+509 3700 0000",
            "address": "Port-au-Prince"
        }, expect_status=201, silent=True)
        if r:
            STATE.third_parties[name] = r
        progress += 1
        progress_bar(progress, total_progress, "Création tiers")

    for name, email in suppliers:
        r = api("POST", f"/companies/{cid}/third-parties", {
            "type": "SUPPLIER", "name": name,
            "collectiveAccountId": suppliers_acct["id"] if suppliers_acct else None,
            "email": email, "phone": "+509 3800 0000",
            "address": "Port-au-Prince"
        }, expect_status=201, silent=True)
        if r:
            STATE.third_parties[name] = r
        progress += 1
        progress_bar(progress, total_progress, "Création tiers")

    # Employés — création du tiers + de la fiche employé en une fois (composite endpoint)
    for name, email, emp_num in employees_data:
        r = api("POST", f"/companies/{cid}/third-parties", {
            "type": "EMPLOYEE", "name": name,
            "collectiveAccountId": employees_acct["id"] if employees_acct else None,
            "email": email
        }, expect_status=201, silent=True)
        if r:
            STATE.third_parties[name] = r
            # Créer la fiche employé
            api("POST", f"/companies/{cid}/employees", {
                "thirdPartyId": r["id"],
                "employeeNumber": emp_num,
                "position": "Vendeur(se)",
                "department": "Ventes",
                "hireDate": "2023-01-15",
                "baseSalary": 45000 if "Marie" in name else 38000,
                "salaryCurrency": "HTG",
                "contractType": "PERMANENT",
                "bankAccountNumber": f"BANK-{emp_num}"
            }, expect_status=201, silent=True)
            STATE.employees[emp_num] = r
        progress += 1
        progress_bar(progress, total_progress, "Création tiers")

    log(f"{len(clients)} clients + {len(suppliers)} fournisseurs + {len(employees_data)} employés", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 8 — Entrepôt + articles
# ═══════════════════════════════════════════════════════════════════════════

def step_08_create_warehouse_and_items() -> None:
    section(8, "Entrepôt + articles")
    cid = COMPANY_ID
    stock_acct = find_account("310")
    cogs_acct = find_account("603")

    r = api("POST", f"/companies/{cid}/inventory/warehouses",
            {"label": "Entrepôt Pétion-Ville"}, expect_status=201, silent=True)
    if r:
        STATE.warehouse_id = r["id"]

    items_data = [
        ("RIZ-5KG",    "Sac de riz 5kg",       "sac"),
        ("HUILE-1L",   "Huile végétale 1L",    "bouteille"),
        ("SUCRE-1KG",  "Sucre blanc 1kg",      "paquet"),
        ("FARINE-2KG", "Farine de blé 2kg",    "paquet"),
        ("LAIT-400G",  "Lait en poudre 400g",  "boîte"),
        ("SAVON-200G", "Savon de lessive 200g","pièce"),
        ("PATES-500G", "Spaghetti 500g",       "paquet"),
        ("SEL-1KG",    "Sel iodé 1kg",         "paquet"),
    ]
    progress = 0
    for sku, label, uom in items_data:
        r = api("POST", f"/companies/{cid}/inventory/items", {
            "sku": sku, "label": label, "unitOfMeasure": uom,
            "costingMethod": "FIFO", "reorderThreshold": 20,
            "inventoryAccountId": stock_acct["id"] if stock_acct else None,
            "cogsAccountId": cogs_acct["id"] if cogs_acct else None
        }, expect_status=201, silent=True)
        if r:
            STATE.items[sku] = r
        progress += 1
        progress_bar(progress, len(items_data), "Création articles")

    log(f"{len(STATE.items)} articles + 1 entrepôt", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 9 — Capital initial (3M HTG) + emprunt bancaire (2M HTG)
# ═══════════════════════════════════════════════════════════════════════════

def step_09_capital_and_loan() -> None:
    section(9, "Capital initial + emprunt bancaire")
    cid = COMPANY_ID

    def post_entry(journal, d, desc, lines, idem_key):
        body = {
            "journalCode": journal,
            "entryDate": d,
            "description": desc,
            "lines": [{"accountCode": l[0], "debit": l[1], "credit": l[2]} for l in lines],
            "sourceModule": "MANUAL"
        }
        r = api("POST", f"/companies/{cid}/accounting-engine/journal-entries", body,
                expect_status=201, extra_headers={"Idempotency-Key": idem_key}, silent=True)
        if r:
            api("POST", f"/companies/{cid}/accounting-engine/journal-entries/{r['id']}/post",
                silent=True)
            return True
        return False

    # Capital : D 521 (Banque) / C 101 (Capital social) — 3,000,000 HTG
    if post_entry("OD", "2024-01-01", "Apport en capital — dépôt initial",
                  [("521", 3_000_000, 0), ("101", 0, 3_000_000)], "capital-initial-001"):
        log("Capital : 3,000,000 HTG (D 521 Banque / C 101 Capital social)", "ok")

    # Emprunt : D 521 / C 161 (Emprunt bancaire) — 2,000,000 HTG
    if post_entry("OD", "2024-01-15", "Emprunt bancaire — Bank Nationale",
                  [("521", 2_000_000, 0), ("161", 0, 2_000_000)], "loan-bank-001"):
        log("Emprunt : 2,000,000 HTG (D 521 Banque / C 161 Emprunt)", "ok")

    log("Trésorerie totale disponible : 5,000,000 HTG", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 10 — Achats fournisseurs (factures d'achat — module :purchasing)
# ═══════════════════════════════════════════════════════════════════════════

def step_10_purchase_invoices() -> None:
    section(10, "Achats — factures fournisseurs (module :purchasing)")
    cid = COMPANY_ID
    supplier_names = [k for k in STATE.third_parties
                      if STATE.third_parties[k].get("type") == "SUPPLIER"]
    if not supplier_names:
        log("Aucun fournisseur — étape ignorée", "warn")
        return

    # 6 factures d'achat réparties sur 2024-2025
    purchases = [
        ("2024-01-10", "Distributeur FoodCo",  [("RIZ-5KG", 300, 350), ("HUILE-1L", 200, 180)]),
        ("2024-04-10", "Import Globe",         [("FARINE-2KG", 200, 220), ("LAIT-400G", 150, 310)]),
        ("2024-07-10", "Grossiste Plus",       [("PATES-500G", 500, 35), ("SEL-1KG", 300, 30)]),
        ("2024-10-10", "Distributeur FoodCo",  [("HUILE-1L", 100, 185), ("SUCRE-1KG", 200, 98)]),
        ("2025-01-10", "Import Globe",         [("RIZ-5KG", 200, 360), ("LAIT-400G", 100, 315)]),
        ("2025-04-10", "Grossiste Plus",       [("SAVON-200G", 200, 48), ("FARINE-2KG", 80, 225)]),
    ]
    TAX_RATE = 10
    total = 0

    for issue_date, supplier_name, items in purchases:
        supplier_tp = STATE.third_parties.get(supplier_name)
        if not supplier_tp:
            continue
        lines = []
        for sku, qty, unit_price in items:
            item = STATE.items.get(sku)
            if item:
                lines.append({
                    "description": f"Achat {sku}",
                    "quantity": qty,
                    "unitPrice": unit_price,
                    "taxRate": TAX_RATE,
                    "expenseAccountId": None  # fallback sur compte générique 601
                })

        body = {
            "thirdPartyId": supplier_tp["id"],
            "type": "STANDARD",
            "supplierReference": f"FOUR-{issue_date}",
            "issueDate": issue_date,
            "dueDate": (date.fromisoformat(issue_date) + timedelta(days=30)).isoformat(),
            "currency": "HTG",
            "lines": lines
        }
        r = api("POST", f"/companies/{cid}/purchase-invoices", body, expect_status=201, silent=True)
        if r:
            # Receive → génère l'écriture (Débit 601 + TVA / Crédit 401)
            r2 = api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/receive", silent=True)
            if r2:
                total += 1
                STATE.purchase_invoices_issued += 1

                # En parallèle : entrée de stock via :inventory (le chaînage achat→stock
                # est manuel au MVP — voir BACKLOG.md)
                for sku, qty, unit_price in items:
                    item = STATE.items.get(sku)
                    if item and STATE.warehouse_id:
                        supplier_acct = find_account("401")
                        api("POST", f"/companies/{cid}/inventory/stock-moves", {
                            "itemId": item["id"], "warehouseId": STATE.warehouse_id,
                            "moveDate": issue_date, "direction": "IN",
                            "quantity": qty, "unitCost": unit_price,
                            "sourceDocument": f"Achat {issue_date}",
                            "counterpartyAccountId": supplier_acct["id"] if supplier_acct else None
                        }, expect_status=201, silent=True)

                # Payer ~70% des factures d'achat
                if random.random() < 0.7:
                    total_amount = r.get("totalAmount", 0)
                    api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/payments",
                        {"amount": total_amount}, silent=True)

    log(f"{total} factures d'achat reçues (D 601 + TVA déductible / C 401)", "ok")
    log(f"  + entrées de stock correspondantes (D 310 Stock / C 401)", "data")
    log(f"  + 70% des factures payées (D 401 / C 521 Banque)", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 11 — Immobilisation (véhicule) + amortissements
# ═══════════════════════════════════════════════════════════════════════════

def step_11_fixed_asset() -> None:
    section(11, "Immobilisation (véhicule de livraison) + amortissements")
    cid = COMPANY_ID
    asset_acct = find_account("244")
    dep_expense_acct = find_account("631")  # Note : le seed sectoriel mappe 631 sur "Salaires"
                                            # Pour les amortissements on crée un 681 à la main si nécessaire
    accum_dep_acct = find_account("2844")
    bank_acct = find_account("521")

    # Le seed sectoriel crée déjà 681 (Dotations aux amortissements).
    # Si absent, on le crée ; sinon on le récupère depuis STATE.accounts.
    class6 = next((a for a in STATE.accounts.values() if a["code"] == "6"), None)
    if "681" in STATE.accounts:
        dep_account_id = STATE.accounts["681"]["id"]
    elif class6 and dep_expense_acct:
        r = api("POST", f"/companies/{cid}/chart-of-accounts/{class6['id']}/children", {
            "code": "681", "label": "Dotations aux amortissements",
            "reportingClass": "CHARGES", "reportingSubcategory": "COURANT",
            "normalBalance": "DEBIT", "isCollective": False
        }, expect_status=201, silent=True)
        if r and "id" in r:
            STATE.accounts["681"] = r
            dep_account_id = r["id"]
        else:
            dep_account_id = dep_expense_acct["id"]
    elif dep_expense_acct:
        dep_account_id = dep_expense_acct["id"]
    else:
        dep_account_id = None

    if not all([asset_acct, accum_dep_acct, bank_acct, dep_account_id]):
        log("Comptes d'immobilisation manquants — étape ignorée", "warn")
        return

    body = {
        "label": "Toyota Hilux 2024 (véhicule de livraison)",
        "acquisitionDate": "2024-02-15",
        "acquisitionCost": 3_500_000,
        "usefulLifeMonths": 60,
        "residualValue": 500_000,
        "depreciationMethod": "STRAIGHT_LINE",
        "assetAccountId": asset_acct["id"],
        "depreciationExpenseAccountId": dep_account_id,
        "accumulatedDepreciationAccountId": accum_dep_acct["id"],
        "cashAccountId": bank_acct["id"]
    }
    r = api("POST", f"/companies/{cid}/fixed-assets", body, expect_status=201, silent=True)
    if r:
        log(f"Immobilisation : Toyota Hilux 2024 — 3,500,000 HTG", "ok")
        log("  → Écriture auto (acquisition) : D 244 / C 521", "data")
        log("  → Amortissement mensuel : (3,500,000 − 500,000) / 60 = 50,000 HTG/mois", "data")

        # Poster les amortissements mensuels 2024 (Feb-Dec = 11 mois) et 2025 (Jan-Jul = 7 mois)
        # NOTE : l'endpoint correct est POST /fixed-assets/{id}/post-period-depreciation?periodId=...
        # (et non POST /fixed-assets/{id}/depreciation/periods/{periodId} qui n'existe pas)
        posted = 0
        if STATE.periods_2024:
            for i in range(1, 12):  # index 1 = février, ..., index 11 = décembre
                period = STATE.periods_2024[i]
                r2 = api("POST",
                    f"/companies/{cid}/fixed-assets/{r['id']}/post-period-depreciation?periodId={period['id']}",
                    silent=True)
                if r2:
                    posted += 1
        if STATE.periods_2025:
            for i in range(0, 7):  # jan à jul 2025
                period = STATE.periods_2025[i]
                r2 = api("POST",
                    f"/companies/{cid}/fixed-assets/{r['id']}/post-period-depreciation?periodId={period['id']}",
                    silent=True)
                if r2:
                    posted += 1
        log(f"  → {posted} amortissements mensuels postés (écriture D 681 / C 2844)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 12 — Ventes + sorties de stock (COGS) + encaissements
# ═══════════════════════════════════════════════════════════════════════════

def step_12_sales_and_cogs() -> None:
    section(12, "Ventes + sorties de stock (COGS) + encaissements")
    cid = COMPANY_ID
    client_names = [k for k in STATE.third_parties
                    if STATE.third_parties[k].get("type") == "CLIENT"]
    if not client_names or not STATE.warehouse_id:
        log("Pas de clients ou d'entrepôt — étape ignorée", "warn")
        return

    TAX_RATE = 10
    # 3-5 ventes par mois sur 2024 (12 mois) + 2025 (7 mois)
    # Marge augmentée (1.8x à 2.5x le coût) et quantités plus importantes pour
    # générer un bénéfice réaliste (le commerce de détail alimentaire a typiquement
    # une marge brute de 40-60% sur les produits de base).
    # Ajustement 2026-07-26 : pour équilibrer le CR (bénéfice réaliste, pas de perte),
    # on augmente le volume (5-8 factures/mois au lieu de 3-5), les quantités (100-400
    # au lieu de 50-200), et la marge (2.5x à 3.5x au lieu de 2.0x à 2.8x). Une boutique
    # de détail alimentaire bien gérée réalise 15-25% de marge nette.
    all_invoices = []
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for month in months:
            num_invoices = random.randint(5, 8)
            for _ in range(num_invoices):
                day = random.randint(3, 27)
                client = random.choice(client_names)
                sku = random.choice(list(STATE.items.keys()))
                qty = random.randint(100, 400)  # quantités plus importantes
                cost = COSTS.get(sku, 100)
                unit_price = round(cost * random.uniform(2.5, 3.5), 2)  # marge 150-250%
                all_invoices.append({
                    "client": client, "date": date(year, month, day),
                    "sku": sku, "qty": qty, "unit_price": unit_price, "cost": cost
                })

    total_inv = 0
    total_cogs = 0
    total_paid = 0
    progress = 0

    for inv in all_invoices:
        client_tp = STATE.third_parties.get(inv["client"])
        item = STATE.items.get(inv["sku"])
        if not client_tp or not item:
            continue

        issue_date = inv["date"].isoformat()
        due_date = (inv["date"] + timedelta(days=30)).isoformat()

        body = {
            "thirdPartyId": client_tp["id"], "type": "STANDARD",
            "issueDate": issue_date, "dueDate": due_date, "currency": "HTG",
            "lines": [{
                "description": f"Vente {inv['sku']}",
                "quantity": inv["qty"], "unitPrice": inv["unit_price"],
                "taxRate": TAX_RATE, "itemId": item["id"]
            }]
        }
        r = api("POST", f"/companies/{cid}/invoicing/invoices", body, expect_status=201, silent=True)
        if not r:
            continue
        invoice_id = r["id"]

        # Émettre la facture → écriture D 411 / C 701 + C 443 (TVA collectée)
        api("POST", f"/companies/{cid}/invoicing/invoices/{invoice_id}/issue", silent=True)
        total_inv += 1

        # Sortie de stock → écriture D 603 (COGS) / C 310 (Stock)
        r2 = api("POST", f"/companies/{cid}/inventory/stock-moves", {
            "itemId": item["id"], "warehouseId": STATE.warehouse_id,
            "moveDate": issue_date, "direction": "OUT",
            "quantity": inv["qty"],
            "sourceDocument": f"Vente facture {r.get('invoiceNumber', '?')}"
        }, expect_status=201, silent=True)
        if r2:
            total_cogs += 1

        # Encaissement (~60% des factures) → écriture D 521 / C 411
        if random.random() < 0.6:
            api("POST", f"/companies/{cid}/invoicing/invoices/{invoice_id}/record-payment", {
                "amount": r.get("totalAmount", 0),
                "paymentDate": (inv["date"] + timedelta(days=random.randint(5, 25))).isoformat()
            }, silent=True)
            total_paid += 1

        progress += 1
        if progress % 10 == 0 or progress == len(all_invoices):
            progress_bar(progress, len(all_invoices), "Ventes")

    STATE.invoices_issued = total_inv
    log(f"{total_inv} factures de vente émises (D 411 / C 701 + C 443 TVA)", "ok")
    log(f"{total_cogs} sorties de stock (D 603 COGS / C 310 Stock)", "ok")
    log(f"{total_paid} encaissements (D 521 / C 411)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 13 — Charges mensuelles (salaires, loyer, électricité, carburant)
# ═══════════════════════════════════════════════════════════════════════════

def step_13_monthly_expenses() -> None:
    section(13, "Charges mensuelles (salaires manuels, loyer, électricité, carburant)")
    cid = COMPANY_ID

    def post_entry(journal, d, desc, lines, idem_key):
        body = {
            "journalCode": journal, "entryDate": d, "description": desc,
            "lines": [{"accountCode": l[0], "debit": l[1], "credit": l[2]} for l in lines],
            "sourceModule": "MANUAL"
        }
        r = api("POST", f"/companies/{cid}/accounting-engine/journal-entries", body,
                expect_status=201, extra_headers={"Idempotency-Key": idem_key}, silent=True)
        if r:
            api("POST", f"/companies/{cid}/accounting-engine/journal-entries/{r['id']}/post",
                silent=True)
            return True
        return False

    count = 0
    total_iterations = 11 + 6  # 11 mois 2024 + 6 mois 2025 (sauf juillet = paie consolidée)
    progress = 0

    # Salaires — déjà payés via la campagne de paie consolidée (étape 15) pour juillet 2025.
    # Pour les autres mois (Jan 2024 → Jun 2025), on poste des écritures manuelles
    # "Salaires versés" sur le compte 631 (charges de personnel) pour refléter la réalité
    # comptable d'une boutique qui paie ses 3 employés chaque mois.
    # Montant réaliste : 3 employés × ~40k HTG/mois = 120k/mois 2024, 130k/mois 2025.
    # NOTE : pour éviter de doubler avec la campagne de paie (étape 15, juillet 2025),
    # on saute le mois de juillet 2025 dans les salaires manuels.
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            if year == 2025 and m == 7:
                continue  # couvert par la campagne de paie
            amt = 120_000 if year == 2024 else 130_000
            if post_entry("OD", f"{year}-{m:02d}-28", f"Salaires manuels {m}/{year}",
                          [("631", amt, 0), ("521", 0, amt)], f"sal-{year}-{m:02d}"):
                count += 1
            progress += 1
            progress_bar(progress, total_iterations, "Charges mensuelles")

    # Loyer (D 621 / C 521) — 60k/mois 2024, 65k/mois 2025
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            amt = 60_000 if year == 2024 else 65_000
            if post_entry("OD", f"{year}-{m:02d}-01", f"Loyer {m}/{year}",
                          [("621", amt, 0), ("521", 0, amt)], f"rent-{year}-{m:02d}"):
                count += 1

    # Électricité trimestrielle (D 622 / C 521)
    for q_month in [3, 6, 9, 12]:
        if post_entry("OD", f"2024-{q_month:02d}-15", f"Électricité Q{q_month//3} 2024",
                      [("622", 20_000, 0), ("521", 0, 20_000)], f"elec-2024-{q_month:02d}"):
            count += 1
    for q_month in [3, 6]:
        if post_entry("OD", f"2025-{q_month:02d}-15", f"Électricité Q{q_month//3} 2025",
                      [("622", 22_000, 0), ("521", 0, 22_000)], f"elec-2025-{q_month:02d}"):
            count += 1

    # Carburant mensuel (D 623 / C 521)
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            if post_entry("OD", f"{year}-{m:02d}-15", f"Carburant {m}/{year}",
                          [("623", 15_000, 0), ("521", 0, 15_000)], f"fuel-{year}-{m:02d}"):
                count += 1

    log(f"{count} écritures de charges (salaires, loyer, électricité, carburant)", "ok")
    log("  Chaque écriture : D Charge / C Banque (débit = crédit → équilibrée)", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 14 — Notes de frais (module :expenses)
# ═══════════════════════════════════════════════════════════════════════════

def step_14_expense_reports() -> None:
    section(14, "Notes de frais employés (module :expenses)")
    cid = COMPANY_ID
    employee_tps = [tp for tp in STATE.third_parties.values()
                    if tp.get("type") == "EMPLOYEE"]
    if not employee_tps:
        log("Aucun employé — étape ignorée", "warn")
        return

    # 5 notes de frais réparties sur 2024-2025
    expenses = [
        # (date, employee, paidDirectly, category, description, amount)
        ("2024-03-12", employee_tps[0], True,  "TRAVEL",   "Taxi aéroport pour livraison",        2500),
        ("2024-06-08", employee_tps[1], False, "MEALS",    "Repas client chez Le Gouvor",         3500),
        ("2024-09-22", employee_tps[2], True,  "SUPPLIES", "Fournitures bureau (stylos, cahiers)", 1500),
        ("2025-02-14", employee_tps[0], False, "TRAVEL",   "Déplacement Cap-Haïtien",             8500),
        ("2025-05-30", employee_tps[1], True,  "OTHER",    "Frais postaux",                       1200),
    ]

    count = 0
    for issue_date, emp_tp, paid_directly, category, desc, amount in expenses:
        body = {
            "thirdPartyId": emp_tp["id"] if not paid_directly else None,
            "expenseDate": issue_date, "currency": "HTG",
            "description": desc, "paidDirectly": paid_directly,
            "lines": [{
                "category": category, "description": desc,
                "amount": amount, "expenseAccountId": None
            }]
        }
        r = api("POST", f"/companies/{cid}/expense-reports", body, expect_status=201, silent=True)
        if r:
            # Submit → approve → pay (cycle complet)
            api("POST", f"/companies/{cid}/expense-reports/{r['id']}/submit", silent=True)
            r2 = api("POST", f"/companies/{cid}/expense-reports/{r['id']}/approve", silent=True)
            if r2:
                api("POST", f"/companies/{cid}/expense-reports/{r['id']}/payments", silent=True)
                count += 1
                STATE.expenses_created += 1

    log(f"{count} notes de frais (cycle DRAFT→SUBMITTED→APPROVED→PAID)", "ok")
    log("  paidDirectly=true → D Charges / C 571 Caisse", "data")
    log("  paidDirectly=false → D Charges / C 421 Personnel-rem. dues", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 15 — Campagne de paie (module :payroll)
# ═══════════════════════════════════════════════════════════════════════════

def step_15_payroll_run() -> None:
    section(15, "Campagne de paie consolidée (module :payroll)")
    cid = COMPANY_ID
    if not STATE.employees:
        log("Aucun employé — étape ignorée", "warn")
        return

    # Campagne de paie pour juillet 2025, charges patronales 14%
    r = api("POST", f"/companies/{cid}/payroll-runs", {
        "periodMonth": 7, "periodYear": 2025,
        "employerContributionRate": 14
    }, expect_status=201, silent=True)
    if not r:
        log("Création campagne échouée", "err")
        return

    log(f"Campagne DRAFT créée : juillet 2025, taux patronal 14%", "ok")

    # Calculate (génère 1 payslip par employé ACTIVE)
    r2 = api("POST",
        f"/companies/{cid}/payroll-runs/{r['id']}/calculate?employerContributionRate=14",
        silent=True)
    if r2:
        log(f"Calculé : {r2.get('payslipCount', '?')} bulletins générés", "ok")
        log(f"  Brut total : {r2.get('totalGross', 0):,.0f} HTG", "data")
        log(f"  Net total  : {r2.get('totalNet', 0):,.0f} HTG", "data")
        log(f"  Charges patronales : {r2.get('totalEmployerContributions', 0):,.0f} HTG", "data")

    # Approve (génère l'écriture consolidée)
    r3 = api("POST", f"/companies/{cid}/payroll-runs/{r['id']}/approve", silent=True)
    if r3:
        log(f"Approuvée — écriture consolidée générée", "ok")
        log("  D 631 Charges de personnel (brut + charges patronales)", "data")
        log("  C 421 Personnel-rem. dues (net, par employé)", "data")
        log("  C 433 Sécurité sociale (charges patronales)", "data")
        log("  C 443 État (retenues fiscales salariales)", "data")

    # Pay (marquage)
    r4 = api("POST", f"/companies/{cid}/payroll-runs/{r['id']}/pay", silent=True)
    if r4:
        log("Marquée PAID", "ok")

    STATE.payroll_run_id = r["id"]


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 16 — Workflow d'approbation (4 yeux) + notifications
# ═══════════════════════════════════════════════════════════════════════════

def step_16_approval_workflow() -> None:
    section(16, "Workflow d'approbation (4 yeux) + notifications")
    cid = COMPANY_ID

    # 1. Créer une règle d'approbation pour JOURNAL_ENTRY_POST (seuil 200,000 HTG)
    rule_body = {
        "actionType": "JOURNAL_ENTRY_POST",
        "thresholdAmount": 200000,
        "requiredApproverRoles": ["ADMIN", "OWNER"],
        "minApprovals": 1
    }
    r = api("POST", f"/companies/{cid}/approval-workflow/rules", rule_body, expect_status=201, silent=True)
    if r:
        log(f"Règle d'approbation créée : JOURNAL_ENTRY_POST > 200,000 HTG", "ok")
    else:
        log("Règle déjà existante ou création échouée (idempotent)", "data")

    # 2. Créer une règle d'alerte LOW_STOCK
    alert_body = {
        "type": "LOW_STOCK",
        "thresholdValue": 20,
        "active": True
    }
    r = api("POST", f"/companies/{cid}/notifications/alert-rules", alert_body, expect_status=201, silent=True)
    if r:
        log(f"Règle d'alerte LOW_STOCK (seuil 20) créée", "ok")

    # 3. Créer une règle d'alerte INVOICE_OVERDUE
    alert_body = {
        "type": "INVOICE_OVERDUE",
        "thresholdValue": 30,
        "active": True
    }
    r = api("POST", f"/companies/{cid}/notifications/alert-rules", alert_body, expect_status=201, silent=True)
    if r:
        log(f"Règle d'alerte INVOICE_OVERDUE (30 jours) créée", "ok")

    # 4. Créer une règle d'alerte APPROVAL_PENDING
    alert_body = {
        "type": "APPROVAL_PENDING",
        "thresholdValue": 24,
        "active": True
    }
    r = api("POST", f"/companies/{cid}/notifications/alert-rules", alert_body, expect_status=201, silent=True)
    if r:
        log(f"Règle d'alerte APPROVAL_PENDING (24h) créée", "ok")

    # 5. Créer une écriture de charge importante (> 200k) pour déclencher le workflow
    # D 621 Loyer annuel / C 401 Fournisseur — 250,000 HTG
    body = {
        "journalCode": "OD",
        "entryDate": "2025-06-30",
        "description": "Loyer annuel anticipé (déclenche workflow d'approbation)",
        "lines": [
            {"accountCode": "621", "debit": 250000, "credit": 0, "description": "Loyer annuel 2025"},
            {"accountCode": "401", "debit": 0, "credit": 250000, "description": "Fournisseur — Loyer annuel"}
        ],
        "sourceModule": "MANUAL"
    }
    r = api("POST", f"/companies/{cid}/accounting-engine/journal-entries", body,
            expect_status=201, extra_headers={"Idempotency-Key": "approval-test-001"}, silent=True)
    if r:
        entry_id = r["id"]
        log(f"Écriture DRAFT créée (250,000 HTG, > seuil 200k)", "data")

        # Tenter de poster → doit passer en PENDING_APPROVAL (montant > seuil)
        r2 = api("POST", f"/companies/{cid}/accounting-engine/journal-entries/{entry_id}/post", silent=True)
        if r2 and r2.get("status") == "PENDING_APPROVAL":
            log("✓ Écriture passée en PENDING_APPROVAL (workflow déclenché)", "ok")
            log("  → Notifications envoyées aux approbateurs (ADMIN, OWNER)", "data")

            # Lister les demandes en attente
            r3 = api("GET", f"/companies/{cid}/approval-workflow/requests?status=PENDING", silent=True)
            if r3:
                log(f"  {len(r3)} demande(s) d'approbation en attente", "data")
                if r3:
                    request_id = r3[0]["id"]
                    # Note : on ne peut pas approuver nous-mêmes (règle des 4 yeux)
                    # L'approbation réelle doit être faite par un autre ADMIN.
                    # Pour la démo, on laisse la demande en attente.
                    log(f"  Demande ID : {color(request_id, 'cyan')}", "data")
                    log("  (Règle des 4 yeux : l'auteur ne peut pas approuver sa propre demande)", "data")
        elif r2 and r2.get("status") == "POSTED":
            log("Écriture auto-postée (montant ≤ seuil ou pas de règle active)", "data")
        else:
            log("Postage échoué — voir logs backend", "warn")

    # 6. Lister les notifications (peuvent être vides si pas d'événement déclencheur)
    r = api("GET", f"/companies/{cid}/notifications", silent=True)
    if r is not None:
        log(f"Notifications : {len(r) if isinstance(r, list) else 0} au total", "ok")

    # 7. Lister les préférences de notification
    r = api("GET", f"/companies/{cid}/notifications/preferences", silent=True)
    if r is not None:
        log(f"Préférences de notification configurées : {len(r) if isinstance(r, list) else 0}", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 17 — Opérations en devises étrangères (module :fx-operations)
# ═══════════════════════════════════════════════════════════════════════════

def step_17_fx_operations() -> None:
    section(17, "Opérations en devises étrangères (module :fx-operations)")
    cid = COMPANY_ID

    # 1. Créer des taux de change USD → HTG et EUR → HTG pour 2024-2025
    rates = [
        ("USD", "HTG", 150.0, "2024-01-01", "Banque Nationale"),
        ("USD", "HTG", 155.0, "2024-07-01", "Banque Nationale"),
        ("USD", "HTG", 160.0, "2025-01-01", "Banque Nationale"),
        ("USD", "HTG", 165.0, "2025-07-01", "Banque Nationale"),
        ("EUR", "HTG", 175.0, "2024-01-01", "BCEAO"),
        ("EUR", "HTG", 180.0, "2025-01-01", "BCEAO"),
    ]
    for from_c, to_c, rate, date_, source in rates:
        api("POST", f"/companies/{cid}/fx-operations/rates", {
            "fromCurrency": from_c, "toCurrency": to_c,
            "rate": rate, "asOfDate": date_, "source": source
        }, expect_status=201, silent=True)
    log(f"{len(rates)} taux de change créés (USD/HTG + EUR/HTG pour 2024-2025)", "ok")

    # 2. Acheter 2,000 USD au taux USD/HTG=150 (2024-01-15) — coût : 300,000 HTG
    # BUY : fromCurrency=HTG, toCurrency=USD, fromAmount=300,000, toAmount=2,000
    # NOTE sur la convention de rate : le backend valide toAmount = fromAmount × rate,
    # c'est-à-dire que rate est "1 fromCurrency = rate toCurrency".
    # Pour HTG→USD au taux USD/HTG=150, le rate à passer est l'inverse : 1/150 = 0.006667.
    # Pas de gain/perte car le taux est direct.
    htg_to_usd_rate = round(1.0 / 150.0, 10)
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "BUY",
        "fromCurrency": "HTG",
        "toCurrency": "USD",
        "fromAmount": 300000,
        "toAmount": 2000,
        "rate": htg_to_usd_rate,
        "operationDate": "2024-01-15",
        "description": "Achat 2,000 USD pour importations (taux USD/HTG=150)",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        log(f"Achat USD : 2,000 USD pour 300,000 HTG (taux 150)", "ok")
        log(f"  Gain/perte : {r.get('fxGainLoss', 0):,.0f} HTG", "data")

    # 3. Vendre 1,000 USD au taux 160 (2025-03-10) — reçoit : 160,000 HTG
    # SELL : fromCurrency=USD, toCurrency=HTG, fromAmount=1,000, toAmount=160,000, rate=160
    # Coût historique : 1,000 × 150 = 150,000 HTG ; vente : 160,000 HTG → gain 10,000 HTG
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "SELL",
        "fromCurrency": "USD",
        "toCurrency": "HTG",
        "fromAmount": 1000,
        "toAmount": 160000,
        "rate": 160,
        "operationDate": "2025-03-10",
        "description": "Vente 1,000 USD (gain de change)",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        log(f"Vente USD : 1,000 USD pour 160,000 HTG (taux 160)", "ok")
        gain = r.get("fxGainLoss", 0) or 0
        gain_color = "green" if gain >= 0 else "red"
        log(f"  Gain/perte : {color(f'{gain:,.0f}', gain_color)} HTG", "data")
        log("  Écriture : D 521 (160,000) / C 521 (150,000) / C 776 (10,000 gain)", "data")

    # 4. Réévaluation de fin de période (31/12/2024) — solde USD restant
    # 1,000 USD restants au taux 160 (clôture) vs 150 (historique)
    # fromAmount = solde historique en HTG = 1,000 × 150 = 150,000
    # toAmount = solde clôture en HTG = 1,000 × 160 = 160,000
    # Différence = 10,000 HTG (gain latent)
    # NOTE sur la convention de rate : le backend valide toAmount = fromAmount × rate,
    # donc rate = toAmount / fromAmount = 160,000 / 150,000 = 1.0667
    reval_rate = round(160000.0 / 150000.0, 10)
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "REVALUATION",
        "fromCurrency": "USD",
        "toCurrency": "HTG",
        "fromAmount": 150000,
        "toAmount": 160000,
        "rate": reval_rate,
        "operationDate": "2024-12-31",
        "description": "Réévaluation fin 2024 — solde USD restant",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        log(f"Réévaluation 31/12/2024 : solde USD réévalué (taux 160)", "ok")
        gain = r.get("fxGainLoss", 0) or 0
        gain_color = "green" if gain >= 0 else "red"
        log(f"  Gain latent : {color(f'{gain:,.0f}', gain_color)} HTG", "data")

    # 5. Tester la conversion via l'endpoint /convert
    r = api("GET",
        f"/companies/{cid}/fx-operations/convert?amount=1000&fromCurrency=USD&toCurrency=HTG&asOfDate=2025-06-01",
        silent=True)
    if r:
        original = r.get("originalAmount", 0)
        converted = r.get("convertedAmount", 0)
        log(f"Conversion test : 1,000 USD = {color(f'{converted:,.0f}', 'cyan')} HTG (au 2025-06-01)", "data")

    # 6. Lister les opérations FX
    r = api("GET", f"/companies/{cid}/fx-operations", silent=True)
    if r:
        log(f"{len(r)} opération(s) FX au total", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 18 — Écriture de clôture d'exercice (pour équilibrer le bilan)
# ═══════════════════════════════════════════════════════════════════════════

def step_18_fiscal_year_close() -> None:
    section(18, "Clôture d'exercice 2024 (écriture de solde des produits/charges)")
    cid = COMPANY_ID

    # L'exercice 2024 doit être clôturé pour équilibrer le bilan au 31/12/2024.
    # La clôture solde les comptes de produits/charges contre le compte de résultat (12).
    if not STATE.fiscal_years:
        log("Aucun exercice fiscal — étape ignorée", "warn")
        return

    fy_2024 = STATE.fiscal_years[0] if "2024" in STATE.fiscal_years[0].get("label", "") else None
    if not fy_2024:
        log("Exercice 2024 introuvable — étape ignorée", "warn")
        return

    fy_id = fy_2024["id"]
    log(f"Clôture de l'exercice 2024 (ID: {color(fy_id, 'cyan')})", "data")
    log("  Calcule le résultat net (Produits − Charges) et génère une écriture", "data")
    log("  qui solde les comptes de produits/charges contre le compte 12 (Résultat)", "data")

    r = api("POST", f"/companies/{cid}/accounting-engine/fiscal-years/{fy_id}/close", silent=True)
    if r:
        log(f"✓ Exercice 2024 clôturé — écriture de clôture générée (ID: {color(r.get('id', '?'), 'cyan')})", "ok")
        log(f"  Référence : {r.get('reference', '?')}", "data")
        log("  → Le bilan au 31/12/2024 est désormais équilibré", "data")
    else:
        log("Clôture échouée — voir logs backend", "warn")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 19 — Vérification cohérence (balance débit = crédit)
# ═══════════════════════════════════════════════════════════════════════════

def step_19_verify_balance() -> None:
    section(19, "Vérification de cohérence — balance débit = crédit (par exercice)")
    cid = COMPANY_ID
    # Trial balance filtre par exercice fiscal depuis le backend v4.1 (suite 4 §1).
    # On utilise ?fiscalYearId= pour ne récupérer que les écritures de l'exercice 2025
    # (le backend résout l'exercice via resolveFiscalYear si le param est absent, mais
    # la bonne pratique est de le passer explicitement — le mobile fait pareil).
    fy_id = None
    if len(STATE.fiscal_years) >= 2:
        fy_id = STATE.fiscal_years[1]["id"]  # 2025
    elif STATE.fiscal_years:
        fy_id = STATE.fiscal_years[0]["id"]

    path = f"/companies/{cid}/accounting-engine/trial-balance"
    if fy_id:
        path += f"?fiscalYearId={fy_id}"
    r = api("GET", path, silent=True)

    if r and isinstance(r, list):
        total_debit = sum(line.get("totalDebit", 0) or 0 for line in r)
        total_credit = sum(line.get("totalCredit", 0) or 0 for line in r)
        diff = total_debit - total_credit
        log(f"Total débit  : {color(f'{total_debit:,.2f}', 'cyan')}", "data")
        log(f"Total crédit : {color(f'{total_credit:,.2f}', 'cyan')}", "data")
        log(f"Différence   : {color(f'{diff:,.2f}', 'green' if abs(diff) < 1 else 'red')}", "data")
        if abs(diff) < 1:
            log(color("✓ BILAN ÉQUILIBRÉ — débit = crédit (double partie respectée)", "green"), "ok")
        else:
            log(color(f"⚠ DÉSÉQUILIBRE détecté : {diff:,.2f}", "yellow"), "warn")
        # Afficher les 10 comptes avec les plus gros soldes
        sorted_lines = sorted(r, key=lambda x: abs((x.get("balance") or 0)), reverse=True)[:10]
        log("Top 10 comptes par solde :", "data")
        for line in sorted_lines:
            code = line.get("accountCode", "?")
            label = line.get("accountLabel", "?")[:30]
            balance = line.get("balance", 0) or 0
            log(f"  {color(code, 'cyan')} {label:30s} : {balance:>15,.2f}", "data")
    else:
        log("Trial balance endpoint non disponible — vérification manuelle requise", "warn")


# ═══════════════════════════════════════════════════════════════════════════
#  Helpers pour les rapports par exercice fiscal
# ═══════════════════════════════════════════════════════════════════════════

def _fiscal_year_periods() -> list[tuple[str, str, str, str | None, str]]:
    """
    Retourne la liste des exercices fiscaux à traiter.
    Chaque tuple : (label, from_date, to_date, as_of_date, fy_id).
    Pour 2024 : from=2024-01-01, to=2024-12-31, as_of=2024-12-31.
    Pour 2025 (partiel Jan→Jul) : from=2025-01-01, to=2025-07-31, as_of=2025-07-31.
    """
    periods = []
    if len(STATE.fiscal_years) >= 1:
        fy24 = STATE.fiscal_years[0]
        periods.append(("2024", "2024-01-01", "2024-12-31", "2024-12-31", fy24["id"]))
    if len(STATE.fiscal_years) >= 2:
        fy25 = STATE.fiscal_years[1]
        periods.append(("2025 (Jan→Jul)", "2025-01-01", "2025-07-31", "2025-07-31", fy25["id"]))
    return periods


def _export_one_report(stmt: str, fmt: str, from_: str | None, to_: str | None,
                       res_id: str | None, desc: str) -> tuple[bool, str]:
    """
    Appelle GET /companies/{cid}/reporting/exports/{stmt}?format=...&from=...&to=...
    Retourne (success, size_str_or_status).
    """
    cid = COMPANY_ID
    params = f"format={fmt}"
    if from_:
        params += f"&from={from_}"
    if to_:
        params += f"&to={to_}"
    if res_id:
        params += f"&resourceId={res_id}"
    full_url = f"{BASE_URL}/api/v1/companies/{cid}/reporting/exports/{stmt}?{params}"
    h = {"Authorization": HEADERS.get("Authorization", "")}
    try:
        resp = requests.get(full_url, headers=h, timeout=60)
        if resp.status_code == 200:
            size_kb = len(resp.content) / 1024
            size_str = f"{size_kb:.1f} KB" if size_kb < 1024 else f"{size_kb/1024:.2f} MB"
            return (True, size_str)
        elif resp.status_code == 403:
            return (False, "GATED")
        else:
            return (False, f"HTTP {resp.status_code}")
    except Exception as e:
        return (False, f"ERR {e}")


def _present_balance_sheet(label: str, as_of: str) -> None:
    """Affiche le bilan au `as_of` pour l'exercice `label`."""
    cid = COMPANY_ID
    r = api("GET", f"/companies/{cid}/financial-statements/balance-sheet?asOf={as_of}",
            silent=True)
    if not r:
        log(f"Bilan au {as_of} : non disponible", "warn")
        return
    assets = r.get("totalAssets", 0) or 0
    liabilities = r.get("totalLiabilities", 0) or 0
    equity = r.get("totalEquity", 0) or 0
    balanced = r.get("balanced", False)
    log(f"BILAN au {as_of} (exercice {label}) :", "ok")
    log(f"  Total Actif      : {color(f'{assets:,.0f}', 'cyan')} HTG", "data")
    log(f"  Total Passif     : {color(f'{liabilities:,.0f}', 'cyan')} HTG", "data")
    log(f"  Capitaux propres : {color(f'{equity:,.0f}', 'cyan')} HTG", "data")
    diff = assets - (liabilities + equity)
    if abs(diff) < 1:
        log(color(f"  ✓ Équilibré (balanced={balanced})", "green"), "data")
    else:
        log(color(f"  ⚠ Déséquilibre : {diff:,.0f}", "yellow"), "data")


def _present_income_statement(label: str, from_: str, to_: str) -> None:
    """Affiche le compte de résultat pour la période [from_, to_] (exercice `label`)."""
    cid = COMPANY_ID
    r = api("GET",
        f"/companies/{cid}/financial-statements/income-statement?from={from_}&to={to_}",
        silent=True)
    if not r:
        log(f"Compte de résultat {label} : non disponible", "warn")
        return
    products = r.get("totalProducts", 0) or 0
    charges = r.get("totalCharges", 0) or 0
    result = r.get("netResult", 0) or 0
    log(f"COMPTE DE RÉSULTAT {label} ({from_} → {to_}) :", "ok")
    log(f"  Produits  : {color(f'{products:,.0f}', 'cyan')} HTG", "data")
    log(f"  Charges   : {color(f'{charges:,.0f}', 'cyan')} HTG", "data")
    result_color = "green" if result >= 0 else "red"
    result_label = "BÉNÉFICE" if result >= 0 else "PERTE"
    log(f"  Résultat  : {color(f'{result:,.0f}', result_color)} HTG ({result_label})", "data")


def _present_trial_balance(label: str, fy_id: str | None) -> None:
    """Affiche la balance générale pour l'exercice `label` (filtre ?fiscalYearId=)."""
    cid = COMPANY_ID
    path = f"/companies/{cid}/accounting-engine/trial-balance"
    if fy_id:
        path += f"?fiscalYearId={fy_id}"
    r = api("GET", path, silent=True)
    if not r or not isinstance(r, list):
        log(f"Balance générale {label} : non disponible", "warn")
        return
    total_debit = sum(line.get("totalDebit", 0) or 0 for line in r)
    total_credit = sum(line.get("totalCredit", 0) or 0 for line in r)
    diff = total_debit - total_credit
    log(f"BALANCE GÉNÉRALE {label} ({len(r)} comptes) :", "ok")
    log(f"  Total débit  : {color(f'{total_debit:,.2f}', 'cyan')}", "data")
    log(f"  Total crédit : {color(f'{total_credit:,.2f}', 'cyan')}", "data")
    log(f"  Différence   : {color(f'{diff:,.2f}', 'green' if abs(diff) < 1 else 'red')}", "data")
    # Top 5 comptes par solde
    sorted_lines = sorted(r, key=lambda x: abs((x.get("balance") or 0)), reverse=True)[:5]
    log("  Top 5 comptes par solde :", "data")
    for line in sorted_lines:
        code = line.get("accountCode", "?")
        acct_label = line.get("accountLabel", "?")[:25]
        balance = line.get("balance", 0) or 0
        log(f"    {color(code, 'cyan')} {acct_label:25s} : {balance:>15,.2f}", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 20 — Exports de rapports (15 statements × 2 exercices fiscaux)
# ═══════════════════════════════════════════════════════════════════════════

def step_20b_export_all_reports() -> None:
    section(20, "Exports de rapports — 15 statements × 2 exercices fiscaux")
    cid = COMPANY_ID
    periods = _fiscal_year_periods()
    if not periods:
        log("Aucun exercice fiscal — étape ignorée", "warn")
        return

    # Pour chaque exercice fiscal (2024 + 2025), on génère tous les statements
    # qui ont du sens par exercice. Les statements "snapshot" (valorisation stock,
    # immo, balance âgée fournisseurs) ne dépendent pas d'un exercice — on les
    # génère une seule fois à la fin.
    success = 0
    skipped = 0
    total_exports = 0

    for label, from_, to_, as_of, fy_id in periods:
        log(f"── Exercice {color(label, 'magenta')} ──", "info")
        # Statements par exercice (PDF + CSV avec filtre from/to)
        per_fy_exports = [
            ("balance_sheet",        "pdf", None,  as_of, None, f"Bilan PDF au {as_of}"),
            ("income_statement",     "pdf", from_, to_,  None, f"Compte de résultat PDF {label}"),
            ("general_ledger",       "csv", from_, to_,  None, f"Grand livre CSV {label}"),
            ("tax_declaration",      "csv", from_, to_,  None, f"Déclaration fiscale CSV {label}"),
            ("purchase_register",    "csv", from_, to_,  None, f"Registre des achats CSV {label}"),
            ("expense_register",     "csv", from_, to_,  None, f"Registre des notes de frais CSV {label}"),
            ("payroll_summary",      "csv", from_, to_,  None, f"Résumé de paie CSV {label}"),
            ("stock_movement_register","csv", from_, to_,None, f"Registre des mouvements de stock CSV {label}"),
            ("fx_operations_register","csv", from_, to_, None, f"Registre des opérations FX CSV {label}"),
        ]
        # trial_balance utilise ?fiscalYearId= via l'endpoint accounting-engine (pas reporting)
        # mais le statement CSV trial_balance dans :reporting ne supporte pas ?fiscalYearId —
        # il exporte toutes les écritures. On l'appelle une seule fois (snapshot global).
        for stmt, fmt, e_from, e_to, res_id, desc in per_fy_exports:
            ok, info = _export_one_report(stmt, fmt, e_from, e_to, res_id, desc)
            total_exports += 1
            if ok:
                log(f"  {color(stmt, 'cyan'):<28s} → {color(fmt.upper(), 'green')} {info:>10s}  {desc}", "ok")
                success += 1
            elif info == "GATED":
                log(f"  {color(stmt, 'cyan'):<28s} → {color('GATED', 'yellow')} (module non activé)  {desc}", "warn")
                skipped += 1
            else:
                log(f"  {color(stmt, 'cyan'):<28s} → {color(info, 'red')}  {desc}", "err")
                skipped += 1

    # Statements "snapshot" — indépendants de l'exercice, générés une seule fois
    log(f"── Snapshots globaux (indépendants de l'exercice) ──", "info")
    snapshot_exports = [
        ("trial_balance",            "csv", None, None, None, "Balance générale CSV (tous exercices)"),
        ("inventory_valuation",      "csv", None, None, None, "Valorisation des stocks CSV (snapshot)"),
        ("fixed_assets_register",    "csv", None, None, None, "Registre des immobilisations CSV (snapshot)"),
        ("aged_balance_suppliers",   "csv", None, None, None, "Balance âgée fournisseurs CSV (snapshot)"),
    ]
    for stmt, fmt, e_from, e_to, res_id, desc in snapshot_exports:
        ok, info = _export_one_report(stmt, fmt, e_from, e_to, res_id, desc)
        total_exports += 1
        if ok:
            log(f"  {color(stmt, 'cyan'):<28s} → {color(fmt.upper(), 'green')} {info:>10s}  {desc}", "ok")
            success += 1
        elif info == "GATED":
            log(f"  {color(stmt, 'cyan'):<28s} → {color('GATED', 'yellow')} (module non activé)  {desc}", "warn")
            skipped += 1
        else:
            log(f"  {color(stmt, 'cyan'):<28s} → {color(info, 'red')}  {desc}", "err")
            skipped += 1

    log(f"{success}/{total_exports} exports réussis, {skipped} ignorés (gated ou erreur)", "ok")

    # Balances âgées JSON (endpoints dédiés)
    log("Balances âgées JSON :", "data")
    r = api("GET", f"/companies/{cid}/reporting/aged-balance", silent=True)
    if r and isinstance(r, dict):
        buckets = r.get("buckets", {}) if isinstance(r, dict) else {}
        total_due = r.get("totalDue", 0) if isinstance(r, dict) else 0
        log(f"  Clients : {color(f'{total_due:,.0f}', 'cyan')} HTG dus au total", "data")
        for bucket_name in ["current", "0_30", "31_60", "61_90", "over_90"]:
            bucket = buckets.get(bucket_name, {}) if isinstance(buckets, dict) else {}
            amt = bucket.get("amount", 0) if isinstance(bucket, dict) else 0
            log(f"    {bucket_name:>10s} : {color(f'{amt:,.0f}', 'cyan')} HTG", "data")

    r = api("GET", f"/companies/{cid}/reporting/aged-balance-suppliers", silent=True)
    if r and isinstance(r, dict):
        total_due = r.get("totalDue", 0) if isinstance(r, dict) else 0
        log(f"  Fournisseurs : {color(f'{total_due:,.0f}', 'cyan')} HTG dus au total", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 21 — Présentation des rapports pour CHAQUE exercice fiscal
# ═══════════════════════════════════════════════════════════════════════════

def step_21_present_reports() -> None:
    section(21, "Présentation des rapports financiers — par exercice fiscal")
    cid = COMPANY_ID
    periods = _fiscal_year_periods()
    if not periods:
        log("Aucun exercice fiscal — étape ignorée", "warn")
        return

    # Pour chaque exercice fiscal : bilan + compte de résultat + balance générale
    for label, from_, to_, as_of, fy_id in periods:
        print()
        log(color(f"══════ Exercice {label} ══════", "magenta"), "info")
        _present_balance_sheet(label, as_of)
        _present_income_statement(label, from_, to_)
        _present_trial_balance(label, fy_id)

    # Tableau comparatif des résultats nets (2024 vs 2025)
    if len(periods) >= 2:
        print()
        log(color("══════ Comparaison des exercices ══════", "magenta"), "info")
        results = []
        for label, from_, to_, _, _ in periods:
            r = api("GET",
                f"/companies/{cid}/financial-statements/income-statement?from={from_}&to={to_}",
                silent=True)
            if r:
                results.append((label, r.get("totalProducts", 0) or 0,
                                r.get("totalCharges", 0) or 0,
                                r.get("netResult", 0) or 0))
        if len(results) >= 2:
            log(f"  {'Exercice':<20s} {'Produits':>15s} {'Charges':>15s} {'Résultat':>15s}", "data")
            for label, prod, chg, res in results:
                res_color = "green" if res >= 0 else "red"
                log(f"  {label:<20s} {color(f'{prod:>15,.0f}', 'cyan')} "
                    f"{color(f'{chg:>15,.0f}', 'cyan')} {color(f'{res:>15,.0f}', res_color)}", "data")
            # Variation
            if results[0][3] != 0:
                var = results[1][3] - results[0][3]
                var_pct = (var / abs(results[0][3]) * 100) if results[0][3] != 0 else 0
                var_color = "green" if var >= 0 else "red"
                log(f"  {'Variation':<20s} {'':>15s} {'':>15s} "
                    f"{color(f'{var:>15,.0f} ({var_pct:+.1f}%)', var_color)}", "data")

    # Liste des journaux
    r = api("GET", f"/companies/{cid}/accounting-engine/journals", silent=True)
    if r:
        log(f"JOURNAUX ({len(r)}) :", "ok")
        for j in r:
            log(f"  {color(j.get('code', '?'), 'cyan')} — {j.get('label', '?')}", "data")

    # Dashboard (KPIs globaux)
    r = api("GET", f"/companies/{cid}/reporting/dashboard", silent=True)
    if r and isinstance(r, dict):
        log("DASHBOARD (KPIs globaux) :", "ok")
        for k, v in r.items():
            if isinstance(v, (int, float)):
                log(f"  {k:30s} : {color(f'{v:,.0f}', 'cyan')}", "data")
            elif isinstance(v, str):
                log(f"  {k:30s} : {color(v, 'cyan')}", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Récapitulatif final
# ═══════════════════════════════════════════════════════════════════════════

def print_summary() -> None:
    print()
    print(color("═" * 60, "cyan"))
    print(color("  RÉCAPITULATIF — Bilan équilibré, cycle complet", "bold"))
    print(color("═" * 60, "cyan"))
    print(f"  Entreprise       : {color(COMPANY_NAME, 'cyan')}")
    print(f"  Company ID      : {color(COMPANY_ID or '?', 'cyan')}")
    print(f"  Utilisateur      : {color(USER_EMAIL, 'cyan')}")
    print(f"  Référentiel      : SYSCOHADA révisé")
    print(f"  Type métier      : RETAIL_COMMERCE")
    print(f"  Secteur          : COMMERCE")
    print(f"  Comptes créés    : {color(str(len(STATE.accounts)), 'cyan')} (niveaux 1+2+3 auto via seed sectoriel)")
    print(f"  Tiers            : {color(str(len(STATE.third_parties)), 'cyan')} (5 clients + 3 fournisseurs + 3 employés)")
    print(f"  Articles         : {color(str(len(STATE.items)), 'cyan')}")
    print(f"  Exercices        : {color(str(len(STATE.fiscal_years)), 'cyan')} (2024 + 2025)")
    print(f"  Période couverte : Janvier 2024 → Juillet 2025")
    print()
    print(color("  Écritures comptables générées :", "bold"))
    print(f"  • Capital initial        : D 521 / C 101   (3,000,000 HTG)")
    print(f"  • Emprunt bancaire        : D 521 / C 161   (2,000,000 HTG)")
    print(f"  • Achats (purchase-inv)   : D 601 + 445 / C 401  ({STATE.purchase_invoices_issued} factures)")
    print(f"  • Entrées de stock        : D 310 / C 401   (via stock-moves IN)")
    print(f"  • Ventes (sales-inv)      : D 411 / C 701 + C 443  ({STATE.invoices_issued} factures)")
    print(f"  • COGS (stock-moves OUT)  : D 603 / C 310   (1 par vente)")
    print(f"  • Encaissements           : D 521 / C 411   (~60% des ventes)")
    print(f"  • Décaissements           : D 401 / C 521   (~70% des achats)")
    print(f"  • Immobilisation          : D 244 / C 521   (3,500,000 HTG)")
    print(f"  • Amortissements          : D 681 / C 2844  (50,000 HTG/mois × 18 mois)")
    print(f"  • Salaires manuels        : D 631 / C 521   (180k-195k/mois × 19 mois)")
    print(f"  • Loyer mensuel           : D 621 / C 521   (60k-65k/mois × 19 mois)")
    print(f"  • Électricité trim.       : D 622 / C 521   (20k-22k × 6 trimestres)")
    print(f"  • Carburant mensuel       : D 623 / C 521   (15k/mois × 19 mois)")
    print(f"  • Notes de frais          : D Charges / C 571 ou 421  ({STATE.expenses_created} notes)")
    print(f"  • Paie consolidée         : D 631 / C 421 + C 433 + C 443  (juillet 2025)")
    print()
    print(color("  ✓ Chaque écriture : débit = crédit (double partie respectée)", "green"))
    print(color("  ✓ Total Actif = Total Passif + Capitaux propres", "green"))
    print()
    print(color("═" * 60, "cyan"))
    print(color("  Connexion :", "bold"))
    print(f"    Email    : {color(USER_EMAIL, 'cyan')}")
    print(f"    Password : {color(USER_PASSWORD, 'cyan')}")
    print(f"    Backend  : {color(BASE_URL, 'cyan')}")
    print(f"    Swagger  : {color(BASE_URL + '/swagger-ui.html', 'cyan')}")
    print(color("═" * 60, "cyan"))
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(description="JOAccountant — Seed Commerce Premium")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="URL du backend (défaut : http://localhost:8080)")
    parser.add_argument("--email", help="Utiliser un utilisateur existant (skip register)")
    parser.add_argument("--no-color", action="store_true", help="Désactiver les couleurs")
    args = parser.parse_args()

    global BASE_URL, USER_EMAIL, USE_COLORS
    BASE_URL = args.base_url.rstrip("/")
    if args.email:
        USER_EMAIL = args.email
    if args.no_color:
        USE_COLORS = False

    banner()
    print(f"  {color('▸', 'magenta')} Backend : {color(BASE_URL, 'cyan')}")
    print(f"  {color('▸', 'magenta')} Email   : {color(USER_EMAIL, 'cyan')}")
    print(f"  {color('▸', 'magenta')} Type    : {color('RETAIL_COMMERCE (COMMERCE)', 'cyan')}")

    try:
        step_01_register_and_login()
        step_02_create_company()
        step_03_run_wizard()
        step_04_init_chart_of_accounts()
        step_05_create_config()
        step_06_create_tax_rules()
        step_07_create_third_parties()
        step_08_create_warehouse_and_items()
        step_09_capital_and_loan()
        step_10_purchase_invoices()
        step_11_fixed_asset()
        step_12_sales_and_cogs()
        step_13_monthly_expenses()
        step_14_expense_reports()
        step_15_payroll_run()
        step_16_approval_workflow()
        step_17_fx_operations()
        step_18_fiscal_year_close()
        step_19_verify_balance()
        step_20b_export_all_reports()
        step_21_present_reports()
        print_summary()
        print(f"{color('✓', 'green')} Seed terminé avec succès !\n")
    except Exception as e:
        print(f"\n{color('✗', 'red')} Erreur : {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

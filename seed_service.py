#!/usr/bin/env python3
"""
JOAccountant v2.2 — Script de seed « Premium » pour entreprise de services
(PROFESSIONAL_SERVICES).

Scénario réaliste et complet : 2 exercices fiscaux (2024 + 2025) avec cycle d'exploitation
end-to-end d'un cabinet de conseil — capitalisation, projets, feuilles de temps, facturation
au temps passé (time-and-materials), notes de frais (déplacements consultants), paie des
consultants, opérations de change pour clients internationaux.

Différences clés vs seed_commerce.py (RETAIL_COMMERCE) :
  • Type métier : PROFESSIONAL_SERVICES (au lieu de RETAIL_COMMERCE)
  • Modules activés : TIME_BILLING (au lieu de INVENTORY) — pas de gestion de stock
  • Pas d'inventaire, pas de COGS, pas d'entrepôts
  • Revenu : facturation au temps passé (time-and-materials) via :time-billing
  • Compte de produit : 706 (Prestations de services) au lieu de 701 (Ventes)
  • Salaires : 80k-180k (consultants spécialisés)
  • Immobilisations : ordinateurs + mobilier bureau (pas de véhicules de livraison)
  • FX : un seul client international (paiements USD) — pas d'imports réguliers
  • Marges : très élevées (300-500% sur le coût salarial)

Flux couvert (modules activés pour PROFESSIONAL_SERVICES) :
  1. Authentification (register + login)
  2. Création entreprise (wizard step 1)
  3. Wizard étapes 2-9 + complete
  4. Plan comptable SYSCOHADA + seed sectoriel PROFESSIONAL_SERVICES
  5. Journaux + exercices 2024+2025 + séquences documentaires
  6. Règle de TVA 10% + retenue salariale 10%
  7. Tiers : 6 clients (5 locaux + 1 international) + 2 fournisseurs + 4 consultants
  8. Aucun article, aucun entrepôt (cabinet de services)
  9. Capital initial (2M HTG) + emprunt (1M HTG)
 10. Achats (matériel informatique, fournitures bureau, logiciels)
 11. Immobilisations (ordinateurs, mobilier, serveur)
 12. Projets + taux horaires (TIME_AND_MATERIALS et FIXED_PRICE)
 13. Feuilles de temps (consultants saisissent leurs heures)
 14. Approbation des entrées de temps
 15. Facturation au temps passé (D 411 / C 706 + C 443 TVA)
 16. Encaissements (~80% des factures)
 17. Charges mensuelles (salaires manuels, loyer bureau, internet, abonnements)
 18. Notes de frais (déplacements consultants, repas clients)
 19. Campagne de paie consolidée
 20. Workflow d'approbation (seuil 200k HTG)
 21. Opérations FX (1 client international — paiement USD)
 22. Clôture d'exercice 2024
 23. Vérification cohérence (?fiscalYearId=)
 24. Exports PDF/CSV des statements :reporting v4.1 POUR CHAQUE exercice fiscal (2024 ET 2025)
     — bilan, CR, grand livre, tax_declaration, purchase_register, expense_register,
     payroll_summary, fx_operations_register + snapshots globaux (trial_balance,
     fixed_assets_register, aged_balance_suppliers) + balances âgées JSON
     (note : inventory_valuation et stock_movement_register gated — INVENTORY non activé)
 25. Présentation des rapports financiers POUR CHAQUE exercice (bilan + CR + balance
     générale avec ?fiscalYearId=) + tableau comparatif 2024 vs 2025 (variation)

Usage :
  python3 seed_service.py --base-url http://localhost:8080
  python3 seed_service.py --email existing@user.ht

Prérequis :
  pip install requests
  Backend lancé : ./gradlew :app:devRun
"""

from __future__ import annotations

import argparse
import base64
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
USER_ID: str | None = None  # UUID de l'utilisateur authentifié (sub du JWT)
COMPANY_ID: str | None = None
HEADERS: dict[str, str] = {}

FRAMEWORK_SYSCOHADA = "00000000-0000-0000-0000-000000000003"

USER_EMAIL = f"service_{int(time.time())}@joaccountant.ht"
USER_PASSWORD = "Service#2026"
COMPANY_NAME = "Cabinet Conseil Stratégie & Tech SA"

SLOW = 0.02
USE_COLORS = sys.stdout.isatty()


@dataclass
class State:
    accounts: dict[str, dict] = field(default_factory=dict)
    third_parties: dict[str, dict] = field(default_factory=dict)
    employees: dict[str, dict] = field(default_factory=dict)
    projects: dict[str, dict] = field(default_factory=dict)  # code -> project
    billable_rates: list[dict] = field(default_factory=list)
    timesheet_entries: list[dict] = field(default_factory=list)
    fiscal_years: list[dict] = field(default_factory=list)
    periods_2024: list[dict] = field(default_factory=list)
    periods_2025: list[dict] = field(default_factory=list)
    invoices_issued: int = 0
    purchase_invoices_issued: int = 0
    expenses_created: int = 0
    payroll_run_id: str | None = None


STATE = State()

# Taux horaires des consultants (HTG/h)
HOURLY_RATES = {
    "Senior Consultant":     4500,
    "Consultant":            3000,
    "Junior Consultant":     1800,
    "Project Manager":       5500,
    "Technical Lead":        6000,
}

# Taux USD/HTG (un seul client international — moins d'opérations FX que le wholesale)
USD_HTG_RATE_2024 = 155.0
USD_HTG_RATE_2025 = 162.0


# ═══════════════════════════════════════════════════════════════════════════
#  Utilitaires d'affichage
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
    title = "JOAccountant v2.2 — Seed Service (Cabinet Conseil)"
    subtitle = "Time & Materials • WIP • Facturation au temps passé"
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


def decode_jwt_sub(token: str) -> str | None:
    """Décode le claim 'sub' (UUID utilisateur) d'un JWT HS256 sans vérifier la signature."""
    try:
        parts = token.split(".")
        if len(parts) < 2:
            return None
        payload_b64 = parts[1]
        # Ajouter padding si nécessaire
        padding = "=" * (-len(payload_b64) % 4)
        payload_bytes = base64.urlsafe_b64decode(payload_b64 + padding)
        payload = json.loads(payload_bytes)
        return payload.get("sub")
    except Exception:
        return None


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
#  Étape 1 — Inscription, connexion, récupération du user UUID
# ═══════════════════════════════════════════════════════════════════════════

def step_01_register_and_login() -> None:
    section(1, "Inscription et connexion (récupération du user UUID)")
    spinner(0.4, "Création du compte utilisateur")
    api("POST", "/auth/register", {
        "email": USER_EMAIL, "password": USER_PASSWORD,
        "fullName": "Jean Service", "locale": "fr"
    }, expect_status=201, silent=True)
    log(f"Utilisateur : {USER_EMAIL}", "ok")

    r = api("POST", "/auth/login", {"email": USER_EMAIL, "password": USER_PASSWORD})
    global TOKEN, USER_ID
    TOKEN = r["accessToken"]
    HEADERS["Authorization"] = f"Bearer {TOKEN}"
    # Décoder le sub du JWT — nécessaire pour les timesheet-entries (resourceUserId)
    USER_ID = decode_jwt_sub(TOKEN)
    if USER_ID:
        log(f"Token JWT obtenu — User UUID : {color(USER_ID, 'cyan')}", "ok")
    else:
        log("Token JWT obtenu (sub non décodé — timesheet va échouer)", "warn")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 2 — Création entreprise
# ═══════════════════════════════════════════════════════════════════════════

def step_02_create_company() -> None:
    section(2, "Création entreprise (wizard step 1 — identité)")
    r = api("POST", "/companies", {
        "name": COMPANY_NAME, "country": "HT", "functionalCurrency": "HTG"
    }, expect_status=201)
    global COMPANY_ID
    COMPANY_ID = r["id"]
    log(f"{COMPANY_NAME} — ID: {color(COMPANY_ID, 'cyan')}", "ok")

    time.sleep(0.3)
    r = api("POST", "/auth/login", {"email": USER_EMAIL, "password": USER_PASSWORD})
    global TOKEN
    TOKEN = r["accessToken"]
    HEADERS["Authorization"] = f"Bearer {TOKEN}"
    log("JWT rafraîchi (avec claim companyId)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 3 — Wizard 2-9 + complete (PROFESSIONAL_SERVICES)
# ═══════════════════════════════════════════════════════════════════════════

def step_03_run_wizard() -> None:
    section(3, "Wizard (étapes 2-9 + complete — PROFESSIONAL_SERVICES)")
    cid = COMPANY_ID
    spinner(0.5, "Saisie des 9 étapes du wizard")

    api("PATCH", f"/companies/{cid}/wizard/2",
        {"organizationNature": "FOR_PROFIT", "legalForm": "SA"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/3", {"sector": "SERVICE"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/4", {"businessTypeCode": "PROFESSIONAL_SERVICES"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/5",
        {"primaryActivityLabel": "Conseil en stratégie et transformation digitale"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/6",
        {"accountingFrameworkId": FRAMEWORK_SYSCOHADA, "fiscalYearStartMonth": 1}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/7", {}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/8", {}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/9", {}, silent=True)
    api("POST", f"/companies/{cid}/wizard/complete", silent=True)

    log("Wizard complété — PROFESSIONAL_SERVICES", "ok")
    log("Modules activés :", "data")
    log("  • Always-on (15) : CHART_OF_ACCOUNTS, ACCOUNTING_ENGINE, THIRD_PARTIES,", "data")
    log("    INVOICING, DOCUMENT_NUMBERING, APPROVAL_WORKFLOW, DOCUMENT_GENERATION,", "data")
    log("    NOTIFICATIONS, AUDIT_TRAIL, FINANCIAL_STATEMENTS, ANALYTICS, REPORTING,", "data")
    log("    EMPLOYEES, EXPENSES, PAYROLL", "data")
    log("  • Sectoriels (5) : TIME_BILLING, FIXED_ASSETS, BANK_RECONCILIATION, TAX, PURCHASING", "data")
    log("  • NON activés : INVENTORY (pas de stock), FX_OPERATIONS (pas d'imports)", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 4 — Plan comptable + seed sectoriel PROFESSIONAL_SERVICES
# ═══════════════════════════════════════════════════════════════════════════

def step_04_init_chart_of_accounts() -> None:
    section(4, "Plan comptable SYSCOHADA + seed sectoriel PROFESSIONAL_SERVICES")
    cid = COMPANY_ID

    r = api("POST", f"/companies/{cid}/chart-of-accounts/initialize",
            {"accountingFrameworkId": FRAMEWORK_SYSCOHADA,
             "businessTypeCode": "PROFESSIONAL_SERVICES"})
    if r:
        log(f"Plan initialisé : {color(str(r.get('accountsCreated', '?')), 'cyan')} comptes créés", "ok")

    accounts = api("GET", f"/companies/{cid}/chart-of-accounts")
    for acc in accounts:
        STATE.accounts[acc["code"]] = acc

    log(f"Total : {color(str(len(STATE.accounts)), 'cyan')} comptes chargés en mémoire", "ok")

    # Vérifier la présence du compte 706 (Prestations de services)
    # Pour un cabinet de services, le revenu principal est 706, pas 701
    key_accounts = ["101", "161", "401", "411", "421", "433", "443", "445",
                    "521", "571", "631", "621", "706", "701", "244", "2844", "681", "776"]
    found = [c for c in key_accounts if c in STATE.accounts]
    log(f"Comptes clés présents : {color(', '.join(found[:10]), 'cyan')}", "data")
    if "706" in STATE.accounts:
        log(f"  ✓ Compte 706 (Prestations de services) — produit principal cabinet", "ok")
    else:
        log("  ⚠ Compte 706 absent — création manuelle peut être nécessaire", "warn")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 5 — Journaux + exercices + séquences
# ═══════════════════════════════════════════════════════════════════════════

def step_05_create_config() -> None:
    section(5, "Journaux, exercices fiscaux, séquences documentaires")
    cid = COMPANY_ID

    journals = [
        ("VT", "Journal des ventes (prestations de services)"),
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

    for start, end, label in [("2024-01-01", "2024-12-31", "Exercice 2024"),
                              ("2025-01-01", "2025-12-31", "Exercice 2025")]:
        r = api("POST", f"/companies/{cid}/accounting-engine/fiscal-years",
                {"startDate": start, "endDate": end, "label": label}, silent=True)
        if r and "id" in r:
            STATE.fiscal_years.append(r)

    if not STATE.fiscal_years:
        existing = api("GET", f"/companies/{cid}/accounting-engine/fiscal-years", silent=True)
        if existing and isinstance(existing, list):
            STATE.fiscal_years = existing
    log(f"{len(STATE.fiscal_years)} exercices fiscaux (2024, 2025)", "ok")

    for fy in STATE.fiscal_years:
        r = api("GET", f"/companies/{cid}/accounting-engine/fiscal-years/{fy['id']}/periods",
                silent=True)
        if r:
            if "2024" in fy.get("label", ""):
                STATE.periods_2024 = r
            else:
                STATE.periods_2025 = r

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
    ]
    for dt, sk, pf, iy, pd, rp in seqs:
        api("POST", f"/companies/{cid}/document-numbering/sequences",
            {"documentType": dt, "scopeKey": sk, "prefix": pf, "includeYear": iy,
             "padding": pd, "resetPolicy": rp}, silent=True)
    log(f"{len(seqs)} séquences documentaires", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 6 — Règles fiscales
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

    api("POST", f"/companies/{cid}/tax/withholding-rules", {
        "code": "IMPOT-SAL-10", "label": "Impôt sur salaire 10%",
        "rate": 10,
        "applicableThirdPartyTypes": ["EMPLOYEE"]
    }, expect_status=201, silent=True)
    log("Retenue salariale 10% (IMPOT-SAL-10)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 7 — Tiers (clients + fournisseurs + consultants-employés)
# ═══════════════════════════════════════════════════════════════════════════

def step_07_create_third_parties() -> None:
    section(7, "Tiers (6 clients + 2 fournisseurs + 4 consultants)")
    cid = COMPANY_ID
    clients_acct = find_account("411")
    suppliers_acct = find_account("401")
    employees_acct = find_account("421")

    clients = [
        # Locaux (HTG)
        ("Banque Nationale",              "achats@bn.ht"),
        ("Digicel Haiti",                 "procurement@digicel.ht"),
        ("Sogebank",                      "projets@sogebank.ht"),
        ("Ministère du Commerce",         "dntc@commerce.gouv.ht"),
        ("BRH — Banque République",       "si@brh.ht"),
        # International (USD)
        ("World Bank Haiti Office",       "procurement@worldbank.org"),
    ]
    suppliers = [
        ("Distributeur FoodCo HT",   "foodco@dist.ht"),
        ("Tech supplier Miami",      "sales@techmiami.us"),
    ]
    employees_data = [
        ("Marc Pierre-Louis",  "marc@cabinet.ht",   "EMP-001", "Senior Consultant",    "Consulting", 180000),
        ("Nadège Joseph",      "nadege@cabinet.ht", "EMP-002", "Project Manager",     "Consulting", 150000),
        ("Patrick Moïse",      "patrick@cabinet.ht","EMP-003", "Technical Lead",      "Tech",       160000),
        ("Carline Étienne",    "carline@cabinet.ht","EMP-004", "Consultant",          "Consulting", 100000),
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

    for name, email, emp_num, position, dept, salary in employees_data:
        r = api("POST", f"/companies/{cid}/third-parties", {
            "type": "EMPLOYEE", "name": name,
            "collectiveAccountId": employees_acct["id"] if employees_acct else None,
            "email": email
        }, expect_status=201, silent=True)
        if r:
            STATE.third_parties[name] = r
            api("POST", f"/companies/{cid}/employees", {
                "thirdPartyId": r["id"],
                "employeeNumber": emp_num,
                "position": position,
                "department": dept,
                "hireDate": "2022-01-15",
                "baseSalary": salary,
                "salaryCurrency": "HTG",
                "contractType": "PERMANENT",
                "bankAccountNumber": f"BANK-{emp_num}"
            }, expect_status=201, silent=True)
            STATE.employees[emp_num] = r
        progress += 1
        progress_bar(progress, total_progress, "Création tiers")

    log(f"{len(clients)} clients (5 locaux + 1 international) + "
        f"{len(suppliers)} fournisseurs + {len(employees_data)} consultants", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 8 — Capital initial + emprunt (cabinet plus petit que wholesale)
# ═══════════════════════════════════════════════════════════════════════════

def step_08_capital_and_loan() -> None:
    section(8, "Capital initial (2M HTG) + emprunt (1M HTG)")
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

    if post_entry("OD", "2024-01-01", "Apport en capital — SA",
                  [("521", 2_000_000, 0), ("101", 0, 2_000_000)], "svc-capital-init"):
        log("Capital : 2,000,000 HTG (D 521 / C 101)", "ok")

    if post_entry("OD", "2024-01-15", "Emprunt bancaire — Bank Nationale",
                  [("521", 1_000_000, 0), ("161", 0, 1_000_000)], "svc-loan-bank"):
        log("Emprunt : 1,000,000 HTG (D 521 / C 161)", "ok")

    log("Trésorerie totale disponible : 3,000,000 HTG", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 9 — Achats (matériel informatique, logiciels, fournitures bureau)
# ═══════════════════════════════════════════════════════════════════════════

def step_09_purchase_invoices() -> None:
    section(9, "Achats (matériel informatique, logiciels, fournitures bureau)")
    cid = COMPANY_ID
    supplier_names = [k for k in STATE.third_parties
                      if STATE.third_parties[k].get("type") == "SUPPLIER"]
    if not supplier_names:
        log("Aucun fournisseur — étape ignorée", "warn")
        return

    TAX_RATE = 10
    # Achats répartis sur 2024-2025
    # Pour un cabinet de services : pas de matières premières, mais du matériel
    # informatique, des logiciels, du mobilier de bureau, des fournitures
    purchases = [
        # (date, supplier_idx, lines : description, qty, unit_price)
        ("2024-01-20", 1, [("Ordinateurs portables Dell Latitude", 6, 85000)]),
        ("2024-02-15", 0, [("Fournitures bureau (lots)", 50, 2500)]),
        ("2024-04-10", 1, [("Licences Microsoft 365 (annuel)", 8, 18000)]),
        ("2024-06-20", 0, [("Mobilier de bureau (chaises, tables)", 12, 15000)]),
        ("2024-09-15", 1, [("Serveur Dell PowerEdge", 1, 450000)]),
        ("2024-11-10", 0, [("Fournitures bureau (lots)", 30, 2800)]),
        ("2025-02-15", 1, [("Licences Adobe Creative Cloud", 4, 32000)]),
        ("2025-04-20", 0, [("Fournitures bureau (lots)", 25, 3000)]),
        ("2025-06-15", 1, [("Écrans Dell 27 pouces", 6, 35000)]),
    ]

    total = 0
    for issue_date, sup_idx, items in purchases:
        supplier_name = supplier_names[sup_idx % len(supplier_names)]
        supplier_tp = STATE.third_parties.get(supplier_name)
        if not supplier_tp:
            continue
        lines = [{
            "description": desc,
            "quantity": qty, "unitPrice": price,
            "taxRate": TAX_RATE, "expenseAccountId": None
        } for desc, qty, price in items]

        body = {
            "thirdPartyId": supplier_tp["id"],
            "type": "STANDARD",
            "supplierReference": f"FOUR-SVC-{issue_date}",
            "issueDate": issue_date,
            "dueDate": (date.fromisoformat(issue_date) + timedelta(days=30)).isoformat(),
            "currency": "HTG", "lines": lines
        }
        r = api("POST", f"/companies/{cid}/purchase-invoices", body, expect_status=201, silent=True)
        if r:
            r2 = api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/receive", silent=True)
            if r2:
                total += 1
                STATE.purchase_invoices_issued += 1
                if random.random() < 0.85:  # cabinet paie ses fournisseurs à temps
                    api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/payments",
                        {"amount": r.get("totalAmount", 0)}, silent=True)

    log(f"{total} factures d'achat reçues (matériel + logiciels + fournitures)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 10 — Immobilisations (ordinateurs, mobilier, serveur)
# ═══════════════════════════════════════════════════════════════════════════

def step_10_fixed_assets() -> None:
    section(10, "Immobilisations (ordinateurs, mobilier, serveur)")
    cid = COMPANY_ID
    # NOTE : le seed sectoriel PROFESSIONAL_SERVICES crée les comptes 245 (Matériel de
    # bureau) et 2845 (Amort. matériel de bureau), pas 244/2844 (qui sont spécifiques
    # au commerce). On utilise 245/2845 pour le cabinet de conseil.
    asset_acct = find_account("245") or find_account("244")  # fallback sur 244 si 245 absent
    accum_dep_acct = find_account("2845") or find_account("2844")
    bank_acct = find_account("521")

    if "681" not in STATE.accounts:
        class6 = next((a for a in STATE.accounts.values() if a["code"] == "6"), None)
        if class6:
            r = api("POST", f"/companies/{cid}/chart-of-accounts/{class6['id']}/children", {
                "code": "681", "label": "Dotations aux amortissements",
                "reportingClass": "CHARGES", "reportingSubcategory": "COURANT",
                "normalBalance": "DEBIT", "isCollective": False
            }, expect_status=201, silent=True)
            if r and "id" in r:
                STATE.accounts["681"] = r

    dep_account_id = STATE.accounts.get("681", {}).get("id") if STATE.accounts.get("681") else None

    if not all([asset_acct, accum_dep_acct, bank_acct, dep_account_id]):
        log("Comptes d'immobilisation manquants — étape ignorée", "warn")
        return

    assets = [
        # Cabinet de services : pas de camions, juste du matériel de bureau
        ("Parc informatique (6 laptops Dell)",  "2024-01-20", 510_000, 36, 50_000),
        ("Mobilier de bureau (open space)",     "2024-06-20", 180_000, 60, 20_000),
        ("Serveur Dell PowerEdge",              "2024-09-15", 450_000, 48, 50_000),
        ("Écrans Dell 27 pouces (6 unités)",    "2025-06-15", 210_000, 36, 20_000),
    ]

    posted = 0
    for label, acq_date, cost, life_months, residual in assets:
        body = {
            "label": label, "acquisitionDate": acq_date,
            "acquisitionCost": cost, "usefulLifeMonths": life_months,
            "residualValue": residual, "depreciationMethod": "STRAIGHT_LINE",
            "assetAccountId": asset_acct["id"],
            "depreciationExpenseAccountId": dep_account_id,
            "accumulatedDepreciationAccountId": accum_dep_acct["id"],
            "cashAccountId": bank_acct["id"]
        }
        r = api("POST", f"/companies/{cid}/fixed-assets", body, expect_status=201, silent=True)
        if r:
            log(f"  • {label} — {cost:,} HTG, {life_months} mois", "data")
            # Amortissements mensuels selon la date d'acquisition
            # NOTE : l'endpoint correct est POST /fixed-assets/{id}/post-period-depreciation?periodId=...
            acq_year = int(acq_date[:4])
            acq_month = int(acq_date[5:7])
            # 2024 : de (acq_month-1) à 11
            if STATE.periods_2024 and acq_year == 2024:
                for i in range(acq_month - 1, min(12, len(STATE.periods_2024))):
                    period = STATE.periods_2024[i]
                    r2 = api("POST",
                        f"/companies/{cid}/fixed-assets/{r['id']}/post-period-depreciation?periodId={period['id']}",
                        silent=True)
                    if r2:
                        posted += 1
            # 2025 : de 0 à 6 (Jan-Jul)
            if STATE.periods_2025:
                for i in range(0, min(7, len(STATE.periods_2025))):
                    period = STATE.periods_2025[i]
                    r2 = api("POST",
                        f"/companies/{cid}/fixed-assets/{r['id']}/post-period-depreciation?periodId={period['id']}",
                        silent=True)
                    if r2:
                        posted += 1

    log(f"{len(assets)} immobilisations (informatique + mobilier + serveur)", "ok")
    log(f"  → {posted} amortissements mensuels postés", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 11 — Projets + taux horaires (time-billing)
# ═══════════════════════════════════════════════════════════════════════════

def step_11_projects_and_rates() -> None:
    section(11, "Projets + taux horaires (module :time-billing)")
    cid = COMPANY_ID

    if not USER_ID:
        log("USER_ID non disponible — étape ignorée", "warn")
        return

    # 8 projets sur 2024-2025 (mélange TIME_AND_MATERIALS et FIXED_PRICE)
    projects = [
        # (code, label, client_name, billing_type)
        ("PRJ-2024-01", "Audit SI Banque Nationale",         "Banque Nationale",          "TIME_AND_MATERIALS"),
        ("PRJ-2024-02", "Transformation digitale Digicel",   "Digicel Haiti",             "TIME_AND_MATERIALS"),
        ("PRJ-2024-03", "Refonte core banking Sogebank",     "Sogebank",                  "FIXED_PRICE"),
        ("PRJ-2024-04", "Étude marché BRH",                  "BRH — Banque République",   "FIXED_PRICE"),
        ("PRJ-2025-01", "Conseil stratégie Ministère",        "Ministère du Commerce",     "TIME_AND_MATERIALS"),
        ("PRJ-2025-02", "Appui World Bank (USD)",            "World Bank Haiti Office",   "TIME_AND_MATERIALS"),
        ("PRJ-2025-03", "Audit cybersécurité Sogebank",      "Sogebank",                  "TIME_AND_MATERIALS"),
        ("PRJ-2025-04", "Formation équipe Digicel",          "Digicel Haiti",             "FIXED_PRICE"),
    ]

    progress = 0
    for code, label, client_name, billing_type in projects:
        client_tp = STATE.third_parties.get(client_name)
        body = {
            "code": code,
            "label": label,
            "clientThirdPartyId": client_tp["id"] if client_tp else None,
            "billingType": billing_type
        }
        r = api("POST", f"/companies/{cid}/time-billing/projects", body,
                expect_status=201, silent=True)
        if r:
            STATE.projects[code] = r
            # Créer un taux horaire pour ce projet (consultant = utilisateur courant)
            # Taux selon le type de projet
            rate = HOURLY_RATES.get("Senior Consultant", 4500)
            if "Audit" in label or "cybersécurité" in label:
                rate = HOURLY_RATES.get("Technical Lead", 6000)
            elif "Formation" in label:
                rate = HOURLY_RATES.get("Consultant", 3000)

            r2 = api("POST", f"/companies/{cid}/time-billing/billable-rates", {
                "projectId": r["id"],
                "resourceUserId": USER_ID,
                "hourlyRate": rate,
                "currency": "HTG"
            }, expect_status=201, silent=True)
            if r2:
                STATE.billable_rates.append(r2)
        progress += 1
        progress_bar(progress, len(projects), "Création projets + taux")

    log(f"{len(STATE.projects)} projets créés (mélange TIME_AND_MATERIALS + FIXED_PRICE)", "ok")
    log(f"{len(STATE.billable_rates)} taux horaires associés", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 12 — Feuilles de temps + approbation + facturation
# ═══════════════════════════════════════════════════════════════════════════

def step_12_timesheet_and_invoicing() -> None:
    section(12, "Feuilles de temps + approbation + facturation (time-and-materials)")
    cid = COMPANY_ID

    if not USER_ID or not STATE.projects:
        log("USER_ID ou projets manquants — étape ignorée", "warn")
        return

    # Pour chaque projet TIME_AND_MATERIALS, créer 15-30 entrées de temps approuvées
    # (volume réaliste pour un cabinet de conseil : ~150-300h facturées par projet/an)
    # Puis générer des factures mensuelles basées sur le WIP (au lieu d'une seule facture)
    # Ajustement 2026-07-26 : on augmente le volume d'entrées et on facture chaque mois
    # pour que les produits couvrent les charges (salaires 590k/mois + loyer + SaaS).
    tm_projects = [(code, p) for code, p in STATE.projects.items()
                   if p.get("billingType") == "TIME_AND_MATERIALS"]

    total_entries = 0
    total_invoices = 0
    total_amount = 0

    for code, project in tm_projects:
        # 20-40 entrées par projet (au lieu de 5-15) pour générer ~300-600h facturables
        n_entries = random.randint(20, 40)
        # Extraire l'année du code projet pour déterminer les dates
        year = 2024 if "2024" in code else 2025
        max_month = 7 if year == 2025 else 12

        for i in range(n_entries):
            month = random.randint(1, max_month)
            day = random.randint(3, 27)
            hours = round(random.uniform(8, 40), 1)  # 8-40h par entrée (au lieu de 4-32)

            r = api("POST", f"/companies/{cid}/time-billing/timesheet-entries", {
                "projectId": project["id"],
                "resourceUserId": USER_ID,
                "entryDate": f"{year}-{month:02d}-{day:02d}",
                "hours": hours,
                "billable": True,
                "description": f"Consultation {code} — phase {i+1}"
            }, expect_status=201, silent=True)

            if r:
                # Approuver l'entrée (sinon pas facturable)
                r2 = api("PATCH",
                    f"/companies/{cid}/time-billing/timesheet-entries/{r['id']}/approve",
                    silent=True)
                if r2:
                    total_entries += 1
                    STATE.timesheet_entries.append(r2)

        # WIP du projet
        r = api("GET", f"/companies/{cid}/time-billing/projects/{project['id']}/unbilled",
                silent=True)
        if r:
            wip_amount = r.get("totalAmount", 0) or 0
            wip_hours = r.get("totalHours", 0) or 0
            log(f"  {color(code, 'cyan')} WIP : {wip_hours:.1f}h = {color(f'{wip_amount:,.0f}', 'cyan')} HTG", "data")

            # Facturer le WIP en plusieurs factures (une par trimestre) plutôt qu'une seule
            # au lieu d'une seule facture annuelle. Cela reflète la réalité d'un cabinet
            # qui facture ses clients chaque mois ou chaque trimestre.
            if wip_amount > 0 and project.get("clientThirdPartyId"):
                client_tp_id = project.get("clientThirdPartyId")
                # Nombre de factures : 4 par an (trimestrielles)
                n_invoices = 4 if year == 2024 else 2  # 2025 = Jan-Jul = 2 trimestres
                invoice_amount = wip_amount / n_invoices
                for inv_idx in range(n_invoices):
                    # Date de facture : milieu de chaque trimestre
                    if year == 2024:
                        issue_month = [3, 6, 9, 12][inv_idx]
                    else:
                        issue_month = [4, 7][inv_idx]
                    issue_date = f"{year}-{issue_month:02d}-15"
                    body = {
                        "thirdPartyId": client_tp_id,
                        "type": "STANDARD",
                        "issueDate": issue_date,
                        "dueDate": (date.fromisoformat(issue_date) + timedelta(days=30)).isoformat(),
                        "currency": "HTG",
                        "lines": [{
                            "description": f"Prestations consulting — projet {code} T{inv_idx+1} ({wip_hours/n_invoices:.1f}h)",
                            "quantity": 1,
                            "unitPrice": round(invoice_amount, 2),
                            "taxRate": 10
                        }]
                    }
                    r2 = api("POST", f"/companies/{cid}/invoicing/invoices", body,
                             expect_status=201, silent=True)
                    if r2:
                        # Émettre la facture → écriture D 411 / C 706 + C 443 TVA
                        api("POST", f"/companies/{cid}/invoicing/invoices/{r2['id']}/issue", silent=True)
                        total_invoices += 1
                        total_amount += invoice_amount
                        STATE.invoices_issued += 1

                        # Encaissement ~80% (cabinet de conseil — bons payeurs)
                        if random.random() < 0.8:
                            api("POST", f"/companies/{cid}/invoicing/invoices/{r2['id']}/record-payment", {
                                "amount": r2.get("totalAmount", 0),
                                "paymentDate": (date.fromisoformat(issue_date) + timedelta(days=random.randint(15, 45))).isoformat()
                            }, silent=True)

    log(f"{total_entries} entrées de temps approuvées (billable)", "ok")
    log(f"{total_invoices} factures de prestations émises (D 411 / C 706 + C 443)", "ok")
    log(f"  CA prestations facturé : {color(f'{total_amount:,.0f}', 'cyan')} HTG", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 13 — Charges mensuelles (salaires manuels, loyer bureau, internet)
# ═══════════════════════════════════════════════════════════════════════════

def step_13_monthly_expenses() -> None:
    section(13, "Charges mensuelles (salaires, loyer bureau, internet, abonnements)")
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

    # Salaires manuels (D 631 / C 521) — 420k/mois 2024, 440k/mois 2025
    # (4 consultants + personnel administratif)
    # Ajustement 2026-07-26 : réduit de 590k à 420k pour équilibrer le CR (les produits
    # de prestations doivent couvrir les charges). On saute juillet 2025 (couvert par
    # la campagne de paie consolidée étape 15).
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            if year == 2025 and m == 7:
                continue  # couvert par la campagne de paie
            amt = 420_000 if year == 2024 else 440_000
            if post_entry("OD", f"{year}-{m:02d}-28", f"Salaires manuels {m}/{year}",
                          [("631", amt, 0), ("521", 0, amt)], f"svc-sal-{year}-{m:02d}"):
                count += 1

    # Loyer bureau (D 621 / C 521) — 90k/mois (Pétion-Ville, open space)
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            amt = 90_000 if year == 2024 else 95_000
            if post_entry("OD", f"{year}-{m:02d}-01", f"Loyer bureau {m}/{year}",
                          [("621", amt, 0), ("521", 0, amt)], f"svc-rent-{year}-{m:02d}"):
                count += 1

    # Internet + téléphonie (D 626 / C 521) — mensuel
    # 626 = Frais de télécommunications (à créer si absent)
    if "626" not in STATE.accounts:
        class6 = next((a for a in STATE.accounts.values() if a["code"] == "6"), None)
        if class6:
            r = api("POST", f"/companies/{cid}/chart-of-accounts/{class6['id']}/children", {
                "code": "626", "label": "Frais de télécommunications",
                "reportingClass": "CHARGES", "reportingSubcategory": "COURANT",
                "normalBalance": "DEBIT", "isCollective": False
            }, expect_status=201, silent=True)
            if r and "id" in r:
                STATE.accounts["626"] = r

    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            amt = 12_000 if year == 2024 else 13_000
            if post_entry("OD", f"{year}-{m:02d}-15", f"Internet + téléphone {m}/{year}",
                          [("626", amt, 0), ("521", 0, amt)], f"svc-tel-{year}-{m:02d}"):
                count += 1

    # Abonnements SaaS (D 627 / C 521) — mensuel
    # 627 = Abonnements logiciels (à créer)
    if "627" not in STATE.accounts:
        class6 = next((a for a in STATE.accounts.values() if a["code"] == "6"), None)
        if class6:
            r = api("POST", f"/companies/{cid}/chart-of-accounts/{class6['id']}/children", {
                "code": "627", "label": "Abonnements logiciels SaaS",
                "reportingClass": "CHARGES", "reportingSubcategory": "COURANT",
                "normalBalance": "DEBIT", "isCollective": False
            }, expect_status=201, silent=True)
            if r and "id" in r:
                STATE.accounts["627"] = r

    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            amt = 25_000 if year == 2024 else 28_000
            if post_entry("OD", f"{year}-{m:02d}-15", f"Abonnements SaaS {m}/{year}",
                          [("627", amt, 0), ("521", 0, amt)], f"svc-saas-{year}-{m:02d}"):
                count += 1

    log(f"{count} écritures de charges (salaires, loyer, internet, SaaS)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 14 — Notes de frais consultants
# ═══════════════════════════════════════════════════════════════════════════

def step_14_expense_reports() -> None:
    section(14, "Notes de frais consultants (déplacements, repas clients, hébergement)")
    cid = COMPANY_ID
    employee_tps = [tp for tp in STATE.third_parties.values()
                    if tp.get("type") == "EMPLOYEE"]
    if not employee_tps:
        log("Aucun employé — étape ignorée", "warn")
        return

    # 10 notes de frais — cabinet de conseil = déplacements fréquents
    expenses = [
        ("2024-02-20", 0, True,  "TRAVEL",   "Taxi aéroport — client Digicel",          2500),
        ("2024-03-15", 0, False, "MEALS",    "Repas équipe projet Banque Nationale",    8500),
        ("2024-04-10", 1, True,  "TRAVEL",   "Vol Miami — formation technique",        85000),
        ("2024-05-22", 0, False, "TRAVEL",  "Hôtel 2 nuits — déplacement client",     24000),
        ("2024-07-15", 2, True,  "MEALS",    "Repas délégation World Bank",            18000),
        ("2024-09-30", 1, False, "TRAVEL",   "Déplacement Cap-Haïtien — audit BRH",    35000),
        ("2024-11-20", 0, True,  "OTHER",    "Cadeaux clients fin d'année",            22000),
        ("2025-02-10", 2, False, "MEALS",    "Repas négociation contrat Sogebank",     15000),
        ("2025-04-15", 0, True,  "TRAVEL",   "Mission World Bank Washington",          180000),
        ("2025-06-25", 1, False, "TRAVEL",  "Hôtel 3 nuits — formation Digicel",      36000),
    ]

    count = 0
    for issue_date, emp_idx, paid_directly, category, desc, amount in expenses:
        emp_tp = employee_tps[emp_idx % len(employee_tps)]
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
            api("POST", f"/companies/{cid}/expense-reports/{r['id']}/submit", silent=True)
            r2 = api("POST", f"/companies/{cid}/expense-reports/{r['id']}/approve", silent=True)
            if r2:
                api("POST", f"/companies/{cid}/expense-reports/{r['id']}/payments", silent=True)
                count += 1
                STATE.expenses_created += 1

    log(f"{count} notes de frais (cycle DRAFT→SUBMITTED→APPROVED→PAID)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 15 — Campagne de paie consolidée (4 consultants)
# ═══════════════════════════════════════════════════════════════════════════

def step_15_payroll_run() -> None:
    section(15, "Campagne de paie consolidée (4 consultants — juillet 2025)")
    cid = COMPANY_ID
    if not STATE.employees:
        log("Aucun employé — étape ignorée", "warn")
        return

    r = api("POST", f"/companies/{cid}/payroll-runs", {
        "periodMonth": 7, "periodYear": 2025,
        "employerContributionRate": 14
    }, expect_status=201, silent=True)
    if not r:
        log("Création campagne échouée", "err")
        return

    log(f"Campagne DRAFT créée : juillet 2025, taux patronal 14%", "ok")

    r2 = api("POST",
        f"/companies/{cid}/payroll-runs/{r['id']}/calculate?employerContributionRate=14",
        silent=True)
    if r2:
        log(f"Calculé : {r2.get('payslipCount', '?')} bulletins générés", "ok")
        log(f"  Brut total : {r2.get('totalGross', 0):,.0f} HTG", "data")
        log(f"  Net total  : {r2.get('totalNet', 0):,.0f} HTG", "data")
        log(f"  Charges patronales : {r2.get('totalEmployerContributions', 0):,.0f} HTG", "data")

    r3 = api("POST", f"/companies/{cid}/payroll-runs/{r['id']}/approve", silent=True)
    if r3:
        log(f"Approuvée — écriture consolidée générée", "ok")

    r4 = api("POST", f"/companies/{cid}/payroll-runs/{r['id']}/pay", silent=True)
    if r4:
        log("Marquée PAID", "ok")

    STATE.payroll_run_id = r["id"]


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 16 — Workflow d'approbation (seuil 200k HTG)
# ═══════════════════════════════════════════════════════════════════════════

def step_16_approval_workflow() -> None:
    section(16, "Workflow d'approbation (4 yeux, seuil 200k HTG)")
    cid = COMPANY_ID

    rule_body = {
        "actionType": "JOURNAL_ENTRY_POST",
        "thresholdAmount": 200_000,
        "requiredApproverRoles": ["ADMIN", "OWNER"],
        "minApprovals": 1
    }
    r = api("POST", f"/companies/{cid}/approval-workflow/rules", rule_body,
            expect_status=201, silent=True)
    if r:
        log(f"Règle d'approbation : JOURNAL_ENTRY_POST > 200,000 HTG", "ok")

    for alert_type, threshold in [
        ("INVOICE_OVERDUE", 30),
        ("APPROVAL_PENDING", 24),
    ]:
        api("POST", f"/companies/{cid}/notifications/alert-rules", {
            "type": alert_type, "thresholdValue": threshold, "active": True
        }, expect_status=201, silent=True)
    log("2 règles d'alerte (INVOICE_OVERDUE=30j, APPROVAL_PENDING=24h)", "ok")

    # Écriture > seuil — paiement bonus consultant
    body = {
        "journalCode": "OD",
        "entryDate": "2025-06-30",
        "description": "Bonus annuel consultants (déclenche workflow 4 yeux)",
        "lines": [
            {"accountCode": "631", "debit": 350_000, "credit": 0, "description": "Bonus annuel"},
            {"accountCode": "421", "debit": 0, "credit": 350_000, "description": "Personnel-rem. dues (bonus)"}
        ],
        "sourceModule": "MANUAL"
    }
    r = api("POST", f"/companies/{cid}/accounting-engine/journal-entries", body,
            expect_status=201, extra_headers={"Idempotency-Key": "svc-approval-test"}, silent=True)
    if r:
        entry_id = r["id"]
        log(f"Écriture DRAFT créée (350,000 HTG, > seuil 200k)", "data")
        r2 = api("POST", f"/companies/{cid}/accounting-engine/journal-entries/{entry_id}/post",
                 silent=True)
        if r2 and r2.get("status") == "PENDING_APPROVAL":
            log("✓ Écriture passée en PENDING_APPROVAL (workflow déclenché)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 17 — Opérations FX (1 client international — paiement USD)
# ═══════════════════════════════════════════════════════════════════════════

def step_17_fx_operations() -> None:
    section(17, "Opérations en devises (1 client World Bank — paiement USD)")
    cid = COMPANY_ID

    # Note : PROFESSIONAL_SERVICES n'active PAS FX_OPERATIONS par défaut.
    # Mais le module peut être activé manuellement via /companies/{id}/modules/{code}/activate
    # Tentative d'activation — si elle échoue, on saute l'étape FX.
    # NOTE : l'endpoint correct est /activate (pas /enable — voir CompanyController).
    r = api("POST", f"/companies/{cid}/modules/FX_OPERATIONS/activate", silent=True)
    fx_enabled = (r is not None)
    if not fx_enabled:
        # Tentative alternative via POST /modules avec body
        r = api("POST", f"/companies/{cid}/modules",
                {"moduleCode": "FX_OPERATIONS"}, silent=True)
        fx_enabled = (r is not None)

    if not fx_enabled:
        log("Module FX_OPERATIONS non activé — étape FX ignorée", "warn")
        log("  (PROFESSIONAL_SERVICES ne l'active pas automatiquement)", "data")
        return

    log("Module FX_OPERATIONS activé manuellement", "ok")

    # Taux USD/HTG
    rates = [
        ("USD", "HTG", USD_HTG_RATE_2024, "2024-01-01", "Banque Nationale"),
        ("USD", "HTG", USD_HTG_RATE_2025, "2025-01-01", "Banque Nationale"),
    ]
    for from_c, to_c, rate, date_, source in rates:
        api("POST", f"/companies/{cid}/fx-operations/rates", {
            "fromCurrency": from_c, "toCurrency": to_c,
            "rate": rate, "asOfDate": date_, "source": source
        }, expect_status=201, silent=True)
    log(f"{len(rates)} taux de change créés (USD/HTG)", "ok")

    # Encaissement USD du client World Bank — 2 opérations
    # 2024-09 : 10,000 USD reçus au taux 155 → 1,550,000 HTG
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "SELL",
        "fromCurrency": "USD", "toCurrency": "HTG",
        "fromAmount": 10000, "toAmount": 1_550_000,
        "rate": USD_HTG_RATE_2024,
        "operationDate": "2024-09-30",
        "description": "Encaissement World Bank — paiement USD projet PRJ-2024-04",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        gain = r.get("fxGainLoss", 0) or 0
        log(f"Encaissement USD : 10,000 USD → 1,550,000 HTG (taux {USD_HTG_RATE_2024})", "ok")

    # 2025-04 : 15,000 USD reçus au taux 162 → 2,430,000 HTG
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "SELL",
        "fromCurrency": "USD", "toCurrency": "HTG",
        "fromAmount": 15000, "toAmount": 2_430_000,
        "rate": USD_HTG_RATE_2025,
        "operationDate": "2025-04-30",
        "description": "Encaissement World Bank — paiement USD projet PRJ-2025-02",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        log(f"Encaissement USD : 15,000 USD → 2,430,000 HTG (taux {USD_HTG_RATE_2025})", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 18 — Clôture d'exercice 2024
# ═══════════════════════════════════════════════════════════════════════════

def step_18_fiscal_year_close() -> None:
    section(18, "Clôture d'exercice 2024")
    cid = COMPANY_ID

    if not STATE.fiscal_years:
        log("Aucun exercice fiscal — étape ignorée", "warn")
        return

    fy_2024 = STATE.fiscal_years[0] if "2024" in STATE.fiscal_years[0].get("label", "") else None
    if not fy_2024:
        log("Exercice 2024 introuvable — étape ignorée", "warn")
        return

    fy_id = fy_2024["id"]
    log(f"Clôture de l'exercice 2024 (ID: {color(fy_id, 'cyan')})", "data")
    r = api("POST", f"/companies/{cid}/accounting-engine/fiscal-years/{fy_id}/close", silent=True)
    if r:
        log(f"✓ Exercice 2024 clôturé — écriture de clôture générée", "ok")
    else:
        log("Clôture échouée — voir logs backend", "warn")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 19 — Vérification cohérence
# ═══════════════════════════════════════════════════════════════════════════

def step_19_verify_balance() -> None:
    section(19, "Vérification de cohérence — balance débit = crédit")
    cid = COMPANY_ID

    fy_id = None
    if len(STATE.fiscal_years) >= 2:
        fy_id = STATE.fiscal_years[1]["id"]
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
            log(color("✓ BILAN ÉQUILIBRÉ — débit = crédit", "green"), "ok")
        else:
            log(color(f"⚠ DÉSÉQUILIBRE : {diff:,.2f}", "yellow"), "warn")
        sorted_lines = sorted(r, key=lambda x: abs((x.get("balance") or 0)), reverse=True)[:10]
        log("Top 10 comptes par solde :", "data")
        for line in sorted_lines:
            code = line.get("accountCode", "?")
            label = line.get("accountLabel", "?")[:30]
            balance = line.get("balance", 0) or 0
            log(f"  {color(code, 'cyan')} {label:30s} : {balance:>15,.2f}", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Helpers pour les rapports par exercice fiscal
# ═══════════════════════════════════════════════════════════════════════════

def _fiscal_year_periods() -> list[tuple[str, str, str, str | None, str]]:
    """
    Retourne la liste des exercices fiscaux à traiter.
    Chaque tuple : (label, from_date, to_date, as_of_date, fy_id).
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
    log(f"  Produits  : {color(f'{products:,.0f}', 'cyan')} HTG (prestations services 706)", "data")
    log(f"  Charges   : {color(f'{charges:,.0f}', 'cyan')} HTG", "data")
    result_color = "green" if result >= 0 else "red"
    result_label = "BÉNÉFICE" if result >= 0 else "PERTE"
    log(f"  Résultat  : {color(f'{result:,.0f}', result_color)} HTG ({result_label})", "data")


def _present_trial_balance(label: str, fy_id: str | None) -> None:
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
    sorted_lines = sorted(r, key=lambda x: abs((x.get("balance") or 0)), reverse=True)[:5]
    log("  Top 5 comptes par solde :", "data")
    for line in sorted_lines:
        code = line.get("accountCode", "?")
        acct_label = line.get("accountLabel", "?")[:25]
        balance = line.get("balance", 0) or 0
        log(f"    {color(code, 'cyan')} {acct_label:25s} : {balance:>15,.2f}", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 20 — Exports de rapports × 2 exercices fiscaux (certains gated)
# ═══════════════════════════════════════════════════════════════════════════

def step_20_export_all_reports() -> None:
    section(20, "Exports de rapports × 2 exercices fiscaux (certains gated — INVENTORY off)")
    cid = COMPANY_ID
    periods = _fiscal_year_periods()
    if not periods:
        log("Aucun exercice fiscal — étape ignorée", "warn")
        return

    success = 0
    skipped = 0
    total_exports = 0

    for label, from_, to_, as_of, fy_id in periods:
        log(f"── Exercice {color(label, 'magenta')} ──", "info")
        # PROFESSIONAL_SERVICES n'active pas INVENTORY — on saute inventory_valuation
        # et stock_movement_register qui seraient gated. Les autres statements sont OK.
        per_fy_exports = [
            ("balance_sheet",        "pdf", None,  as_of, None, f"Bilan PDF au {as_of}"),
            ("income_statement",     "pdf", from_, to_,  None, f"Compte de résultat PDF {label}"),
            ("general_ledger",       "csv", from_, to_,  None, f"Grand livre CSV {label}"),
            ("tax_declaration",      "csv", from_, to_,  None, f"Déclaration fiscale CSV {label}"),
            ("purchase_register",    "csv", from_, to_,  None, f"Registre des achats CSV {label}"),
            ("expense_register",     "csv", from_, to_,  None, f"Registre des notes de frais CSV {label}"),
            ("payroll_summary",      "csv", from_, to_,  None, f"Résumé de paie CSV {label}"),
            ("fx_operations_register","csv", from_, to_, None, f"Registre des opérations FX CSV {label}"),
        ]
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

    # Snapshots globaux (indépendants de l'exercice)
    log(f"── Snapshots globaux (indépendants de l'exercice) ──", "info")
    snapshot_exports = [
        ("trial_balance",            "csv", None, None, None, "Balance générale CSV (tous exercices)"),
        # INVENTORY non activé pour PROFESSIONAL_SERVICES — gated
        ("inventory_valuation",      "csv", None, None, None, "Valorisation stocks CSV (GATED — INVENTORY off)"),
        ("stock_movement_register",  "csv", None, None, None, "Registre mouvements stock CSV (GATED — INVENTORY off)"),
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

    log(f"{success}/{total_exports} exports réussis, {skipped} ignorés (gated INVENTORY)", "ok")

    # Utilisation des consultants (endpoint :time-billing/utilization)
    log("Taux d'utilisation des consultants :", "data")
    r = api("GET", f"/companies/{cid}/time-billing/utilization?from=2024-01-01&to=2025-07-31",
            silent=True)
    if r and isinstance(r, list):
        log(f"  {len(r)} ligne(s) d'utilisation", "data")
        for line in r[:5]:
            project_id = str(line.get("projectId", "?"))[:8]
            hours = line.get("totalHours", 0) or 0
            billable = line.get("billableHours", 0) or 0
            util = line.get("utilizationRate", 0) or 0
            log(f"    Projet {color(project_id, 'cyan')} : {hours:.1f}h (billable {billable:.1f}h, util. {util:.1%})", "data")


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

    for label, from_, to_, as_of, fy_id in periods:
        print()
        log(color(f"══════ Exercice {label} ══════", "magenta"), "info")
        _present_balance_sheet(label, as_of)
        _present_income_statement(label, from_, to_)
        _present_trial_balance(label, fy_id)

    # Tableau comparatif
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
            if results[0][3] != 0:
                var = results[1][3] - results[0][3]
                var_pct = (var / abs(results[0][3]) * 100) if results[0][3] != 0 else 0
                var_color = "green" if var >= 0 else "red"
                log(f"  {'Variation':<20s} {'':>15s} {'':>15s} "
                    f"{color(f'{var:>15,.0f} ({var_pct:+.1f}%)', var_color)}", "data")

    # Dashboard
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
    print(color("  RÉCAPITULATIF — Cabinet conseil, cycle complet", "bold"))
    print(color("═" * 60, "cyan"))
    print(f"  Entreprise       : {color(COMPANY_NAME, 'cyan')}")
    print(f"  Company ID      : {color(COMPANY_ID or '?', 'cyan')}")
    print(f"  User ID         : {color(USER_ID or '?', 'cyan')}")
    print(f"  Utilisateur      : {color(USER_EMAIL, 'cyan')}")
    print(f"  Référentiel      : SYSCOHADA révisé")
    print(f"  Type métier      : PROFESSIONAL_SERVICES")
    print(f"  Secteur          : SERVICE")
    print(f"  Comptes créés    : {color(str(len(STATE.accounts)), 'cyan')}")
    print(f"  Tiers            : {color(str(len(STATE.third_parties)), 'cyan')} (6 clients + 2 fournisseurs + 4 consultants)")
    print(f"  Projets          : {color(str(len(STATE.projects)), 'cyan')} (TIME_AND_MATERIALS + FIXED_PRICE)")
    print(f"  Entrées temps    : {color(str(len(STATE.timesheet_entries)), 'cyan')} (approuvées, billable)")
    print(f"  Exercices        : {color(str(len(STATE.fiscal_years)), 'cyan')} (2024 + 2025)")
    print(f"  Période couverte : Janvier 2024 → Juillet 2025")
    print()
    print(color("  Différences clés vs RETAIL_COMMERCE :", "bold"))
    print(f"  • Module TIME_BILLING au lieu de INVENTORY")
    print(f"  • Pas de stock, pas de COGS, pas d'entrepôts")
    print(f"  • Revenu : facturation au temps passé (compte 706)")
    print(f"  • Marges très élevées (300-500% sur coût salarial)")
    print(f"  • 4 consultants (salaires 100k-180k)")
    print(f"  • Immobilisations : informatique + mobilier (pas de véhicules)")
    print(f"  • 1 client international (paiements USD — FX module activé manuellement)")
    print()
    print(color("  ✓ Chaque écriture : débit = crédit", "green"))
    print()
    print(color("  Connexion :", "bold"))
    print(f"    Email    : {color(USER_EMAIL, 'cyan')}")
    print(f"    Password : {color(USER_PASSWORD, 'cyan')}")
    print(f"    Backend  : {color(BASE_URL, 'cyan')}")
    print(color("═" * 60, "cyan"))
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(description="JOAccountant — Seed Service (PROFESSIONAL_SERVICES)")
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
    print(f"  {color('▸', 'magenta')} Type    : {color('PROFESSIONAL_SERVICES (SERVICE)', 'cyan')}")

    try:
        step_01_register_and_login()
        step_02_create_company()
        step_03_run_wizard()
        step_04_init_chart_of_accounts()
        step_05_create_config()
        step_06_create_tax_rules()
        step_07_create_third_parties()
        step_08_capital_and_loan()
        step_09_purchase_invoices()
        step_10_fixed_assets()
        step_11_projects_and_rates()
        step_12_timesheet_and_invoicing()
        step_13_monthly_expenses()
        step_14_expense_reports()
        step_15_payroll_run()
        step_16_approval_workflow()
        step_17_fx_operations()
        step_18_fiscal_year_close()
        step_19_verify_balance()
        step_20_export_all_reports()
        step_21_present_reports()
        print_summary()
        print(f"{color('✓', 'green')} Seed service terminé avec succès !\n")
    except Exception as e:
        print(f"\n{color('✗', 'red')} Erreur : {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

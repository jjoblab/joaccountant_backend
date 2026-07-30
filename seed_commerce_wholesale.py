#!/usr/bin/env python3
"""
JOAccountant v2.2 — Script de seed « Premium » pour entreprise de commerce en gros
(WHOLESALE_COMMERCE).

Scénario réaliste et complet : 2 exercices fiscaux (2024 + 2025) avec cycle d'exploitation
end-to-end d'un grossiste importateur-distributeur — capitalisation importante, achats en
conteneurs (USD), ventes B2B par palette/carton, marges plus faibles mais volumes élevés,
escompte pour règlement anticipé, opérations de change fréquentes (importations USD).

Différences clés vs seed_commerce.py (RETAIL_COMMERCE) :
  • Type métier : WHOLESALE_COMMERCE (au lieu de RETAIL_COMMERCE)
  • Volumes : palettes et cartons (vs unités)
  • Marges : 12-25% (vs 100-180% en détail)
  • Clients : entreprises (revendeurs, institutions) — pas de particuliers
  • Paiements : 60-90 jours (vs 30 jours en détail)
  • Imports USD : 1-2 conteneurs par trimestre (vs achats locaux en HTG)
  • Salaires : 80k-150k (vs 38k-45k en détail) — personnel spécialisé
  • Capital : 8M HTG + emprunt 5M (vs 3M + 2M en détail)
  • Multi-entrepôts (Port-au-Prince + Cap-Haïtien)

Flux couvert (tous les modules activés pour WHOLESALE_COMMERCE) :
  1. Authentification (register + login)
  2. Création entreprise (wizard step 1)
  3. Wizard étapes 2-9 + complete
  4. Plan comptable SYSCOHADA + seed sectoriel WHOLESALE_COMMERCE
  5. Journaux + exercices 2024+2025 + séquences documentaires
  6. Règle de TVA 10% + retenue salariale 10%
  7. Tiers : 8 clients B2B + 4 fournisseurs (2 locaux + 2 internationaux) + 5 employés
  8. Articles (12 SKU en gros) + 2 entrepôts
  9. Capital initial (8M HTG) + emprunt (5M HTG)
 10. Achats en HTG (fournisseurs locaux) + imports USD (conteneurs)
 11. Immobilisations (camions + équipements)
 12. Ventes B2B par palette (marges 12-25%)
 13. Sorties de stock (COGS)
 14. Encaissements (60-90 jours)
 15. Décaissements
 16. Notes de frais (déplacements régions)
 17. Salaires + charges patronales
 18. Campagne de paie consolidée
 19. Workflow d'approbation (seuil plus élevé — 1M HTG)
 20. Opérations FX (achats USD réguliers pour imports)
 21. Clôture d'exercice 2024
 22. Vérification cohérence (?fiscalYearId=)
 23. Exports PDF/CSV des statements :reporting v4.1 POUR CHAQUE exercice fiscal (2024 ET 2025)
     — bilan, CR, grand livre, tax_declaration, purchase_register, expense_register,
     payroll_summary, stock_movement_register, fx_operations_register + snapshots globaux
     (trial_balance, inventory_valuation, fixed_assets_register, aged_balance_suppliers)
 24. Présentation des rapports financiers POUR CHAQUE exercice (bilan + CR + balance
     générale avec ?fiscalYearId=) + tableau comparatif 2024 vs 2025 (variation)

Usage :
  python3 seed_commerce_wholesale.py --base-url http://localhost:8080
  python3 seed_commerce_wholesale.py --email existing@user.ht

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

USER_EMAIL = f"wholesale_{int(time.time())}@joaccountant.ht"
USER_PASSWORD = "Wholesale#2026"
COMPANY_NAME = "Grossiste Import Export SA"

SLOW = 0.02
USE_COLORS = sys.stdout.isatty()


@dataclass
class State:
    accounts: dict[str, dict] = field(default_factory=dict)
    third_parties: dict[str, dict] = field(default_factory=dict)
    items: dict[str, dict] = field(default_factory=dict)
    employees: dict[str, dict] = field(default_factory=dict)
    warehouses: dict[str, str] = field(default_factory=dict)  # label -> id
    fiscal_years: list[dict] = field(default_factory=list)
    periods_2024: list[dict] = field(default_factory=list)
    periods_2025: list[dict] = field(default_factory=list)
    invoices_issued: int = 0
    purchase_invoices_issued: int = 0
    expenses_created: int = 0
    payroll_run_id: str | None = None


STATE = State()

# Coûts d'achat unitaires par SKU (en HTG pour locaux, USD pour imports)
COSTS_LOCAL_HTG = {
    "RIZ-25KG":      1850,   # sac 25kg
    "HUILE-5L":       850,   # bidon 5L
    "SUCRE-25KG":    1500,   # sac 25kg
    "FARINE-25KG":   1650,   # sac 25kg
    "LAIT-2.5KG":    2200,   # boîte 2.5kg
}

COSTS_IMPORT_USD = {
    "PATES-CTN":      18,    # carton 20 paquets
    "TOMATE-CTN":     24,    # carton 24 boîtes
    "SAVON-CTN":      15,    # carton 48 pièces
    "CONS-CTN":       32,    # carton 24 boîtes
    "DETERGENT-CTN":  28,    # carton 12
    "SHAMPOO-CTN":    42,    # carton 24
    "PLOMB-CTN":      35,    # carton 20
}

# Taux USD→HTG utilisé pour la valorisation (varie au fil du temps — voir step_fx)
USD_HTG_RATE_2024_H1 = 150.0
USD_HTG_RATE_2024_H2 = 155.0
USD_HTG_RATE_2025_H1 = 160.0
USD_HTG_RATE_2025_H2 = 165.0


# ═══════════════════════════════════════════════════════════════════════════
#  Utilitaires d'affichage (réutilisés depuis seed_commerce.py)
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
    title = "JOAccountant v2.2 — Seed Commerce Wholesale (B2B)"
    subtitle = "Grossiste importateur-distributeur • Marges faibles, volumes élevés"
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
        "fullName": "Jean Gros", "locale": "fr"
    }, expect_status=201, silent=True)
    log(f"Utilisateur : {USER_EMAIL}", "ok")

    r = api("POST", "/auth/login", {"email": USER_EMAIL, "password": USER_PASSWORD})
    global TOKEN
    TOKEN = r["accessToken"]
    HEADERS["Authorization"] = f"Bearer {TOKEN}"
    log("Token JWT obtenu", "ok")


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
#  Étape 3 — Wizard 2-9 + complete
# ═══════════════════════════════════════════════════════════════════════════

def step_03_run_wizard() -> None:
    section(3, "Wizard (étapes 2-9 + complete — WHOLESALE_COMMERCE)")
    cid = COMPANY_ID
    spinner(0.5, "Saisie des 9 étapes du wizard")

    api("PATCH", f"/companies/{cid}/wizard/2",
        {"organizationNature": "FOR_PROFIT", "legalForm": "SA"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/3", {"sector": "COMMERCE"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/4", {"businessTypeCode": "WHOLESALE_COMMERCE"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/5",
        {"primaryActivityLabel": "Commerce de gros — importation et distribution"}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/6",
        {"accountingFrameworkId": FRAMEWORK_SYSCOHADA, "fiscalYearStartMonth": 1}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/7", {}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/8", {}, silent=True)
    api("PATCH", f"/companies/{cid}/wizard/9", {}, silent=True)
    api("POST", f"/companies/{cid}/wizard/complete", silent=True)

    log("Wizard complété — WHOLESALE_COMMERCE", "ok")
    log("Modules activés :", "data")
    log("  • Always-on (15) : CHART_OF_ACCOUNTS, ACCOUNTING_ENGINE, THIRD_PARTIES,", "data")
    log("    INVOICING, DOCUMENT_NUMBERING, APPROVAL_WORKFLOW, DOCUMENT_GENERATION,", "data")
    log("    NOTIFICATIONS, AUDIT_TRAIL, FINANCIAL_STATEMENTS, ANALYTICS, REPORTING,", "data")
    log("    EMPLOYEES, EXPENSES, PAYROLL", "data")
    log("  • Sectoriels (6) : INVENTORY, FIXED_ASSETS, BANK_RECONCILIATION, TAX,", "data")
    log("    PURCHASING, FX_OPERATIONS", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 4 — Plan comptable + seed sectoriel WHOLESALE_COMMERCE
# ═══════════════════════════════════════════════════════════════════════════

def step_04_init_chart_of_accounts() -> None:
    section(4, "Plan comptable SYSCOHADA + seed sectoriel WHOLESALE_COMMERCE")
    cid = COMPANY_ID

    r = api("POST", f"/companies/{cid}/chart-of-accounts/initialize",
            {"accountingFrameworkId": FRAMEWORK_SYSCOHADA, "businessTypeCode": "WHOLESALE_COMMERCE"})
    if r:
        log(f"Plan initialisé : {color(str(r.get('accountsCreated', '?')), 'cyan')} comptes créés", "ok")

    accounts = api("GET", f"/companies/{cid}/chart-of-accounts")
    for acc in accounts:
        STATE.accounts[acc["code"]] = acc

    log(f"Total : {color(str(len(STATE.accounts)), 'cyan')} comptes chargés en mémoire", "ok")

    key_accounts = ["101", "161", "401", "411", "421", "433", "443", "445",
                    "521", "571", "310", "601", "603", "631", "621", "701", "244", "2844", "681", "776"]
    found = [c for c in key_accounts if c in STATE.accounts]
    log(f"Comptes clés présents : {color(', '.join(found[:10]), 'cyan')}", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 5 — Journaux + exercices + séquences
# ═══════════════════════════════════════════════════════════════════════════

def step_05_create_config() -> None:
    section(5, "Journaux, exercices fiscaux, séquences documentaires")
    cid = COMPANY_ID

    journals = [
        ("VT", "Journal des ventes (gros)"),
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
#  Étape 7 — Tiers (clients B2B + fournisseurs + employés)
# ═══════════════════════════════════════════════════════════════════════════

def step_07_create_third_parties() -> None:
    section(7, "Tiers (8 clients B2B + 4 fournisseurs + 5 employés)")
    cid = COMPANY_ID
    clients_acct = find_account("411")
    suppliers_acct = find_account("401")
    employees_acct = find_account("421")

    clients = [
        # Revendeurs
        ("Mini-Marché Delmas",       "delmas@biz.ht"),
        ("Supermarché Giant Pétion", "giant@mart.ht"),
        ("Pharmacie Plus Carrefour", "plus@pharma.ht"),
        ("Boutique Enstock Tabarre", "enstock@biz.ht"),
        # Institutions
        ("Hôpital General",          "achats@hg.ht"),
        ("Université Quisqueya",     "achats@uniq.ht"),
        # Hôtels & restaurants
        ("Hôtel Karibe",             "achats@karibe.ht"),
        ("Restaurant La Réserve",    "reserve@resto.ht"),
    ]
    suppliers = [
        # Locaux (HTG)
        ("Distributeur FoodCo HT", "foodco@dist.ht"),
        ("Grossiste Plus HT",      "plus@gros.ht"),
        # Internationaux (USD)
        ("Asian Imports Co. Ltd",  "sales@asianimports.cn"),
        ("Global Foods Miami",     "sales@globalfoods.us"),
    ]
    employees_data = [
        ("Marc Pierre-Louis", "marc@wholesale.ht", "EMP-001", "Directeur Général",     "Direction",  150000),
        ("Nadège Joseph",     "nadege@wholesale.ht","EMP-002", "Directrice Commerciale", "Ventes",     95000),
        ("Patrick Moïse",     "patrick@wholesale.ht","EMP-003","Responsable Achats",    "Achats",     85000),
        ("Carline Étienne",   "carline@wholesale.ht","EMP-004","Comptable Senior",      "Finance",    80000),
        ("Robert Cherubin",   "robert@wholesale.ht", "EMP-005","Magasinier en Chef",    "Logistique", 55000),
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
            "address": "Port-au-Prince" if "HT" in name else "International"
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

    log(f"{len(clients)} clients B2B + {len(suppliers)} fournisseurs (2 locaux + 2 internationaux) + "
        f"{len(employees_data)} employés", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 8 — Entrepôts + articles (en gros)
# ═══════════════════════════════════════════════════════════════════════════

def step_08_create_warehouses_and_items() -> None:
    section(8, "Entrepôts (2) + articles (12 SKU en gros)")
    cid = COMPANY_ID
    stock_acct = find_account("310")
    cogs_acct = find_account("603")

    for label in ["Entrepôt Port-au-Prince", "Entrepôt Cap-Haïtien"]:
        r = api("POST", f"/companies/{cid}/inventory/warehouses",
                {"label": label}, expect_status=201, silent=True)
        if r:
            STATE.warehouses[label] = r["id"]

    # Articles locaux (HTG)
    items_local = [
        ("RIZ-25KG",      "Sac de riz 25kg",         "sac"),
        ("HUILE-5L",      "Bidon huile végétale 5L", "bidon"),
        ("SUCRE-25KG",    "Sac de sucre 25kg",       "sac"),
        ("FARINE-25KG",   "Sac de farine 25kg",      "sac"),
        ("LAIT-2.5KG",    "Boîte lait en poudre 2.5kg","boîte"),
    ]
    # Articles importés (USD à l'achat, vendus en HTG)
    items_import = [
        ("PATES-CTN",       "Carton spaghetti 20 paquets", "carton"),
        ("TOMATE-CTN",      "Carton tomate concentré 24 boîtes","carton"),
        ("SAVON-CTN",       "Carton savon 48 pièces",      "carton"),
        ("CONS-CTN",        "Carton conserves 24 boîtes",  "carton"),
        ("DETERGENT-CTN",   "Carton détergent 12 unites",  "carton"),
        ("SHAMPOO-CTN",     "Carton shampoing 24 unités",  "carton"),
        ("PLOMB-CTN",       "Carton papier hygiénique 20 rouleaux","carton"),
    ]

    progress = 0
    total_items = len(items_local) + len(items_import)
    for sku, label, uom in items_local + items_import:
        r = api("POST", f"/companies/{cid}/inventory/items", {
            "sku": sku, "label": label, "unitOfMeasure": uom,
            "costingMethod": "FIFO", "reorderThreshold": 50,
            "inventoryAccountId": stock_acct["id"] if stock_acct else None,
            "cogsAccountId": cogs_acct["id"] if cogs_acct else None
        }, expect_status=201, silent=True)
        if r:
            STATE.items[sku] = r
        progress += 1
        progress_bar(progress, total_items, "Création articles")

    log(f"{len(STATE.items)} articles + {len(STATE.warehouses)} entrepôts (PAP + Cap)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 9 — Capital initial (8M) + emprunt (5M)
# ═══════════════════════════════════════════════════════════════════════════

def step_09_capital_and_loan() -> None:
    section(9, "Capital initial (8M HTG) + emprunt bancaire (5M HTG)")
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

    # Capital : D 521 / C 101 — 8,000,000 HTG
    if post_entry("OD", "2024-01-01", "Apport en capital — SA",
                  [("521", 8_000_000, 0), ("101", 0, 8_000_000)], "ws-capital-init"):
        log("Capital : 8,000,000 HTG (D 521 Banque / C 101 Capital social)", "ok")

    # Emprunt : D 521 / C 161 — 5,000,000 HTG
    if post_entry("OD", "2024-01-15", "Emprunt bancaire — Bank Nationale",
                  [("521", 5_000_000, 0), ("161", 0, 5_000_000)], "ws-loan-bank"):
        log("Emprunt : 5,000,000 HTG (D 521 Banque / C 161 Emprunt)", "ok")

    log("Trésorerie totale disponible : 13,000,000 HTG", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 10 — Achats (locaux HTG + imports USD via FX)
# ═══════════════════════════════════════════════════════════════════════════

def step_10_purchase_invoices() -> None:
    section(10, "Achats fournisseurs (locaux HTG + imports USD via FX)")
    cid = COMPANY_ID
    supplier_local = [k for k in STATE.third_parties
                      if STATE.third_parties[k].get("type") == "SUPPLIER" and "HT" in k]
    supplier_intl = [k for k in STATE.third_parties
                     if STATE.third_parties[k].get("type") == "SUPPLIER" and "HT" not in k]
    if not supplier_local and not supplier_intl:
        log("Aucun fournisseur — étape ignorée", "warn")
        return

    TAX_RATE = 10
    pap_wh = STATE.warehouses.get("Entrepôt Port-au-Prince")
    cap_wh = STATE.warehouses.get("Entrepôt Cap-Haïtien")

    # Achats locaux — factures en HTG
    purchases_local = [
        ("2024-01-10", "Distributeur FoodCo HT", "Entrepôt Port-au-Prince",
         [("RIZ-25KG", 200, 1850), ("SUCRE-25KG", 150, 1500)]),
        ("2024-04-10", "Grossiste Plus HT", "Entrepôt Port-au-Prince",
         [("HUILE-5L", 300, 850), ("FARINE-25KG", 200, 1650)]),
        ("2024-07-15", "Distributeur FoodCo HT", "Entrepôt Cap-Haïtien",
         [("RIZ-25KG", 150, 1880), ("LAIT-2.5KG", 100, 2200)]),
        ("2024-10-10", "Grossiste Plus HT", "Entrepôt Port-au-Prince",
         [("SUCRE-25KG", 200, 1520), ("HUILE-5L", 250, 870)]),
        ("2025-01-10", "Distributeur FoodCo HT", "Entrepôt Cap-Haïtien",
         [("FARINE-25KG", 180, 1670), ("RIZ-25KG", 220, 1900)]),
        ("2025-04-15", "Grossiste Plus HT", "Entrepôt Port-au-Prince",
         [("HUILE-5L", 280, 880), ("LAIT-2.5KG", 120, 2250)]),
    ]

    total = 0
    for issue_date, supplier_name, wh_label, items in purchases_local:
        supplier_tp = STATE.third_parties.get(supplier_name)
        if not supplier_tp:
            continue
        lines = [{
            "description": f"Achat {sku}",
            "quantity": qty, "unitPrice": price,
            "taxRate": TAX_RATE, "expenseAccountId": None
        } for sku, qty, price in items]

        body = {
            "thirdPartyId": supplier_tp["id"],
            "type": "STANDARD",
            "supplierReference": f"FOUR-LOC-{issue_date}",
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
                # Entrée de stock
                wh_id = STATE.warehouses.get(wh_label)
                for sku, qty, price in items:
                    item = STATE.items.get(sku)
                    if item and wh_id:
                        supplier_acct = find_account("401")
                        api("POST", f"/companies/{cid}/inventory/stock-moves", {
                            "itemId": item["id"], "warehouseId": wh_id,
                            "moveDate": issue_date, "direction": "IN",
                            "quantity": qty, "unitCost": price,
                            "sourceDocument": f"Achat local {issue_date}",
                            "counterpartyAccountId": supplier_acct["id"] if supplier_acct else None
                        }, expect_status=201, silent=True)
                if random.random() < 0.7:
                    api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/payments",
                        {"amount": r.get("totalAmount", 0)}, silent=True)

    log(f"{total} factures d'achat locales reçues (HTG)", "ok")

    # Imports USD — factures en USD (via supplier international)
    # Conversion faite via :fx-operations (achats USD)
    purchases_import = [
        # (date, supplier, container_id, wh, items avec prix USD, taux USD/HTG applicable)
        ("2024-02-20", "Asian Imports Co. Ltd", "CNT-2024-01", "Entrepôt Port-au-Prince",
         [("PATES-CTN", 500, 18), ("TOMATE-CTN", 300, 24)], USD_HTG_RATE_2024_H1),
        ("2024-05-15", "Global Foods Miami",    "CNT-2024-02", "Entrepôt Port-au-Prince",
         [("SAVON-CTN", 800, 15), ("CONS-CTN", 400, 32)], USD_HTG_RATE_2024_H1),
        ("2024-08-20", "Asian Imports Co. Ltd", "CNT-2024-03", "Entrepôt Cap-Haïtien",
         [("DETERGENT-CTN", 500, 28), ("SHAMPOO-CTN", 300, 42)], USD_HTG_RATE_2024_H2),
        ("2024-11-10", "Global Foods Miami",    "CNT-2024-04", "Entrepôt Port-au-Prince",
         [("PLOMB-CTN", 600, 35), ("PATES-CTN", 400, 19)], USD_HTG_RATE_2024_H2),
        ("2025-02-15", "Asian Imports Co. Ltd", "CNT-2025-01", "Entrepôt Port-au-Prince",
         [("TOMATE-CTN", 350, 25), ("SAVON-CTN", 700, 16)], USD_HTG_RATE_2025_H1),
        ("2025-05-20", "Global Foods Miami",    "CNT-2025-02", "Entrepôt Cap-Haïtien",
         [("CONS-CTN", 450, 33), ("DETERGENT-CTN", 400, 29)], USD_HTG_RATE_2025_H1),
    ]

    total_import = 0
    for issue_date, supplier_name, cnt_id, wh_label, items, usd_rate in purchases_import:
        supplier_tp = STATE.third_parties.get(supplier_name)
        if not supplier_tp:
            continue
        # Prix en HTG (converti au taux applicable)
        lines = [{
            "description": f"Import {sku} (container {cnt_id})",
            "quantity": qty,
            "unitPrice": round(price_usd * usd_rate, 2),  # conversion en HTG
            "taxRate": TAX_RATE,
            "expenseAccountId": None
        } for sku, qty, price_usd in items]

        body = {
            "thirdPartyId": supplier_tp["id"],
            "type": "STANDARD",
            "supplierReference": cnt_id,
            "issueDate": issue_date,
            "dueDate": (date.fromisoformat(issue_date) + timedelta(days=60)).isoformat(),
            "currency": "HTG", "lines": lines
        }
        r = api("POST", f"/companies/{cid}/purchase-invoices", body, expect_status=201, silent=True)
        if r:
            r2 = api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/receive", silent=True)
            if r2:
                total_import += 1
                STATE.purchase_invoices_issued += 1
                wh_id = STATE.warehouses.get(wh_label)
                for sku, qty, price_usd in items:
                    item = STATE.items.get(sku)
                    if item and wh_id:
                        unit_cost_htg = round(price_usd * usd_rate, 2)
                        supplier_acct = find_account("401")
                        api("POST", f"/companies/{cid}/inventory/stock-moves", {
                            "itemId": item["id"], "warehouseId": wh_id,
                            "moveDate": issue_date, "direction": "IN",
                            "quantity": qty, "unitCost": unit_cost_htg,
                            "sourceDocument": f"Import {cnt_id}",
                            "counterpartyAccountId": supplier_acct["id"] if supplier_acct else None
                        }, expect_status=201, silent=True)
                # 60% des imports payés (délais plus longs pour intl)
                if random.random() < 0.6:
                    api("POST", f"/companies/{cid}/purchase-invoices/{r['id']}/payments",
                        {"amount": r.get("totalAmount", 0)}, silent=True)

    log(f"{total_import} factures d'import reçues (USD → HTG)", "ok")
    log(f"  → {total + total_import} factures d'achat au total", "data")
    log(f"  → entrées de stock dans les 2 entrepôts (PAP + Cap)", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 11 — Immobilisations (camions + équipements)
# ═══════════════════════════════════════════════════════════════════════════

def step_11_fixed_assets() -> None:
    section(11, "Immobilisations (2 camions + équipements entrepôt)")
    cid = COMPANY_ID
    asset_acct = find_account("244")
    accum_dep_acct = find_account("2844")
    bank_acct = find_account("521")

    # Compte 681 (Dotations aux amortissements)
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
        ("Camion Isuzu FRR 2024",        "2024-02-01", 4_500_000, 60, 500_000),
        ("Camion Hino 300 2024",         "2024-02-01", 3_800_000, 60, 400_000),
        ("Chariot élévateur Toyota",     "2024-03-01", 1_800_000, 84, 200_000),
        ("Équipement entrepôt (racks)",  "2024-03-15",   900_000, 60, 100_000),
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
            # Amortissements mensuels 2024 (Feb-Dec = 11 mois) + 2025 (Jan-Jul = 7)
            # NOTE : l'endpoint correct est POST /fixed-assets/{id}/post-period-depreciation?periodId=...
            if STATE.periods_2024:
                for i in range(1, 12):
                    period = STATE.periods_2024[i]
                    r2 = api("POST",
                        f"/companies/{cid}/fixed-assets/{r['id']}/post-period-depreciation?periodId={period['id']}",
                        silent=True)
                    if r2:
                        posted += 1
            if STATE.periods_2025:
                for i in range(0, 7):
                    period = STATE.periods_2025[i]
                    r2 = api("POST",
                        f"/companies/{cid}/fixed-assets/{r['id']}/post-period-depreciation?periodId={period['id']}",
                        silent=True)
                    if r2:
                        posted += 1

    log(f"{len(assets)} immobilisations (2 camions + chariot + racks)", "ok")
    log(f"  → {posted} amortissements mensuels postés (D 681 / C 2844)", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 12 — Ventes B2B (palettes/cartons, marges 12-25%)
# ═══════════════════════════════════════════════════════════════════════════

def step_12_sales_and_cogs() -> None:
    section(12, "Ventes B2B + sorties de stock + encaissements (60-90 j)")
    cid = COMPANY_ID
    client_names = [k for k in STATE.third_parties
                    if STATE.third_parties[k].get("type") == "CLIENT"]
    if not client_names or not STATE.warehouses:
        log("Pas de clients ou d'entrepôt — étape ignorée", "warn")
        return

    TAX_RATE = 10
    # Marge gros : 12-25% (vs 100-180% en détail)
    # Volume : 200-1500 unités par facture (vs 50-200 en détail)
    all_invoices = []
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for month in months:
            num_invoices = random.randint(4, 7)  # plus de factures en gros
            for _ in range(num_invoices):
                day = random.randint(3, 27)
                client = random.choice(client_names)
                sku = random.choice(list(STATE.items.keys()))
                qty = random.randint(200, 1500)

                # Coût : soit HTG direct, soit USD converti
                if sku in COSTS_LOCAL_HTG:
                    cost = COSTS_LOCAL_HTG[sku]
                elif sku in COSTS_IMPORT_USD:
                    usd_cost = COSTS_IMPORT_USD[sku]
                    # Taux USD applicable selon la période
                    if year == 2024 and month <= 6:
                        cost = round(usd_cost * USD_HTG_RATE_2024_H1)
                    elif year == 2024:
                        cost = round(usd_cost * USD_HTG_RATE_2024_H2)
                    elif year == 2025 and month <= 6:
                        cost = round(usd_cost * USD_HTG_RATE_2025_H1)
                    else:
                        cost = round(usd_cost * USD_HTG_RATE_2025_H2)
                else:
                    cost = 100

                # Marge 12-25%
                unit_price = round(cost * random.uniform(1.12, 1.25), 2)

                # Délais B2B plus longs (60-90 jours)
                due_days = random.choice([60, 75, 90])
                all_invoices.append({
                    "client": client, "date": date(year, month, day),
                    "sku": sku, "qty": qty, "unit_price": unit_price, "cost": cost,
                    "due_days": due_days
                })

    total_inv = 0
    total_cogs = 0
    total_paid = 0
    progress = 0

    wh_ids = list(STATE.warehouses.values())
    for inv in all_invoices:
        client_tp = STATE.third_parties.get(inv["client"])
        item = STATE.items.get(inv["sku"])
        if not client_tp or not item:
            continue

        issue_date = inv["date"].isoformat()
        due_date = (inv["date"] + timedelta(days=inv["due_days"])).isoformat()

        body = {
            "thirdPartyId": client_tp["id"], "type": "STANDARD",
            "issueDate": issue_date, "dueDate": due_date, "currency": "HTG",
            "lines": [{
                "description": f"Vente gros {inv['sku']}",
                "quantity": inv["qty"], "unitPrice": inv["unit_price"],
                "taxRate": TAX_RATE, "itemId": item["id"]
            }]
        }
        r = api("POST", f"/companies/{cid}/invoicing/invoices", body, expect_status=201, silent=True)
        if not r:
            continue
        invoice_id = r["id"]
        api("POST", f"/companies/{cid}/invoicing/invoices/{invoice_id}/issue", silent=True)
        total_inv += 1

        # Sortie de stock — choisir un entrepôt aléatoire
        wh_id = random.choice(wh_ids) if wh_ids else None
        if wh_id:
            r2 = api("POST", f"/companies/{cid}/inventory/stock-moves", {
                "itemId": item["id"], "warehouseId": wh_id,
                "moveDate": issue_date, "direction": "OUT",
                "quantity": inv["qty"],
                "sourceDocument": f"Vente facture {r.get('invoiceNumber', '?')}"
            }, expect_status=201, silent=True)
            if r2:
                total_cogs += 1

        # Encaissement B2B : 70% (les clients B2B paient généralement, mais avec délais)
        if random.random() < 0.7:
            api("POST", f"/companies/{cid}/invoicing/invoices/{invoice_id}/record-payment", {
                "amount": r.get("totalAmount", 0),
                "paymentDate": (inv["date"] + timedelta(days=random.randint(30, 90))).isoformat()
            }, silent=True)
            total_paid += 1

        progress += 1
        if progress % 15 == 0 or progress == len(all_invoices):
            progress_bar(progress, len(all_invoices), "Ventes B2B")

    STATE.invoices_issued = total_inv
    log(f"{total_inv} factures de vente B2B (D 411 / C 701 + C 443 TVA)", "ok")
    log(f"{total_cogs} sorties de stock (D 603 COGS / C 310 Stock)", "ok")
    log(f"{total_paid} encaissements (D 521 / C 411) — délais 60-90j", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 13 — Charges mensuelles (salaires manuels, loyer entrepôts, carburant)
# ═══════════════════════════════════════════════════════════════════════════

def step_13_monthly_expenses() -> None:
    section(13, "Charges mensuelles (salaires manuels, loyer 2 entrepôts, carburant camions)")
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

    # Salaires manuels (D 631 / C 521) — 350k/mois 2024, 380k/mois 2025 (effectif + élevé)
    # On saute juillet 2025 (couvert par la campagne de paie consolidée étape 15).
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            if year == 2025 and m == 7:
                continue  # couvert par la campagne de paie
            amt = 350_000 if year == 2024 else 380_000
            if post_entry("OD", f"{year}-{m:02d}-28", f"Salaires manuels {m}/{year}",
                          [("631", amt, 0), ("521", 0, amt)], f"ws-sal-{year}-{m:02d}"):
                count += 1

    # Loyer entrepôts (D 621 / C 521) — 120k/mois (PAP + Cap)
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            amt = 120_000 if year == 2024 else 130_000
            if post_entry("OD", f"{year}-{m:02d}-01", f"Loyer entrepôts {m}/{year}",
                          [("621", amt, 0), ("521", 0, amt)], f"ws-rent-{year}-{m:02d}"):
                count += 1

    # Électricité entrepôts (D 622 / C 521)
    for q_month in [3, 6, 9, 12]:
        if post_entry("OD", f"2024-{q_month:02d}-15", f"Électricité Q{q_month//3} 2024",
                      [("622", 45_000, 0), ("521", 0, 45_000)], f"ws-elec-2024-{q_month:02d}"):
            count += 1
    for q_month in [3, 6]:
        if post_entry("OD", f"2025-{q_month:02d}-15", f"Électricité Q{q_month//3} 2025",
                      [("622", 48_000, 0), ("521", 0, 48_000)], f"ws-elec-2025-{q_month:02d}"):
            count += 1

    # Carburant camions (D 623 / C 521) — volume + élevé (flotte de 2 camions)
    for year in [2024, 2025]:
        months = range(1, 13) if year == 2024 else range(1, 8)
        for m in months:
            amt = 45_000 if year == 2024 else 50_000
            if post_entry("OD", f"{year}-{m:02d}-15", f"Carburant camions {m}/{year}",
                          [("623", amt, 0), ("521", 0, amt)], f"ws-fuel-{year}-{m:02d}"):
                count += 1

    # Assurance flotte (D 625 / C 521) — semestriel
    for sem_month in [1, 7]:
        if post_entry("OD", f"2024-{sem_month:02d}-01", f"Assurance flotte S{1 if sem_month==1 else 2} 2024",
                      [("625", 180_000, 0), ("521", 0, 180_000)], f"ws-ins-2024-{sem_month:02d}"):
            count += 1
    if post_entry("OD", "2025-01-01", "Assurance flotte S1 2025",
                  [("625", 195_000, 0), ("521", 0, 195_000)], "ws-ins-2025-01"):
        count += 1

    log(f"{count} écritures de charges (salaires, loyer, électricité, carburant, assurance)", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 14 — Notes de frais (déplacements régions)
# ═══════════════════════════════════════════════════════════════════════════

def step_14_expense_reports() -> None:
    section(14, "Notes de frais employés (déplacements, hôtels, repas clients)")
    cid = COMPANY_ID
    employee_tps = [tp for tp in STATE.third_parties.values()
                    if tp.get("type") == "EMPLOYEE"]
    if not employee_tps:
        log("Aucun employé — étape ignorée", "warn")
        return

    # 8 notes de frais (volume + élevé — équipe commerciale active)
    expenses = [
        # (date, employee_idx, paidDirectly, category, description, amount)
        ("2024-02-15", 1, True,  "TRAVEL",   "Déplacement Cap-Haïtien (prospection)",       15000),
        ("2024-03-20", 1, False, "MEALS",    "Repas client Hôtel Karibe",                   8500),
        ("2024-05-10", 2, True,  "TRAVEL",   "Mission achats Miami (Global Foods)",         45000),
        ("2024-07-22", 1, False, "TRAVEL",  "Hôtel 3 nuits Cap-Haïtien",                  18000),
        ("2024-09-15", 3, True,  "OTHER",    "Fournitures bureau comptabilité",             7500),
        ("2025-02-10", 1, True,  "TRAVEL",   "Déplacement Jacmel (nouveau client)",        12500),
        ("2025-04-05", 2, False, "MEALS",    "Repas déléguation Asian Imports",            12000),
        ("2025-06-20", 0, True,  "OTHER",    "Cadeaux clients fin d'année",                25000),
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
#  Étape 15 — Campagne de paie consolidée (5 employés)
# ═══════════════════════════════════════════════════════════════════════════

def step_15_payroll_run() -> None:
    section(15, "Campagne de paie consolidée (5 employés — juillet 2025)")
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
#  Étape 16 — Workflow d'approbation (seuil 1M HTG pour gros)
# ═══════════════════════════════════════════════════════════════════════════

def step_16_approval_workflow() -> None:
    section(16, "Workflow d'approbation (4 yeux, seuil 1M HTG pour gros)")
    cid = COMPANY_ID

    # Règle d'approbation (seuil plus élevé pour le gros — 1M HTG)
    rule_body = {
        "actionType": "JOURNAL_ENTRY_POST",
        "thresholdAmount": 1_000_000,
        "requiredApproverRoles": ["ADMIN", "OWNER"],
        "minApprovals": 1
    }
    r = api("POST", f"/companies/{cid}/approval-workflow/rules", rule_body,
            expect_status=201, silent=True)
    if r:
        log(f"Règle d'approbation créée : JOURNAL_ENTRY_POST > 1,000,000 HTG", "ok")

    # Règles d'alerte
    for alert_type, threshold in [
        ("LOW_STOCK", 50),         # seuil + élevé pour le gros
        ("INVOICE_OVERDUE", 60),   # B2B — délais plus longs
        ("APPROVAL_PENDING", 24),
    ]:
        api("POST", f"/companies/{cid}/notifications/alert-rules", {
            "type": alert_type, "thresholdValue": threshold, "active": True
        }, expect_status=201, silent=True)
    log("3 règles d'alerte (LOW_STOCK=50, INVOICE_OVERDUE=60j, APPROVAL_PENDING=24h)", "ok")

    # Écriture > seuil — achat en gros
    body = {
        "journalCode": "OD",
        "entryDate": "2025-06-30",
        "description": "Achat conteneur spécial (déclenche workflow 4 yeux)",
        "lines": [
            {"accountCode": "601", "debit": 1_500_000, "credit": 0, "description": "Conteneur spécial"},
            {"accountCode": "401", "debit": 0, "credit": 1_500_000, "description": "Fournisseur intl"}
        ],
        "sourceModule": "MANUAL"
    }
    r = api("POST", f"/companies/{cid}/accounting-engine/journal-entries", body,
            expect_status=201, extra_headers={"Idempotency-Key": "ws-approval-test"}, silent=True)
    if r:
        entry_id = r["id"]
        log(f"Écriture DRAFT créée (1,500,000 HTG, > seuil 1M)", "data")
        r2 = api("POST", f"/companies/{cid}/accounting-engine/journal-entries/{entry_id}/post",
                 silent=True)
        if r2 and r2.get("status") == "PENDING_APPROVAL":
            log("✓ Écriture passée en PENDING_APPROVAL (workflow déclenché)", "ok")
            r3 = api("GET", f"/companies/{cid}/approval-workflow/requests?status=PENDING", silent=True)
            if r3:
                log(f"  {len(r3)} demande(s) d'approbation en attente", "data")
        elif r2 and r2.get("status") == "POSTED":
            log("Écriture auto-postée (montant ≤ seuil)", "data")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 17 — Opérations FX (achats USD réguliers pour imports)
# ═══════════════════════════════════════════════════════════════════════════

def step_17_fx_operations() -> None:
    section(17, "Opérations en devises (achats USD réguliers pour imports)")
    cid = COMPANY_ID

    # Taux USD/HTG historiques (déjà utilisés pour la valorisation des imports)
    rates = [
        ("USD", "HTG", USD_HTG_RATE_2024_H1, "2024-01-01", "Banque Nationale"),
        ("USD", "HTG", USD_HTG_RATE_2024_H2, "2024-07-01", "Banque Nationale"),
        ("USD", "HTG", USD_HTG_RATE_2025_H1, "2025-01-01", "Banque Nationale"),
        ("USD", "HTG", USD_HTG_RATE_2025_H2, "2025-07-01", "Banque Nationale"),
        ("EUR", "HTG", 175.0, "2024-01-01", "BCEAO"),
        ("EUR", "HTG", 185.0, "2025-01-01", "BCEAO"),
    ]
    for from_c, to_c, rate, date_, source in rates:
        api("POST", f"/companies/{cid}/fx-operations/rates", {
            "fromCurrency": from_c, "toCurrency": to_c,
            "rate": rate, "asOfDate": date_, "source": source
        }, expect_status=201, silent=True)
    log(f"{len(rates)} taux de change créés (USD/HTG + EUR/HTG)", "ok")

    # Achats USD réguliers (un par trimestre pour payer les imports)
    # NOTE sur la convention de rate : le backend valide toAmount = fromAmount × rate,
    # c'est-à-dire que rate est le taux "1 fromCurrency = rate toCurrency".
    # Pour un BUY HTG → USD au taux USD/HTG = 150 (1 USD = 150 HTG), le rate à passer
    # est l'inverse : rate = toAmount / fromAmount = usd / htg_cost = 1 / 150 = 0.006667.
    # On le calcule directement pour éviter toute confusion.
    fx_buys = [
        # (date, usd_amount, usd_htg_rate)
        ("2024-02-10", 30000, USD_HTG_RATE_2024_H1),  # 4.5M HTG pour CNT-2024-01+02
        ("2024-08-05", 35000, USD_HTG_RATE_2024_H2),  # 5.4M HTG pour CNT-2024-03+04
        ("2025-02-05", 28000, USD_HTG_RATE_2025_H1),  # 4.5M HTG pour CNT-2025-01
        ("2025-05-15", 32000, USD_HTG_RATE_2025_H1),  # 5.1M HTG pour CNT-2025-02
    ]
    buys_done = 0
    for date_, usd, usd_htg_rate in fx_buys:
        htg_cost = round(usd * usd_htg_rate)
        # rate "1 HTG = rate USD" = 1 / usd_htg_rate
        # Précision 10 décimales pour que fromAmount × rate ≈ toAmount à 0.01 près
        # (tolérance de validation backend). Le calcul exact donne 4,500,000 × (1/150) = 30,000.00
        # mais le float 1/150 = 0.0066666666... tronqué à 10 décimales = 0.0066666667
        # → 4,500,000 × 0.0066666667 = 30,000.00015 → diff 0.00015 < 0.01 ✓
        htg_to_usd_rate = round(1.0 / usd_htg_rate, 10)
        body = {
            "type": "BUY",
            "fromCurrency": "HTG", "toCurrency": "USD",
            "fromAmount": htg_cost, "toAmount": usd,
            "rate": htg_to_usd_rate, "operationDate": date_,
            "description": f"Achat {usd:,} USD pour imports conteneurs (taux USD/HTG={usd_htg_rate})",
            "bankAccountId": None
        }
        r = api("POST", f"/companies/{cid}/fx-operations", body, expect_status=201, silent=True)
        if r:
            buys_done += 1
            log(f"  Achat USD : {usd:,} USD pour {htg_cost:,} HTG (taux {usd_htg_rate}, rate={htg_to_usd_rate})", "data")
        else:
            # En cas d'échec, retry avec un taux plus précis (calcul exact via Decimal)
            from decimal import Decimal, getcontext
            getcontext().prec = 30
            htg_to_usd_rate_dec = Decimal(1) / Decimal(str(usd_htg_rate))
            body["rate"] = float(htg_to_usd_rate_dec)
            r2 = api("POST", f"/companies/{cid}/fx-operations", body, expect_status=201, silent=True)
            if r2:
                buys_done += 1
                log(f"  Achat USD : {usd:,} USD pour {htg_cost:,} HTG (taux {usd_htg_rate}, rate Decimal)", "data")
    log(f"{buys_done} achats USD réguliers (~{sum(u for _, u, _ in fx_buys):,} USD au total)", "ok")

    # Vente USD résiduelle (2025-03-10)
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "SELL",
        "fromCurrency": "USD", "toCurrency": "HTG",
        "fromAmount": 5000, "toAmount": 800000,
        "rate": 160,
        "operationDate": "2025-03-10",
        "description": "Vente USD résiduelle (excédent imports)",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        gain = r.get("fxGainLoss", 0) or 0
        gain_color = "green" if gain >= 0 else "red"
        log(f"Vente USD : 5,000 USD pour 800,000 HTG (gain {color(f'{gain:,.0f}', gain_color)} HTG)", "ok")

    # Réévaluation fin 2024
    r = api("POST", f"/companies/{cid}/fx-operations", {
        "type": "REVALUATION",
        "fromCurrency": "USD", "toCurrency": "HTG",
        "fromAmount": 1_500_000, "toAmount": 1_550_000,
        "rate": 155,
        "operationDate": "2024-12-31",
        "description": "Réévaluation fin 2024 — solde USD restant (10,000 USD)",
        "bankAccountId": None
    }, expect_status=201, silent=True)
    if r:
        log(f"Réévaluation 31/12/2024 : solde USD réévalué (taux 155)", "ok")

    # Conversion test
    r = api("GET",
        f"/companies/{cid}/fx-operations/convert?amount=10000&fromCurrency=USD&toCurrency=HTG&asOfDate=2025-06-01",
        silent=True)
    if r:
        converted = r.get("convertedAmount", 0)
        log(f"Conversion test : 10,000 USD = {color(f'{converted:,.0f}', 'cyan')} HTG", "data")

    # Liste opérations
    r = api("GET", f"/companies/{cid}/fx-operations", silent=True)
    if r:
        log(f"{len(r)} opération(s) FX au total", "ok")


# ═══════════════════════════════════════════════════════════════════════════
#  Étape 18 — Clôture d'exercice 2024
# ═══════════════════════════════════════════════════════════════════════════

def step_18_fiscal_year_close() -> None:
    section(18, "Clôture d'exercice 2024 (solde produits/charges contre compte 12)")
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
#  Étape 19 — Vérification cohérence (?fiscalYearId=)
# ═══════════════════════════════════════════════════════════════════════════

def step_19_verify_balance() -> None:
    section(19, "Vérification de cohérence — balance débit = crédit (par exercice)")
    cid = COMPANY_ID

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
    log(f"  Produits  : {color(f'{products:,.0f}', 'cyan')} HTG", "data")
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
#  Étape 20 — Exports de rapports (15 statements × 2 exercices fiscaux)
# ═══════════════════════════════════════════════════════════════════════════

def step_20_export_all_reports() -> None:
    section(20, "Exports de rapports — 15 statements × 2 exercices fiscaux")
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
        ("inventory_valuation",      "csv", None, None, None, "Valorisation des stocks CSV (2 entrepôts, snapshot)"),
        ("fixed_assets_register",    "csv", None, None, None, "Registre des immobilisations CSV (camions + équipement)"),
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

    log(f"{success}/{total_exports} exports réussis, {skipped} ignorés", "ok")


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
    print(color("  RÉCAPITULATIF — Grossiste B2B, cycle complet", "bold"))
    print(color("═" * 60, "cyan"))
    print(f"  Entreprise       : {color(COMPANY_NAME, 'cyan')}")
    print(f"  Company ID      : {color(COMPANY_ID or '?', 'cyan')}")
    print(f"  Utilisateur      : {color(USER_EMAIL, 'cyan')}")
    print(f"  Référentiel      : SYSCOHADA révisé")
    print(f"  Type métier      : WHOLESALE_COMMERCE")
    print(f"  Secteur          : COMMERCE")
    print(f"  Comptes créés    : {color(str(len(STATE.accounts)), 'cyan')}")
    print(f"  Tiers            : {color(str(len(STATE.third_parties)), 'cyan')} (8 clients B2B + 4 fournisseurs + 5 employés)")
    print(f"  Articles         : {color(str(len(STATE.items)), 'cyan')} (5 locaux HTG + 7 imports USD)")
    print(f"  Entrepôts        : {color(str(len(STATE.warehouses)), 'cyan')} (PAP + Cap-Haïtien)")
    print(f"  Exercices        : {color(str(len(STATE.fiscal_years)), 'cyan')} (2024 + 2025)")
    print(f"  Période couverte : Janvier 2024 → Juillet 2025")
    print()
    print(color("  Différences clés vs RETAIL_COMMERCE :", "bold"))
    print(f"  • Marges 12-25% (vs 100-180% en détail)")
    print(f"  • Ventes B2B par palette/carton (200-1500 unités)")
    print(f"  • Paiements 60-90 jours (vs 30 jours en détail)")
    print(f"  • Imports USD réguliers (1 conteneur/trimestre)")
    print(f"  • 2 entrepôts (PAP + Cap-Haïtien)")
    print(f"  • Capital 8M HTG + emprunt 5M (vs 3M + 2M)")
    print(f"  • 5 employés spécialisés (salaires 55k-150k)")
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
    parser = argparse.ArgumentParser(description="JOAccountant — Seed Commerce Wholesale B2B")
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
    print(f"  {color('▸', 'magenta')} Type    : {color('WHOLESALE_COMMERCE (COMMERCE)', 'cyan')}")

    try:
        step_01_register_and_login()
        step_02_create_company()
        step_03_run_wizard()
        step_04_init_chart_of_accounts()
        step_05_create_config()
        step_06_create_tax_rules()
        step_07_create_third_parties()
        step_08_create_warehouses_and_items()
        step_09_capital_and_loan()
        step_10_purchase_invoices()
        step_11_fixed_assets()
        step_12_sales_and_cogs()
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
        print(f"{color('✓', 'green')} Seed wholesale terminé avec succès !\n")
    except Exception as e:
        print(f"\n{color('✗', 'red')} Erreur : {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

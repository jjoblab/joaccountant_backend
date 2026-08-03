#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_electronics_commerce.py — Peuple le backend JOAccountant avec des données
de démonstration pour une entreprise de commerce (vente en gros et au détail
de marchandises électroniques).

Entreprise cible : "ElectroPro Distribution HT"
  - Type métier     : MIXED_COMMERCE (commerce de gros + détail)
  - Pays            : Haïti (HT)
  - Devise          : HTG (Gourde haïtienne)
  - Plan comptable  : PCN_HAITI (Plan Comptable National Haïti)
  - Exercice fiscal : 1er octobre 2025 → 30 septembre 2026

Données créées (couvrant l'exercice fiscal complet) :
  1. Utilisateur owner (register + login)
  2. Entreprise "ElectroPro Distribution HT"
  3. Wizard complet (3 étapes) avec MIXED_COMMERCE + PCN_HAITI + FY octobre
  4. ~10 tiers (clients gros + détail, fournisseurs)
  5. ~15 articles électroniques (téléviseurs, smartphones, ordinateurs, accessoires)
  6. ~5 immobilisations (camion de livraison, ordinateurs bureau, mobilier)
  7. ~40 factures de vente étalées sur 12 mois (oct 2025 → sept 2026)
     - 70% encaissées (PAID), 20% émises (ISSUED), 10% échues (OVERDUE)
  8. ~20 factures d'achat (approvisionnement stock)
  9. ~30 mouvements de stock (entrées et sorties)
 10. ~25 écritures manuelles (OD, BQ) pour salaires, loyers, charges

Usage :
    python3 seed_electronics_commerce.py [--base-url http://localhost:8080]

Pré-requis : backend joaccountant démarré en profil dev (PostgreSQL embarqué Zonky).

Idempotent : chaque exécution crée un nouvel utilisateur + entreprise avec un suffixe
timestamp pour éviter les conflits d'email uniques.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple

import requests


# ──────────────────────────────────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────────────────────────────────

DEFAULT_BASE_URL = "http://localhost:8080"

# Identifiants du plan comptable PCN_HAITI (UUID fixe en seed V1_002)
PCN_HAITI_FRAMEWORK_ID = "00000000-0000-0000-0000-000000000005"

# Exercice fiscal cible
FY_START = date(2025, 10, 1)
FY_END = date(2026, 9, 30)

# Couleurs pour logs lisibles
class C:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    CYAN = "\033[36m"


def log(level: str, msg: str, *args) -> None:
    """Affiche un message coloré selon le niveau."""
    colors = {
        "INFO": C.CYAN,
        "OK": C.GREEN,
        "WARN": C.YELLOW,
        "ERR": C.RED,
        "STEP": C.BOLD + C.MAGENTA,
        "DATA": C.DIM,
    }
    color = colors.get(level, C.RESET)
    fmt = f"{color}[{level:<4}]{C.RESET} {msg}"
    if args:
        try:
            fmt = fmt.format(*args)
        except Exception:
            pass
    print(fmt, flush=True)


# ──────────────────────────────────────────────────────────────────────────
# Client API
# ──────────────────────────────────────────────────────────────────────────

@dataclass
class ApiClient:
    base_url: str
    token: Optional[str] = None
    company_id: Optional[str] = None
    session: requests.Session = field(default_factory=requests.Session)
    request_count: int = 0
    error_count: int = 0

    def _headers(self, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
        h = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        if extra:
            h.update(extra)
        return h

    def _url(self, path: str) -> str:
        if path.startswith("http"):
            return path
        return f"{self.base_url.rstrip('/')}{path}"

    def _req(self, method: str, path: str, **kwargs) -> requests.Response:
        self.request_count += 1
        headers = self._headers(kwargs.pop("headers", None))
        url = self._url(path)
        try:
            resp = self.session.request(method, url, headers=headers, timeout=60, **kwargs)
            if resp.status_code >= 400:
                # v2.7.1 — Les 409 Conflict sont idempotents (séquences déjà existantes,
                # register déjà fait) et ne sont pas des erreurs réelles. On les compte
                # à part pour ne pas fausser le compteur d'erreurs et on les log en DATA
                # (gris discret) au lieu de ERR (rouge).
                if resp.status_code == 409:
                    # Ne pas incrémenter error_count pour les 409 idempotents
                    log("DATA", "{} {} → HTTP 409 (idempotent, déjà existant)", method, path)
                else:
                    self.error_count += 1
                    body_preview = resp.text[:300] if resp.text else ""
                    log("ERR", "{} {} → HTTP {} | {}", method, path, resp.status_code, body_preview)
            return resp
        except requests.RequestException as e:
            self.error_count += 1
            log("ERR", "{} {} → exception: {}", method, path, e)
            raise

    def get(self, path: str, **kw) -> requests.Response:
        return self._req("GET", path, **kw)

    def post(self, path: str, body: Any = None, **kw) -> requests.Response:
        kw["data"] = json.dumps(body) if body is not None else None
        return self._req("POST", path, **kw)

    def patch(self, path: str, body: Any = None, **kw) -> requests.Response:
        kw["data"] = json.dumps(body) if body is not None else None
        return self._req("PATCH", path, **kw)

    def put(self, path: str, body: Any = None, **kw) -> requests.Response:
        kw["data"] = json.dumps(body) if body is not None else None
        return self._req("PUT", path, **kw)

    def ensure_ok(self, resp: requests.Response, ctx: str) -> Dict[str, Any]:
        if resp.status_code < 200 or resp.status_code >= 300:
            try:
                err = resp.json()
            except Exception:
                err = {"raw": resp.text[:500]}
            raise RuntimeError(f"{ctx} failed (HTTP {resp.status_code}): {err}")
        if not resp.text:
            return {}
        try:
            return resp.json()
        except ValueError:
            return {"raw": resp.text}


# ──────────────────────────────────────────────────────────────────────────
# Données métier : électroménager et électronique
# ──────────────────────────────────────────────────────────────────────────

ELECTRONICS_ARTICLES: List[Dict[str, Any]] = [
    # (sku, label, unit, costing_method, reorder_threshold, unit_cost_min, unit_cost_max)
    {"sku": "TV-LED-43",   "label": "Téléviseur LED 43\" Samsung-class",       "unit": "U",  "cost": 28500,  "price": 34900,  "reorder": 5},
    {"sku": "TV-LED-55",   "label": "Téléviseur LED 55\" 4K UHD",              "unit": "U",  "cost": 41200,  "price": 51900,  "reorder": 4},
    {"sku": "TV-OLED-65",  "label": "Téléviseur OLED 65\" premium",            "unit": "U",  "cost": 86500,  "price": 109000, "reorder": 2},
    {"sku": "PHN-AND-128", "label": "Smartphone Android 128 Go",               "unit": "U",  "cost": 15800,  "price": 21500,  "reorder": 10},
    {"sku": "PHN-AND-256", "label": "Smartphone Android 256 Go haut de gamme", "unit": "U",  "cost": 24700,  "price": 32900,  "reorder": 8},
    {"sku": "PHN-IOS-128", "label": "Smartphone iOS 128 Go",                   "unit": "U",  "cost": 48200,  "price": 61900,  "reorder": 5},
    {"sku": "LAP-I5-512",  "label": "Ordinateur portable i5 512 Go SSD",       "unit": "U",  "cost": 38900,  "price": 48900,  "reorder": 4},
    {"sku": "LAP-I7-1TB",  "label": "Ordinateur portable i7 1 To SSD",         "unit": "U",  "cost": 62500,  "price": 78900,  "reorder": 3},
    {"sku": "DSK-I5-256",  "label": "Ordinateur de bureau i5 256 Go",          "unit": "U",  "cost": 31800,  "price": 39900,  "reorder": 3},
    {"sku": "TAB-AND-64",  "label": "Tablette Android 64 Go 10\"",             "unit": "U",  "cost": 12700,  "price": 16900,  "reorder": 6},
    {"sku": "TAB-IOS-256", "label": "Tablette iOS 256 Go 11\"",                "unit": "U",  "cost": 45800,  "price": 58900,  "reorder": 4},
    {"sku": "AUD-HP-BT",   "label": "Casque audio Bluetooth ANC",              "unit": "U",  "cost": 4200,   "price": 6900,   "reorder": 15},
    {"sku": "AUD-SPK-MINI","label": "Enceinte Bluetooth portable mini",        "unit": "U",  "cost": 2800,   "price": 4500,   "reorder": 20},
    {"sku": "ACC-CHRG-30W","label": "Chargeur rapide USB-C 30W",               "unit": "U",  "cost": 850,    "price": 1900,   "reorder": 50},
    {"sku": "ACC-CBL-HDMI","label": "Câble HDMI 2.0 2m",                       "unit": "U",  "cost": 320,    "price": 950,    "reorder": 100},
    {"sku": "ACC-WEBCAM",  "label": "Webcam HD 1080p USB",                     "unit": "U",  "cost": 2400,   "price": 3900,   "reorder": 10},
    {"sku": "STR-SSD-500", "label": "SSD 500 Go SATA",                         "unit": "U",  "cost": 5800,   "price": 8200,   "reorder": 8},
    {"sku": "STR-HDD-2TB", "label": "Disque dur 2 To 3.5\"",                   "unit": "U",  "cost": 7400,   "price": 10500,  "reorder": 6},
    {"sku": "NET-RTR-AC",  "label": "Routeur Wi-Fi AC1750",                    "unit": "U",  "cost": 3900,   "price": 5900,   "reorder": 8},
    {"sku": "PWR-UPS-1KVA","label": "Onduleur UPS 1 kVA",                      "unit": "U",  "cost": 11500,  "price": 15500,  "reorder": 3},
]

CLIENTS_RETAIL: List[Dict[str, Any]] = [
    # Clients détail (particuliers / petites entreprises)
    {"name": "Jean-Robert Pierre",          "email": "jrpierre@example.ht",  "address": "Delmas 33, Port-au-Prince",     "type": "CLIENT"},
    {"name": "Marie-Carmel Joseph",         "email": "mcjoseph@example.ht",  "address": "Pétion-Ville, Rue Lamarre",     "type": "CLIENT"},
    {"name": "Boutique Electro Express",    "email": "electro.exp@example.ht","address": "Carrefour, Route de l'Aéroport","type": "CLIENT"},
    {"name": "Clinique Médicale du Bord",   "email": "cmbord@example.ht",    "address": "Bourg-de-Port, Cap-Haïtien",    "type": "CLIENT"},
    {"name": "École Mixte Le Bon Berger",   "email": "bonberger@example.ht","address": "Saint-Marc, Artibonite",        "type": "CLIENT"},
    {"name": "Wilson Télécom",              "email": "wilsontel@example.ht", "address": "Gonaïves, Rue Dumas",           "type": "CLIENT"},
    {"name": "Hôpital Sainte-Thérèse",      "email": "hstherese@example.ht", "address": "Port-au-Prince, Tabarre",       "type": "CLIENT"},
]

CLIENTS_WHOLESALE: List[Dict[str, Any]] = [
    # Clients gros (revendeurs, grossistes régionaux)
    {"name": "Distributeur Nord SA",        "email": "distnord@example.ht",   "address": "Cap-Haïtien, Zone Industrielle","type": "CLIENT"},
    {"name": "Grossiste Electro Sud",       "email": "elecsud@example.ht",    "address": "Les Cayes, Rue Pavée",          "type": "CLIENT"},
    {"name": "Importex Haïti",              "email": "importex@example.ht",   "address": "Port-au-Prince, Varreux",      "type": "CLIENT"},
    {"name": "Comptoir Électronique Centre","email": "comptoir@example.ht",   "address": "Hinche, Centre",                "type": "CLIENT"},
    {"name": "Techno Distribution Artib.",  "email": "technodis@example.ht",  "address": "Gonaïves, Avenue Toussaint",    "type": "CLIENT"},
]

SUPPLIERS: List[Dict[str, Any]] = [
    # Fournisseurs (généralement importateurs depuis USA, Chine, République Dominicaine)
    {"name": "Shenzhen Electro Import Co.",  "email": "sales@shenzhen-electro.cn", "address": "Shenzhen, Guangdong, China",       "type": "SUPPLIER"},
    {"name": "Miami Tech Wholesale Inc.",    "email": "orders@miamitechwholesale.com","address": "Miami, FL 33126, USA",          "type": "SUPPLIER"},
    {"name": "Santo Domingo Electronics",    "email": "ventas@sdelc.do",           "address": "Santo Domingo, República Dominicana","type": "SUPPLIER"},
    {"name": "Panama Tech Distributors",     "email": "info@panamatech.pa",        "address": "Colon Free Zone, Panama",         "type": "SUPPLIER"},
    {"name": "Distribuidora Caribbean SA",   "email": "caribdis@dr.com",           "address": "Santiago de los Caballeros, RD", "type": "SUPPLIER"},
]

FIXED_ASSETS: List[Dict[str, Any]] = [
    # Immobilisations pour le fonctionnement de l'entreprise
    {"label": "Camion Isuzu NPR de livraison", "cost": 1850000, "useful_life_months": 60,  "residual": 250000},
    {"label": "Fourgon Toyota Hiace",          "cost": 1450000, "useful_life_months": 60,  "residual": 200000},
    {"label": "Ordinateur de bureau comptable","cost": 55000,   "useful_life_months": 36,  "residual": 5000},
    {"label": "Serveur et onduleur bureau",    "cost": 95000,   "useful_life_months": 48,  "residual": 8000},
    {"label": "Mobilier de bureau (bureaux)",  "cost": 75000,   "useful_life_months": 120, "residual": 10000},
    {"label": "Climatiseurs entrepôt",         "cost": 120000,  "useful_life_months": 84,  "residual": 12000},
]


# ──────────────────────────────────────────────────────────────────────────
# Étapes du script
# ──────────────────────────────────────────────────────────────────────────

def step_register_user(api: ApiClient, suffix: str) -> Tuple[str, str]:
    """Crée un utilisateur owner et renvoie (userId, email)."""
    email = f"electropro.owner.{suffix}@example.ht"
    password = "ElectroPro#2026"
    log("STEP", "1/10 — Création de l'utilisateur owner")
    log("INFO", "Email: {}", email)
    body = {
        "email": email,
        "password": password,
        "fullName": "ElectroPro Owner",
        "locale": "fr",
    }
    resp = api.post("/api/v1/auth/register", body)
    data = api.ensure_ok(resp, "register user")
    # Le register peut renvoyer directement un accessToken (si auto-login) ou juste userId
    token = data.get("accessToken")
    if token:
        api.token = token
        log("OK", "Utilisateur créé + auto-login (userId={}, token={} chars)",
            data.get("userId"), len(token))
    else:
        log("OK", "Utilisateur créé : userId={}", data.get("userId"))
    return email, password


def step_login(api: ApiClient, email: str, password: str) -> None:
    """Authentifie et stocke le JWT (skip si déjà authentifié par register)."""
    if api.token:
        log("STEP", "2/10 — Authentification (login) — déjà authentifié via register, skip")
        return
    log("STEP", "2/10 — Authentification (login)")
    body = {"email": email, "password": password}
    resp = api.post("/api/v1/auth/login", body)
    data = api.ensure_ok(resp, "login")
    token = data.get("accessToken") or data.get("access_token")
    if not token:
        raise RuntimeError(f"No access token in login response: {data}")
    api.token = token
    log("OK", "JWT obtenu ({} chars)", len(token))


def step_create_company(api: ApiClient) -> str:
    """Crée l'entreprise et renvoie son ID."""
    log("STEP", "3/10 — Création de l'entreprise 'ElectroPro Distribution HT'")
    body = {
        "name": "ElectroPro Distribution HT",
        "country": "HT",
        "functionalCurrency": "HTG",
        "organizationNature": "FOR_PROFIT",
        "legalForm": "SA",
    }
    resp = api.post("/api/v1/companies", body)
    data = api.ensure_ok(resp, "create company")
    # La réponse peut être {"id": "..."} (CreateCompanyResponse directe)
    # ou {"company": {"id": "..."}, "accessToken": "..."} (wrapper avec auto-login)
    company_id = data.get("id")
    if not company_id:
        company_obj = data.get("company")
        if isinstance(company_obj, dict):
            company_id = company_obj.get("id")
    if not company_id:
        raise RuntimeError(f"No company id in response: {data}")
    api.company_id = company_id
    # v2.7.0 — Le backend renvoie un NOUVEAU accessToken après création de company
    # (le token contient désormais la company dans les claims "companies").
    # Sans ce refresh, les requêtes suivantes sur /companies/{id}/* échouent en 404
    # car le token original a companies=[] (vide).
    new_token = data.get("accessToken")
    if new_token:
        api.token = new_token
        log("OK", "Entreprise créée : companyId={} (JWT rafraîchi avec claim company)",
            company_id)
    else:
        log("OK", "Entreprise créée : companyId={}", company_id)
        log("WARN", "Pas d'accessToken dans la réponse — re-login nécessaire pour rafraîchir les claims")
        # Re-login pour obtenir un token avec la company dans les claims
        # (le backend ajoute la company au user au moment du create, mais le token
        # existant n'est pas invalidé — il faut en demander un nouveau)
        # Note : le script appelle déjà step_login avant, mais le token était expiré
        # au sens des claims. On le re-demande explicitement ici.
        # (Si l'API ne renvoie pas de token à la création, on doit faire un nouveau login.)

    # Mise à jour des champs légaux (NIF, adresse)
    legal_body = {
        "nif": "HT2018-98765-E",
        "address": "Varreux 1, Rue des Industries, Port-au-Prince, Haïti",
    }
    resp = api.patch(f"/api/v1/companies/{company_id}/legal", legal_body)
    if resp.status_code < 300:
        log("OK", "Champs légaux (NIF, adresse) mis à jour")
    else:
        log("WARN", "Mise à jour champs légaux ignorée : HTTP {}", resp.status_code)
    return company_id


def step_wizard_complete(api: ApiClient, company_id: str) -> None:
    """Complète le wizard en 3 étapes : business type, framework, fiscal year."""
    log("STEP", "4/10 — Complétion du wizard (3 étapes)")

    # Étape 2 : business type MIXED_COMMERCE + activité principale
    log("INFO", "Wizard étape 2 : businessType=MIXED_COMMERCE")
    step2_body = {
        "primaryActivityLabel": "Vente en gros et au détail de marchandises électroniques (téléviseurs, smartphones, ordinateurs, accessoires)",
        "businessTypeCode": "MIXED_COMMERCE",
        "sector": "COMMERCE",
        "extraAttributes": {},
        "customModules": [],
    }
    resp = api.patch(f"/api/v1/companies/{company_id}/wizard/2", step2_body)
    api.ensure_ok(resp, "wizard step 2")
    log("OK", "Wizard étape 2 OK")

    # Étape 3 : framework PCN_HAITI + exercice fiscal octobre→septembre + TVA débit
    log("INFO", "Wizard étape 3 : PCN_HAITI + FY octobre + TVA débit")
    step3_body = {
        "accountingFrameworkId": PCN_HAITI_FRAMEWORK_ID,
        "fiscalYearStartMonth": 10,
        "fiscalYearStartYear": 2025,
        "fiscalYearLabel": "Exercice 2025-2026 (oct 2025 → sept 2026)",
        "vatMode": "DEBIT",
        "numberingPrefixes": {
            "SALES_INVOICE": "FAC",
            "JOURNAL_ENTRY": "EC",
            "PURCHASE_INVOICE": "FA",
        },
    }
    resp = api.patch(f"/api/v1/companies/{company_id}/wizard/3", step3_body)
    api.ensure_ok(resp, "wizard step 3")
    log("OK", "Wizard étape 3 OK")

    # Complétion atomique (modules + plan comptable + exercice + séquences)
    log("INFO", "Wizard completion : POST /wizard/complete")
    complete_body = {
        "mfaCode": None,
        "expenseCategories": [],
        "contributionRules": [],
    }
    resp = api.post(f"/api/v1/companies/{company_id}/wizard/complete", complete_body)
    if resp.status_code >= 400:
        try:
            err = resp.json()
            if "already" in str(err).lower() or resp.status_code == 409:
                log("WARN", "Wizard déjà complété (409) — on continue")
            else:
                raise RuntimeError(f"wizard/complete failed (HTTP {resp.status_code}): {err}")
        except ValueError:
            raise RuntimeError(f"wizard/complete failed (HTTP {resp.status_code}): {resp.text[:300]}")
    else:
        log("OK", "Wizard complété — modules + plan comptable + exercice + séquences actifs")

    # v2.7.0 — Création explicite des séquences documentaires si le wizard ne les a pas créées
    # (le wizard crée SALES_INVOICE/"" par défaut, mais le backend invoicing utilise
    # scopeKey="VT" pour les ventes — il faut donc créer SALES_INVOICE/VT explicitement).
    #
    # v2.7.1 (2026-08-02) : le scopeKey="" est rejeté en 422 par certains backends (Render)
    # car @NotBlank est appliqué strictement. On utilise "DEFAULT" comme scope par défaut
    # pour les types qui n'ont pas de scope naturel (CREDIT_NOTE, PURCHASE_INVOICE, etc.).
    # Pour SALES_INVOICE/JOURNAL_ENTRY, on garde les scopes métier (VT, OD, AC, BQ).
    _ensure_sequence(api, company_id, "SALES_INVOICE", "VT", "FAC")
    _ensure_sequence(api, company_id, "SALES_INVOICE", "DEFAULT", "FAC")
    _ensure_sequence(api, company_id, "JOURNAL_ENTRY", "OD", "EC")
    _ensure_sequence(api, company_id, "JOURNAL_ENTRY", "VT", "EC")
    _ensure_sequence(api, company_id, "JOURNAL_ENTRY", "AC", "AC")
    _ensure_sequence(api, company_id, "JOURNAL_ENTRY", "BQ", "BQ")
    _ensure_sequence(api, company_id, "JOURNAL_ENTRY", "PA", "PA")  # v2.7.2 — journal paie
    _ensure_sequence(api, company_id, "JOURNAL_ENTRY", "DEFAULT", "EC")
    _ensure_sequence(api, company_id, "CREDIT_NOTE", "DEFAULT", "AV")
    # v2.7.2 — PURCHASE_INVOICE/AC requis par le backend purchasing pour receive
    _ensure_sequence(api, company_id, "PURCHASE_INVOICE", "AC", "FA")
    _ensure_sequence(api, company_id, "PURCHASE_INVOICE", "DEFAULT", "FA")
    # v2.7.2 — PAYSLIP/PA requis par le module payroll pour générer les bulletins
    _ensure_sequence(api, company_id, "PAYSLIP", "PA", "BUL")


def _ensure_sequence(api: ApiClient, company_id: str, doc_type: str, scope_key: str, prefix: str) -> None:
    """Crée une séquence documentaire si elle n'existe pas déjà (idempotent).

    États gérés :
      - 201 Created → nouvelle séquence créée → log OK
      - 409 Conflict → séquence déjà existante → silencieux (idempotent)
      - 422 Validation Error → log WARN (ex: scopeKey blank sur backend strict)
      - Autre → log WARN
    """
    body = {
        "documentType": doc_type,
        "scopeKey": scope_key,
        "prefix": prefix,
        "includeYear": True,
        "padding": 6,
        "resetPolicy": "YEARLY",
    }
    resp = api.post(f"/api/v1/companies/{company_id}/document-numbering/sequences", body)
    if resp.status_code == 201:
        log("OK", "Séquence {} / '{}' créée (prefix={})", doc_type, scope_key, prefix)
    elif resp.status_code == 409:
        # Déjà existante — c'est OK, silencieux pour ne pas polluer le log
        log("DATA", "Séquence {} / '{}' déjà existante (409, idempotent)", doc_type, scope_key)
    else:
        log("WARN", "Séquence {} / '{}' : HTTP {} (ignoré)", doc_type, scope_key, resp.status_code)


def step_get_accounts(api: ApiClient, company_id: str) -> Dict[str, Dict[str, Any]]:
    """Récupère le plan comptable et indexe par code pour lookup rapide."""
    log("STEP", "5/10 — Récupération du plan comptable PCN_HAITI")
    resp = api.get(f"/api/v1/companies/{company_id}/chart-of-accounts")
    if resp.status_code >= 400:
        log("WARN", "Chart of accounts non récupéré (HTTP {}) — fallback sur codes standard PCN", resp.status_code)
        return {}
    data = api.ensure_ok(resp, "list accounts")
    accounts = data if isinstance(data, list) else data.get("content", data.get("accounts", []))
    by_code: Dict[str, Dict[str, Any]] = {}
    for acc in accounts:
        code = str(acc.get("code", "")).strip()
        if code:
            by_code[code] = acc
    log("OK", "{} comptes chargés", len(by_code))
    # Affiche un échantillon des codes clés
    key_codes = ["101", "401", "411", "521", "601", "605", "701", "702", "331", "244", "2844", "631"]
    sample = [f"{c}={by_code[c]['id']}" for c in key_codes if c in by_code]
    if sample:
        log("DATA", "Comptes clés trouvés : {}", ", ".join(sample[:6]))
    return by_code


def _pick_account(accounts: Dict[str, Dict[str, Any]], *preferred_codes: str) -> Optional[str]:
    """Renvoie l'UUID du premier compte trouvé dans la liste de codes préférés."""
    for code in preferred_codes:
        # Recherche exacte puis par préfixe
        if code in accounts:
            return accounts[code].get("id")
        # Préfixe : ex "411" peut matcher "411000"
        for k, v in accounts.items():
            if k.startswith(code):
                return v.get("id")
    return None


def step_create_third_parties(api: ApiClient, company_id: str) -> Dict[str, List[Dict[str, Any]]]:
    """Crée clients (gros + détail) et fournisseurs."""
    log("STEP", "6/10 — Création des tiers (clients + fournisseurs)")
    created: Dict[str, List[Dict[str, Any]]] = {"clients": [], "suppliers": []}

    all_clients = CLIENTS_RETAIL + CLIENTS_WHOLESALE
    for tp in all_clients:
        body = {
            "type": tp["type"],
            "name": tp["name"],
            "email": tp.get("email"),
            "address": tp.get("address"),
            "collectiveAccountId": None,  # auto-résolu par le backend selon le type
        }
        resp = api.post(f"/api/v1/companies/{company_id}/third-parties", body)
        if resp.status_code >= 400:
            log("WARN", "Tiers '{}' non créé : HTTP {}", tp["name"], resp.status_code)
            continue
        data = api.ensure_ok(resp, f"create third-party {tp['name']}")
        tp_created = dict(tp)
        tp_created["id"] = data.get("id")
        created["clients"].append(tp_created)
    log("OK", "{} clients créés (gros + détail)", len(created["clients"]))

    for sup in SUPPLIERS:
        body = {
            "type": "SUPPLIER",
            "name": sup["name"],
            "email": sup.get("email"),
            "address": sup.get("address"),
            "collectiveAccountId": None,
        }
        resp = api.post(f"/api/v1/companies/{company_id}/third-parties", body)
        if resp.status_code >= 400:
            log("WARN", "Fournisseur '{}' non créé : HTTP {}", sup["name"], resp.status_code)
            continue
        data = api.ensure_ok(resp, f"create supplier {sup['name']}")
        sup_created = dict(sup)
        sup_created["id"] = data.get("id")
        created["suppliers"].append(sup_created)
    log("OK", "{} fournisseurs créés", len(created["suppliers"]))
    return created


def step_create_inventory_items(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Crée les articles de stock électroniques."""
    log("STEP", "7/10 — Création des articles de stock (électronique)")

    # Comptes PCN_HAITI :
    #  - Stock de marchandises : 331 (ou 30/31 selon secteur)
    #  - Variation de stock (COGS) : 603 (ou 601/602 pour achats)
    inventory_account_id = _pick_account(accounts, "331", "33", "30", "31")
    cogs_account_id = _pick_account(accounts, "603", "601", "602", "60")

    if not inventory_account_id or not cogs_account_id:
        log("WARN", "Comptes stock/COGS non trouvés dans le plan — utilisation des premiers comptes disponibles")
        # Fallback : prendre le 1er compte ACTIF et le 1er compte CHARGES
        for code, acc in accounts.items():
            if acc.get("reportingClass") == "ACTIF" and not inventory_account_id:
                inventory_account_id = acc.get("id")
            if acc.get("reportingClass") == "CHARGES" and not cogs_account_id:
                cogs_account_id = acc.get("id")
            if inventory_account_id and cogs_account_id:
                break

    if not inventory_account_id or not cogs_account_id:
        log("ERR", "Impossible de trouver des comptes pour stock/COGS — skip inventory")
        return []

    created: List[Dict[str, Any]] = []
    for art in ELECTRONICS_ARTICLES:
        body = {
            "sku": art["sku"],
            "label": art["label"],
            "unitOfMeasure": art["unit"],
            "costingMethod": "FIFO",
            "reorderThreshold": art["reorder"],
            "inventoryAccountId": inventory_account_id,
            "cogsAccountId": cogs_account_id,
        }
        resp = api.post(f"/api/v1/companies/{company_id}/inventory/items", body)
        if resp.status_code >= 400:
            log("WARN", "Article '{}' non créé : HTTP {}", art["sku"], resp.status_code)
            continue
        data = api.ensure_ok(resp, f"create item {art['sku']}")
        art_created = dict(art)
        art_created["id"] = data.get("id")
        created.append(art_created)
    log("OK", "{} articles créés", len(created))
    return created


def step_create_fixed_assets(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Crée les immobilisations."""
    log("STEP", "8/10 — Création des immobilisations")

    asset_account_id = _pick_account(accounts, "244", "24", "22", "23")
    depreciation_account_id = _pick_account(accounts, "631", "630", "681", "63")
    accumulated_account_id = _pick_account(accounts, "2844", "284", "28", "281")

    if not asset_account_id or not depreciation_account_id or not accumulated_account_id:
        log("WARN", "Comptes immobilisations non trouvés — skip fixed assets")
        return []

    created: List[Dict[str, Any]] = []
    acquisition_date = FY_START + timedelta(days=15)  # 15 oct 2025
    for asset in FIXED_ASSETS:
        body = {
            "label": asset["label"],
            "acquisitionDate": acquisition_date.isoformat(),
            "acquisitionCost": asset["cost"],
            "usefulLifeMonths": asset["useful_life_months"],
            "residualValue": asset.get("residual", 0),
            "depreciationMethod": "STRAIGHT_LINE",
            "assetAccountId": asset_account_id,
            "depreciationExpenseAccountId": depreciation_account_id,
            "accumulatedDepreciationAccountId": accumulated_account_id,
            "supplierAccountId": None,
            "cashAccountId": _pick_account(accounts, "521", "52", "57"),
        }
        resp = api.post(f"/api/v1/companies/{company_id}/fixed-assets", body)
        if resp.status_code >= 400:
            log("WARN", "Immobilisation '{}' non créée : HTTP {}", asset["label"], resp.status_code)
            continue
        data = api.ensure_ok(resp, f"create fixed asset {asset['label']}")
        asset_created = dict(asset)
        asset_created["id"] = data.get("id")
        created.append(asset_created)
    log("OK", "{} immobilisations créées", len(created))
    return created


def _random_date_in_fy(rng: random.Random, start: date = FY_START, end: date = FY_END) -> date:
    """Retourne une date aléatoire dans l'exercice fiscal."""
    delta = (end - start).days
    return start + timedelta(days=rng.randint(0, delta))


def step_create_invoices(
    api: ApiClient,
    company_id: str,
    clients: List[Dict[str, Any]],
    articles: List[Dict[str, Any]],
    rng: random.Random,
    n_invoices: int = 40,
) -> int:
    """Crée des factures de vente étalées sur l'exercice fiscal."""
    log("STEP", "9/10 — Création de {} factures de vente sur l'exercice", n_invoices)

    if not clients or not articles:
        log("WARN", "Pas de clients ou d'articles — skip invoices")
        return 0

    created_count = 0
    issued_count = 0
    paid_count = 0
    error_count_local = 0

    for i in range(n_invoices):
        client = rng.choice(clients)
        issue_date = _random_date_in_fy(rng)
        due_date = issue_date + timedelta(days=rng.choice([15, 30, 45, 60]))

        # 1 à 5 lignes par facture
        n_lines = rng.randint(1, 5)
        chosen_articles = rng.sample(articles, min(n_lines, len(articles)))
        lines = []
        for art in chosen_articles:
            # Pour clients gros : remise sur volume
            is_wholesale = client.get("name") in [c["name"] for c in CLIENTS_WHOLESALE]
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
            "type": "STANDARD",  # InvoiceType enum: STANDARD | CREDIT_NOTE (pas SALES_INVOICE)
            "issueDate": issue_date.isoformat(),
            "dueDate": due_date.isoformat(),
            "currency": "HTG",
            "lines": lines,
            "creditNoteForInvoiceId": None,
        }

        # Idempotency-Key pour éviter doublons
        idem_key = f"seed-inv-{company_id[:8]}-{i+1:03d}-{int(time.time())}"
        # v9.4 fix — Le backend a unifié SALES et PURCHASE sous /invoices avec query param ?direction=.
        # Ancien path /invoicing/invoices → 404 "No static resource".
        # Default direction=SALES, donc pas besoin de query param pour les factures de vente.
        resp = api.post(
            f"/api/v1/companies/{company_id}/invoices",
            body,
            headers={"Idempotency-Key": idem_key},
        )

        if resp.status_code >= 400:
            error_count_local += 1
            if error_count_local <= 3:
                log("WARN", "Facture #{} non créée : HTTP {} (body={})", i + 1, resp.status_code, resp.text[:200])
            continue

        try:
            inv_data = resp.json()
        except ValueError:
            inv_data = {}
        inv_id = inv_data.get("id")
        if not inv_id:
            log("WARN", "Facture #{} sans id dans la réponse", i + 1)
            continue
        # Récupère le solde dû pour le paiement (si présent dans la réponse)
        balance_due = inv_data.get("balanceDue") or inv_data.get("totalAmount") or 0
        created_count += 1

        # Workflow aléatoire : 70% PAID, 20% ISSUED, 10% OVERDUE (laisser en DRAFT rarement)
        # Pour les factures passées (>30 jours) → majorité payées
        days_ago = (date.today() - issue_date).days
        if days_ago > 30:
            roll = rng.random()
            if roll < 0.75:
                # Issue + mark paid
                if _try_issue(api, company_id, inv_id):
                    if _try_mark_paid(api, company_id, inv_id, balance_due):
                        paid_count += 1
                    else:
                        issued_count += 1  # issue réussi mais payment échoué
            elif roll < 0.95:
                if _try_issue(api, company_id, inv_id):
                    issued_count += 1
            # sinon : reste DRAFT
        else:
            # Récentes : juste issue si échéance proche
            if rng.random() < 0.5:
                if _try_issue(api, company_id, inv_id):
                    issued_count += 1

        if created_count % 10 == 0:
            log("INFO", "{}/{} factures traitées...", created_count, n_invoices)

    log("OK", "{} factures créées (paid={}, issued={}, draft/other={})",
        created_count, paid_count, issued_count, created_count - paid_count - issued_count)
    return created_count


def _try_issue(api: ApiClient, company_id: str, invoice_id: str) -> bool:
    """Tente d'émettre la facture (DRAFT → ISSUED). Tolère les erreurs."""
    # v9.4 fix — /invoicing/invoices → /invoices (unification v9.0).
    # L'endpoint /issue gère aussi bien SALES (issue) que PURCHASE (receive) côté backend.
    resp = api.post(f"/api/v1/companies/{company_id}/invoices/{invoice_id}/issue")
    return resp.status_code < 300


def _try_mark_paid(api: ApiClient, company_id: str, invoice_id: str, balance_due: float = 0) -> bool:
    """Tente de marquer la facture comme payée.

    v9.4 fix — /invoicing/invoices → /invoices (unification v9.0).
    Le backend expose /invoices/{id}/record-payment (pas /payments) avec un body {amount}.

    Le backend exige amount > 0 ET amount <= balanceDue (validation payment_exceeds_total).
    On envoie le montant exact du solde dû récupéré depuis la réponse de création
    de l'invoice. Si balance_due est 0 ou inconnu, on tente un montant minimal de 1 HTG.
    """
    # Assure un montant valide (> 0). Si balance_due est 0 ou négatif, on met 1.
    amount = balance_due if balance_due and balance_due > 0 else 1
    body = {"amount": amount}
    resp = api.post(
        f"/api/v1/companies/{company_id}/invoices/{invoice_id}/record-payment",
        body,
    )
    return resp.status_code < 300


def step_create_journal_entries(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
    rng: random.Random,
    n_entries: int = 25,
) -> int:
    """Crée des écritures manuelles (OD, BQ) pour salaires, loyers, charges diverses."""
    log("STEP", "10/10 — Création de {} écritures manuelles (salaires, loyers, charges)", n_entries)

    if not accounts:
        log("WARN", "Plan comptable vide — skip journal entries")
        return 0

    # Recherche des comptes nécessaires
    cash_account = _pick_account(accounts, "521", "52", "57")  # Banque
    expense_salary = _pick_account(accounts, "661", "66")  # Rémunérations
    expense_rent = _pick_account(accounts, "621", "62", "613")  # Loyers
    expense_utilities = _pick_account(accounts, "625", "623", "626")  # Eau/électricité
    expense_other = _pick_account(accounts, "628", "627", "658", "67")  # Autres charges
    expense_marketing = _pick_account(accounts, "641", "6235", "62")  # Publicité

    # Templates d'écritures
    templates = []

    if expense_salary and cash_account:
        templates.append({
            "journalCode": "OD",
            "description": "Salaires du personnel",
            "amount_range": (45000, 85000),
            "debit_account": expense_salary,
            "credit_account": cash_account,
        })
    if expense_rent and cash_account:
        templates.append({
            "journalCode": "OD",
            "description": "Loyer entrepôt Varreux",
            "amount_range": (25000, 35000),
            "debit_account": expense_rent,
            "credit_account": cash_account,
        })
    if expense_utilities and cash_account:
        templates.append({
            "journalCode": "BQ",
            "description": "Facture électricité EDH",
            "amount_range": (8000, 22000),
            "debit_account": expense_utilities,
            "credit_account": cash_account,
        })
    if expense_marketing and cash_account:
        templates.append({
            "journalCode": "OD",
            "description": "Campagne publicitaire radio/affichage",
            "amount_range": (5000, 18000),
            "debit_account": expense_marketing,
            "credit_account": cash_account,
        })
    if expense_other and cash_account:
        templates.append({
            "journalCode": "OD",
            "description": "Charges diverses (transport, fournitures)",
            "amount_range": (2000, 12000),
            "debit_account": expense_other,
            "credit_account": cash_account,
        })

    if not templates:
        log("WARN", "Aucun template d'écriture exploitable — skip")
        return 0

    created_count = 0
    for i in range(n_entries):
        tpl = rng.choice(templates)
        amount = round(rng.uniform(*tpl["amount_range"]), 2)
        entry_date = _random_date_in_fy(rng)

        # Lookup du code du compte débit (depuis accounts)
        debit_code = None
        credit_code = None
        for code, acc in accounts.items():
            if acc.get("id") == tpl["debit_account"]:
                debit_code = code
            if acc.get("id") == tpl["credit_account"]:
                credit_code = code
        if not debit_code or not credit_code:
            continue

        body = {
            "journalCode": tpl["journalCode"],
            "entryDate": entry_date.isoformat(),
            "description": f"{tpl['description']} — {entry_date.strftime('%B %Y')}",
            "lines": [
                {
                    "accountCode": debit_code,
                    "thirdPartyId": None,
                    "debit": amount,
                    "credit": 0,
                    "description": tpl["description"],
                },
                {
                    "accountCode": credit_code,
                    "thirdPartyId": None,
                    "debit": 0,
                    "credit": amount,
                    "description": "Contrepartie",
                },
            ],
            "sourceModule": "MANUAL",
        }

        idem_key = f"seed-od-{company_id[:8]}-{i+1:03d}-{int(time.time())}"
        resp = api.post(
            f"/api/v1/companies/{company_id}/accounting-engine/journal-entries",
            body,
            headers={"Idempotency-Key": idem_key},
        )
        if resp.status_code >= 400:
            if created_count == 0 and i < 3:
                log("WARN", "Écriture #{} non créée : HTTP {} (body={})", i + 1, resp.status_code, resp.text[:200])
            continue
        created_count += 1

    log("OK", "{} écritures manuelles créées", created_count)
    return created_count


# ──────────────────────────────────────────────────────────────────────────
# v2.7.2 — Modules étendus : Capital d'ouverture, Purchasing, PO, Expenses, Payroll
# ──────────────────────────────────────────────────────────────────────────

def step_create_capital_opening(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
    rng: random.Random,
) -> int:
    """Crée l'écriture de capital d'ouverture (à nouveaux) au 01/10/2025.

    <p>Cette écriture AN (À Nouveaux) matérialise l'apport en capital des associés
    + le dépôt initial en banque + le stock initial + les immobilisations existantes
    au démarrage de l'exercice.

    <p>Écriture :
      Débit : 521 Banque (apport en capital libéré)
      Débit : 331 Stock de marchandises (stock initial)
      Débit : 244 Matériel de transport (camion existant)
      Crédit : 101 Capital social
    """
    log("STEP", "11/16 — Capital d'ouverture (écriture à nouveaux au 01/10/2025)")

    if not accounts:
        log("WARN", "Plan comptable vide — skip capital d'ouverture")
        return 0

    # Comptes nécessaires
    bank_account = _pick_account(accounts, "521", "52", "57")
    stock_account = _pick_account(accounts, "331", "33", "30", "31")
    vehicle_account = _pick_account(accounts, "244", "24", "22", "23")
    capital_account = _pick_account(accounts, "101", "10")

    if not bank_account or not capital_account:
        log("WARN", "Comptes capital/banque non trouvés — skip capital d'ouverture")
        return 0

    # Lookup codes
    def _code_of(acc_id):
        for code, acc in accounts.items():
            if acc.get("id") == acc_id:
                return code
        return None

    bank_code = _code_of(bank_account)
    stock_code = _code_of(stock_account) if stock_account else None
    vehicle_code = _code_of(vehicle_account) if vehicle_account else None
    capital_code = _code_of(capital_account)

    # Montants
    capital_total = 5_000_000  # 5M HTG de capital social
    bank_deposit = 3_500_000   # 3.5M déposés en banque
    stock_initial = 1_000_000  # 1M de stock initial
    vehicle_existing = 500_000  # 500k de véhicule existant

    lines = [
        {"accountCode": bank_code, "thirdPartyId": None,
         "debit": bank_deposit, "credit": 0,
         "description": "Dépôt initial en banque (apport en capital libéré)"},
    ]
    if stock_code:
        lines.append({"accountCode": stock_code, "thirdPartyId": None,
                      "debit": stock_initial, "credit": 0,
                      "description": "Stock initial de marchandises électroniques"})
    if vehicle_code:
        lines.append({"accountCode": vehicle_code, "thirdPartyId": None,
                      "debit": vehicle_existing, "credit": 0,
                      "description": "Camion de livraison existant au démarrage"})
    lines.append({"accountCode": capital_code, "thirdPartyId": None,
                  "debit": 0, "credit": capital_total,
                  "description": "Capital social (apports des associés)"})

    body = {
        "journalCode": "OD",  # v9.4 fix — Le wizard ne crée que VT/AC/BQ/CA/OD/PA/DP/FX (pas AN).
        # Le journal OD (Opérations Diverses) est utilisé pour l'écriture d'ouverture car le backend
        # rejette le code AN avec « Journal introuvable : AN (et ne correspond à aucun JournalType standard) ».
        # Voir AccountingProvisioningPortImpl.createDefaultJournals (lignes 72-79).
        "entryDate": FY_START.isoformat(),  # 01/10/2025
        "description": "Écriture d'ouverture — Capital social + apports initiaux",
        "lines": lines,
        "sourceModule": "MANUAL",
    }

    idem_key = f"seed-capital-{company_id[:8]}-{int(time.time())}"
    resp = api.post(
        f"/api/v1/companies/{company_id}/accounting-engine/journal-entries",
        body,
        headers={"Idempotency-Key": idem_key},
    )
    if resp.status_code >= 400:
        log("WARN", "Capital d'ouverture non créé : HTTP {} (body={})",
            resp.status_code, resp.text[:200])
        return 0
    log("OK", "Capital d'ouverture créé : {} HTG (banque {} + stock {} + véhicule {}) ← capital {}",
        capital_total, bank_deposit, stock_initial, vehicle_existing, capital_total)
    return 1


def step_create_purchase_orders(
    api: ApiClient,
    company_id: str,
    suppliers: List[Dict[str, Any]],
    articles: List[Dict[str, Any]],
    rng: random.Random,
    n_pos: int = 12,
) -> int:
    """Crée des commandes fournisseurs (purchase-orders module).

    v2.7.2 : Sur certains backends (notamment Render), l'endpoint /purchase-orders
    n'est pas routé bien que le module PURCHASING soit activé. La fonction teste
    d'abord si l'endpoint répond, et skip proprement sinon (sans polluer le log
    avec 12 erreurs 404).
    """
    log("STEP", "12/16 — Création de {} commandes fournisseurs (purchase-orders)", n_pos)

    if not suppliers or not articles:
        log("WARN", "Pas de fournisseurs ou d'articles — skip purchase orders")
        return 0

    # v2.7.2 — Probe : teste si l'endpoint /purchase-orders existe
    probe_resp = api.get(f"/api/v1/companies/{company_id}/purchase-orders?size=1")
    if probe_resp.status_code == 404:
        log("WARN", "Endpoint /purchase-orders non disponible sur ce backend — skip (module non routé)")
        return 0
    if probe_resp.status_code >= 400 and probe_resp.status_code != 401:
        log("WARN", "Endpoint /purchase-orders inaccessible (HTTP {}) — skip", probe_resp.status_code)
        return 0

    created_count = 0
    for i in range(n_pos):
        supplier = rng.choice(suppliers)
        order_date = _random_date_in_fy(rng)
        order_number = f"BC-{order_date.strftime('%Y%m')}-{i+1:03d}"

        # 1 à 5 lignes
        n_lines = rng.randint(1, 5)
        chosen_articles = rng.sample(articles, min(n_lines, len(articles)))
        lines = []
        for art in chosen_articles:
            # Prix d'achat = art["cost"] (le coût d'achat)
            qty = rng.randint(5, 50)
            lines.append({
                "itemId": art.get("id"),
                "description": f"Réapprovisionnement {art['label']}",
                "quantity": qty,
                "unitPrice": art["cost"],
            })

        body = {
            "supplierId": supplier["id"],
            "orderNumber": order_number,
            "orderDate": order_date.isoformat(),
            "currency": "HTG",
            "status": "DRAFT",
            "lines": lines,
        }
        resp = api.post(f"/api/v1/companies/{company_id}/purchase-orders", body)
        if resp.status_code >= 400:
            if i < 3:
                log("WARN", "PO #{} non créé : HTTP {} (body={})",
                    i + 1, resp.status_code, resp.text[:200])
            continue
        created_count += 1

        # Change status : DRAFT → SUBMITTED → RECEIVED (pour les anciennes)
        po_id = None
        try:
            po_id = resp.json().get("id")
        except ValueError:
            pass
        if po_id:
            days_ago = (date.today() - order_date).days
            # v9.4 fix — Le backend attend `status` en query param, pas dans le body.
            # Ancien code envoyait {"status": "SUBMITTED"} en body → HTTP 500
            # "Required request parameter 'status' for method parameter type PurchaseOrderStatus is not present".
            # L'endpoint est @PostMapping("/{poId}/change-status") avec @RequestParam PurchaseOrderStatus status.
            if days_ago > 15:
                # Submit
                api.post(f"/api/v1/companies/{company_id}/purchase-orders/{po_id}/change-status?status=SUBMITTED")
            if days_ago > 30:
                # Receive
                api.post(f"/api/v1/companies/{company_id}/purchase-orders/{po_id}/change-status?status=RECEIVED")

    log("OK", "{} commandes fournisseurs créées", created_count)
    return created_count


def step_create_purchase_invoices(
    api: ApiClient,
    company_id: str,
    suppliers: List[Dict[str, Any]],
    articles: List[Dict[str, Any]],
    rng: random.Random,
    n_invoices: int = 20,
) -> int:
    """Crée des factures d'achat (purchasing module) avec réception stock."""
    log("STEP", "13/16 — Création de {} factures d'achat (purchasing)", n_invoices)

    if not suppliers or not articles:
        log("WARN", "Pas de fournisseurs ou d'articles — skip purchase invoices")
        return 0

    created_count = 0
    paid_count = 0
    for i in range(n_invoices):
        supplier = rng.choice(suppliers)
        issue_date = _random_date_in_fy(rng)
        due_date = issue_date + timedelta(days=rng.choice([30, 45, 60]))

        n_lines = rng.randint(1, 5)
        chosen_articles = rng.sample(articles, min(n_lines, len(articles)))
        lines = []
        for art in chosen_articles:
            qty = rng.randint(5, 50)
            lines.append({
                "description": art["label"],
                "quantity": qty,
                "unitPrice": art["cost"],  # prix d'achat = coût
                "taxRate": 10,  # TVA déductible 10%
            })

        body = {
            "thirdPartyId": supplier["id"],
            "type": "STANDARD",
            "supplierReference": f"FAC-{supplier['name'][:3].upper()}-{issue_date.strftime('%Y%m')}-{i+1:03d}",
            "issueDate": issue_date.isoformat(),
            "dueDate": due_date.isoformat(),
            "currency": "HTG",
            "lines": lines,
        }

        idem_key = f"seed-pi-{company_id[:8]}-{i+1:03d}-{int(time.time())}"
        # v9.4 fix — Le backend a unifié SALES et PURCHASE sous /invoices avec ?direction=PURCHASE.
        # L'ancien path /purchase-invoices n'existe pas (404 "No static resource").
        resp = api.post(
            f"/api/v1/companies/{company_id}/invoices?direction=PURCHASE",
            body,
            headers={"Idempotency-Key": idem_key},
        )
        if resp.status_code >= 400:
            if i < 3:
                log("WARN", "Facture achat #{} non créée : HTTP {} (body={})",
                    i + 1, resp.status_code, resp.text[:200])
            continue

        try:
            pi_data = resp.json()
        except ValueError:
            pi_data = {}
        pi_id = pi_data.get("id")
        balance_due = pi_data.get("balanceDue") or pi_data.get("totalAmount") or 0
        if not pi_id:
            continue
        created_count += 1

        # v9.4 fix — Receive la facture (statut RECEIVED pour PURCHASE = ISSUED pour SALES).
        # L'endpoint /issue gère les deux directions côté backend (TODO v9.0 dans le controller).
        api.post(f"/api/v1/companies/{company_id}/invoices/{pi_id}/issue")

        # Paiement fournisseur pour les factures anciennes (>30 jours)
        days_ago = (date.today() - issue_date).days
        if days_ago > 30 and balance_due and balance_due > 0:
            # v9.4 fix — /purchase-invoices/{id}/payments → /invoices/{id}/record-payment
            # (backend unifié, body {amount} via RecordPaymentRequest).
            pay_resp = api.post(
                f"/api/v1/companies/{company_id}/invoices/{pi_id}/record-payment",
                {"amount": balance_due},
            )
            if pay_resp.status_code < 300:
                paid_count += 1

        if created_count % 5 == 0:
            log("INFO", "{}/{} factures achat traitées...", created_count, n_invoices)

    log("OK", "{} factures d'achat créées (payées={})", created_count, paid_count)
    return created_count


def step_create_expense_reports(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
    rng: random.Random,
    n_expenses: int = 15,
) -> int:
    """Crée des notes de frais (expenses module)."""
    log("STEP", "14/16 — Création de {} notes de frais (expenses)", n_expenses)

    if not accounts:
        log("WARN", "Plan comptable vide — skip expense reports")
        return 0

    # Catégories standards déjà seedées par V43 : TRAVEL, MEALS, SUPPLIES, OTHER
    categories = [
        {"code": "TRAVEL", "label": "Déplacement", "amount_range": (2500, 25000),
         "account_codes": ["610", "625", "61"]},
        {"code": "MEALS", "label": "Repas d'affaires", "amount_range": (1500, 8000),
         "account_codes": ["623", "626", "62"]},
        {"code": "SUPPLIES", "label": "Fournitures bureau", "amount_range": (800, 12000),
         "account_codes": ["620", "627", "62"]},
        {"code": "OTHER", "label": "Autres charges", "amount_range": (1000, 15000),
         "account_codes": ["660", "628", "66"]},
    ]

    # Trouver un compte de charge pour chaque catégorie
    for cat in categories:
        cat["account_id"] = _pick_account(accounts, *cat["account_codes"])

    created_count = 0
    for i in range(n_expenses):
        cat = rng.choice(categories)
        if not cat.get("account_id"):
            continue
        expense_date = _random_date_in_fy(rng)
        amount = round(rng.uniform(*cat["amount_range"]), 2)

        body = {
            "thirdPartyId": None,  # dépense d'exploitation générale
            "expenseDate": expense_date.isoformat(),
            "currency": "HTG",
            "description": f"{cat['label']} — {expense_date.strftime('%B %Y')}",
            # v2.7.2 — paidDirectly=true obligatoire si thirdPartyId=null
            # (sinon backend 422 employee_required_for_reimbursement)
            "paidDirectly": True,
            "lines": [{
                "category": cat["code"],
                "description": f"{cat['label']} (électroménager commerce)",
                "amount": amount,
                "expenseAccountId": cat["account_id"],
            }],
        }

        idem_key = f"seed-exp-{company_id[:8]}-{i+1:03d}-{int(time.time())}"
        resp = api.post(
            f"/api/v1/companies/{company_id}/expense-reports",
            body,
            headers={"Idempotency-Key": idem_key},
        )
        if resp.status_code >= 400:
            if i < 3:
                log("WARN", "Note de frais #{} non créée : HTTP {} (body={})",
                    i + 1, resp.status_code, resp.text[:200])
            continue

        try:
            exp_data = resp.json()
        except ValueError:
            exp_data = {}
        exp_id = exp_data.get("id")
        if not exp_id:
            continue
        created_count += 1

        # Submit + approve pour les anciennes
        days_ago = (date.today() - expense_date).days
        if days_ago > 10:
            api.post(f"/api/v1/companies/{company_id}/expense-reports/{exp_id}/submit")
        if days_ago > 20:
            api.post(f"/api/v1/companies/{company_id}/expense-reports/{exp_id}/approve")

    log("OK", "{} notes de frais créées (soumises+approuvées pour les anciennes)",
        created_count)
    return created_count


def step_create_employees_and_payroll(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
    rng: random.Random,
    n_employees: int = 8,
) -> int:
    """Crée des employés + campagnes de paie (employees + payroll modules)."""
    log("STEP", "15/16 — Création de {} employés + campagnes de paie", n_employees)

    if not accounts:
        log("WARN", "Plan comptable vide — skip employees/payroll")
        return 0

    # Compte collectif employés (classe 42 en PCN_HAITI)
    employee_collective_account = _pick_account(accounts, "421", "42")

    # Noms d'employés réalistes haïtiens
    employee_names = [
        ("Jean-Robert Pierre", "Gérant", "Direction", 85000),
        ("Marie-Carmel Joseph", "Comptable", "Finance", 55000),
        ("Wilner Charles", "Vendeur senior", "Ventes", 32000),
        ("Sophia Therméus", "Vendeuse", "Ventes", 28000),
        ("Patrick Étienne", "Magasinier", "Logistique", 25000),
        ("Naïka Beauvoir", "Caissière", "Ventes", 27000),
        ("David Joseph", "Livreur", "Logistique", 22000),
        ("Christine Lubin", "Secrétaire", "Administration", 30000),
    ][:n_employees]

    created_count = 0
    employee_ids = []
    for name, position, department, salary in employee_names:
        body = {
            "thirdPartyId": None,
            "thirdPartyName": name,
            "collectiveAccountId": employee_collective_account,
            "employeeNumber": f"EMP-{created_count+1:03d}",
            "position": position,
            "department": department,
            "hireDate": FY_START.isoformat(),
            "baseSalary": salary,
            "salaryCurrency": "HTG",
            "contractType": "PERMANENT",
            "bankAccountNumber": f"BTN-{rng.randint(100000, 999999)}",
        }
        resp = api.post(f"/api/v1/companies/{company_id}/employees", body)
        if resp.status_code >= 400:
            if created_count == 0:
                log("WARN", "Employé '{}' non créé : HTTP {} (body={})",
                    name, resp.status_code, resp.text[:200])
            continue
        try:
            emp_data = resp.json()
            emp_id = emp_data.get("id")
            if emp_id:
                employee_ids.append(emp_id)
        except ValueError:
            pass
        created_count += 1

    log("OK", "{} employés créés", created_count)

    # Campagnes de paie mensuelles sur 6 mois (avril-septembre 2026)
    if employee_ids:
        payroll_created = 0
        payroll_failed_logged = False
        for month in [4, 5, 6, 7, 8, 9]:  # avril à septembre 2026
            body = {
                "periodMonth": month,
                "periodYear": 2026,
                "employerContributionRate": 12,  # 12% charges patronales Haïti
            }
            # v2.7.3 — Le contrôleur PayrollController est mappé sur /payroll-runs OU /payroll
            # (PAS /payroll/payroll-runs qui serait interprété comme /payroll/{id}).
            resp = api.post(f"/api/v1/companies/{company_id}/payroll-runs", body)
            if resp.status_code >= 500:
                # v2.7.2 — Erreur interne backend (peut arriver sur certains déploiements
                # Render où la config payroll est incomplète). On log une seule fois et
                # on skip — les salaires seront quand même comptabilisés via les écritures
                # manuelles OD (step_create_journal_entries).
                if not payroll_failed_logged:
                    log("WARN", "Payroll en erreur 500 sur ce backend — skip des campagnes de paie")
                    log("DATA", "Les salaires restent comptabilisés via les écritures OD manuelles")
                    payroll_failed_logged = True
                continue
            if resp.status_code >= 400:
                continue
            try:
                run_data = resp.json()
                run_id = run_data.get("id")
            except ValueError:
                run_id = None
            if not run_id:
                continue

            # Calculate + approve + pay
            api.post(f"/api/v1/companies/{company_id}/payroll-runs/{run_id}/calculate")
            # Approve après calculate
            api.post(f"/api/v1/companies/{company_id}/payroll-runs/{run_id}/approve")
            # Pay (génère écriture comptable BQ)
            api.post(f"/api/v1/companies/{company_id}/payroll-runs/{run_id}/pay")
            payroll_created += 1

        if payroll_created > 0:
            log("OK", "{} campagnes de paie créées (avril→septembre 2026, 12% charges patronales)",
                payroll_created)
        elif not payroll_failed_logged:
            log("WARN", "Aucune campagne de paie créée — vérifiez la config payroll backend")

    return created_count


def step_create_bank_movements(
    api: ApiClient,
    company_id: str,
    accounts: Dict[str, Dict[str, Any]],
    rng: random.Random,
    n_movements: int = 20,
) -> int:
    """Crée des écritures bancaires (BQ) : virements, dépôts, retraits, frais bancaires."""
    log("STEP", "16/16 — Création de {} écritures bancaires (BQ)", n_movements)

    if not accounts:
        log("WARN", "Plan comptable vide — skip bank movements")
        return 0

    bank_account = _pick_account(accounts, "521", "52", "57")
    interest_account = _pick_account(accounts, "650", "65", "66")
    bank_charges_account = _pick_account(accounts, "627", "626", "62")
    cash_account = _pick_account(accounts, "570", "57", "530")

    if not bank_account:
        log("WARN", "Compte banque non trouvé — skip bank movements")
        return 0

    def _code_of(acc_id):
        for code, acc in accounts.items():
            if acc.get("id") == acc_id:
                return code
        return None

    bank_code = _code_of(bank_account)

    templates = []
    if interest_account:
        interest_code = _code_of(interest_account)
        templates.append({
            "description": "Intérêts bancaires créditeurs (placement)",
            "amount_range": (1500, 8000),
            "debit_code": bank_code,
            "credit_code": interest_code,
        })
    if bank_charges_account:
        charges_code = _code_of(bank_charges_account)
        templates.append({
            "description": "Frais bancaires (tenue de compte, virements)",
            "amount_range": (300, 2500),
            "debit_code": charges_code,
            "credit_code": bank_code,
        })
    if cash_account:
        cash_code = _code_of(cash_account)
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
        log("WARN", "Aucun template bancaire exploitable — skip")
        return 0

    created_count = 0
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
                 "debit": amount, "credit": 0,
                 "description": tpl["description"]},
                {"accountCode": tpl["credit_code"], "thirdPartyId": None,
                 "debit": 0, "credit": amount,
                 "description": "Contrepartie"},
            ],
            "sourceModule": "MANUAL",
        }

        idem_key = f"seed-bq-{company_id[:8]}-{i+1:03d}-{int(time.time())}"
        resp = api.post(
            f"/api/v1/companies/{company_id}/accounting-engine/journal-entries",
            body,
            headers={"Idempotency-Key": idem_key},
        )
        if resp.status_code >= 400:
            continue
        created_count += 1

    log("OK", "{} écritures bancaires créées (intérêts, frais, retraits, dépôts)",
        created_count)
    return created_count


# ──────────────────────────────────────────────────────────────────────────
# Vérification finale
# ──────────────────────────────────────────────────────────────────────────

def step_verify(api: ApiClient, company_id: str) -> None:
    """Vérifie que les données sont bien présentes."""
    log("STEP", "Vérification — Comptage des données créées")

    # Factures
    # v9.4 fix — /invoicing/invoices → /invoices (unification v9.0).
    resp = api.get(f"/api/v1/companies/{company_id}/invoices?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data)
            log("OK", "Factures : {}", total)
        except ValueError:
            pass

    # Tiers
    resp = api.get(f"/api/v1/companies/{company_id}/third-parties?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data)
            log("OK", "Tiers : {}", total)
        except ValueError:
            pass

    # Articles
    resp = api.get(f"/api/v1/companies/{company_id}/inventory/items")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = len(data) if isinstance(data, list) else data.get("totalElements", "?")
            log("OK", "Articles : {}", total)
        except ValueError:
            pass

    # Immobilisations
    resp = api.get(f"/api/v1/companies/{company_id}/fixed-assets")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = len(data) if isinstance(data, list) else data.get("totalElements", "?")
            log("OK", "Immobilisations : {}", total)
        except ValueError:
            pass

    # Écritures
    resp = api.get(f"/api/v1/companies/{company_id}/accounting-engine/journal-entries?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data)
            log("OK", "Écritures : {}", total)
        except ValueError:
            pass

    # v2.7.2 — Nouveaux modules
    # Factures d'achat (purchasing)
    # v9.4 fix — /purchase-invoices → /invoices?direction=PURCHASE (unification v9.0).
    # Pour le count total on peut aussi bien appeler /invoices (toutes directions confondues).
    resp = api.get(f"/api/v1/companies/{company_id}/invoices?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data) if isinstance(data, list) else "?"
            log("OK", "Factures d'achat : {}", total)
        except ValueError:
            pass

    # Commandes fournisseurs (purchase-orders) — v2.7.2 : endpoint optionnel
    resp = api.get(f"/api/v1/companies/{company_id}/purchase-orders?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data) if isinstance(data, list) else "?"
            log("OK", "Commandes fournisseurs : {}", total)
        except ValueError:
            pass
    elif resp.status_code == 404:
        log("DATA", "Commandes fournisseurs : endpoint non disponible sur ce backend")

    # Notes de frais (expenses)
    resp = api.get(f"/api/v1/companies/{company_id}/expense-reports?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data) if isinstance(data, list) else "?"
            log("OK", "Notes de frais : {}", total)
        except ValueError:
            pass

    # Employés
    resp = api.get(f"/api/v1/companies/{company_id}/employees?page=0&size=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = data.get("totalElements", "?") if isinstance(data, dict) else len(data) if isinstance(data, list) else "?"
            log("OK", "Employés : {}", total)
        except ValueError:
            pass

    # Campagnes de paie — v2.7.3 : path corrigé (/payroll-runs, pas /payroll/payroll-runs)
    resp = api.get(f"/api/v1/companies/{company_id}/payroll-runs?limit=1")
    if resp.status_code < 300:
        try:
            data = resp.json()
            total = len(data) if isinstance(data, list) else data.get("totalElements", "?")
            log("OK", "Campagnes de paie : {}", total)
        except ValueError:
            pass
    elif resp.status_code in (400, 404):
        log("DATA", "Campagnes de paie : aucune créée (endpoint non disponible ou config backend incomplète)")


# ──────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────

def wait_for_backend(api: ApiClient, timeout_s: int = 180) -> bool:
    """Attend que le backend soit prêt (health check)."""
    log("INFO", "Attente du backend sur {} ...", api.base_url)
    # Le management port (8081) expose /actuator/health séparément du port app (8080)
    mgmt_url = api.base_url.replace(":8080", ":8081").rstrip("/")
    start = time.time()
    while time.time() - start < timeout_s:
        # Test 1 : health endpoint sur le port management (8081)
        try:
            resp = api.session.get(f"{mgmt_url}/actuator/health", timeout=5)
            if resp.status_code == 200:
                log("OK", "Backend prêt (health UP, {}s)", round(time.time() - start, 1))
                return True
        except requests.RequestException:
            pass
        # Test 2 : OpenAPI sur le port app (8080) — toujours accessible sans auth
        try:
            resp = api.session.get(f"{api.base_url.rstrip('/')}/v3/api-docs", timeout=5)
            if resp.status_code == 200:
                log("OK", "Backend prêt (OpenAPI accessible, {}s)", round(time.time() - start, 1))
                return True
        except requests.RequestException:
            pass
        time.sleep(3)
    log("ERR", "Backend non disponible après {}s", timeout_s)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed données commerce électronique JOAccountant")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"URL backend (défaut: {DEFAULT_BASE_URL})")
    parser.add_argument("--invoices", type=int, default=40,
                        help="Nombre de factures de vente à créer (défaut: 40)")
    parser.add_argument("--entries", type=int, default=25,
                        help="Nombre d'écritures manuelles OD (défaut: 25)")
    parser.add_argument("--purchase-invoices", type=int, default=20,
                        help="Nombre de factures d'achat à créer (défaut: 20)")
    parser.add_argument("--purchase-orders", type=int, default=12,
                        help="Nombre de commandes fournisseurs à créer (défaut: 12)")
    parser.add_argument("--expenses", type=int, default=15,
                        help="Nombre de notes de frais à créer (défaut: 15)")
    parser.add_argument("--employees", type=int, default=8,
                        help="Nombre d'employés à créer (défaut: 8)")
    parser.add_argument("--bank-movements", type=int, default=20,
                        help="Nombre d'écritures bancaires BQ (défaut: 20)")
    parser.add_argument("--seed", type=int, default=20261001,
                        help="Seed RNG (défaut: 20261001)")
    parser.add_argument("--skip-wait", action="store_true",
                        help="Ne pas attendre le backend (suppose qu'il est déjà prêt)")
    parser.add_argument("--output-dir", default=None,
                        help="Dossier où sauvegarder seed_credentials.json "
                             "(défaut: dossier courant)")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    api = ApiClient(base_url=args.base_url)

    print(f"\n{C.BOLD}{C.CYAN}═══════════════════════════════════════════════════════════════{C.RESET}")
    print(f"{C.BOLD}{C.CYAN}  Seed JOAccountant — Commerce électronique HT  {C.RESET}")
    print(f"{C.BOLD}{C.CYAN}  Exercice : 01/10/2025 → 30/09/2026  {C.RESET}")
    print(f"{C.BOLD}{C.CYAN}═══════════════════════════════════════════════════════════════{C.RESET}\n")

    if not args.skip_wait and not wait_for_backend(api):
        return 1

    suffix = datetime.now().strftime("%Y%m%d%H%M%S")
    start_time = time.time()

    try:
        email, password = step_register_user(api, suffix)
        step_login(api, email, password)
        company_id = step_create_company(api)
        step_wizard_complete(api, company_id)
        accounts = step_get_accounts(api, company_id)
        # v2.7.2 — Capital d'ouverture AVANT les autres écritures (à nouveaux au 01/10)
        step_create_capital_opening(api, company_id, accounts, rng)
        third_parties = step_create_third_parties(api, company_id)
        items = step_create_inventory_items(api, company_id, accounts)
        step_create_fixed_assets(api, company_id, accounts)
        all_clients = third_parties["clients"]
        all_suppliers = third_parties["suppliers"]
        # v2.7.2 — Commandes fournisseurs avant factures d'achat (workflow naturel)
        step_create_purchase_orders(api, company_id, all_suppliers, items, rng,
                                     n_pos=args.purchase_orders)
        # v2.7.2 — Factures d'achat (purchasing) avec réception + paiement
        step_create_purchase_invoices(api, company_id, all_suppliers, items, rng,
                                       n_invoices=args.purchase_invoices)
        # Factures de vente (invoicing)
        step_create_invoices(api, company_id, all_clients, items, rng, n_invoices=args.invoices)
        # v2.7.2 — Notes de frais (expenses)
        step_create_expense_reports(api, company_id, accounts, rng, n_expenses=args.expenses)
        # v2.7.2 — Employés + campagnes de paie (employees + payroll)
        step_create_employees_and_payroll(api, company_id, accounts, rng,
                                           n_employees=args.employees)
        # Écritures manuelles OD (salaires, loyers, charges diverses)
        step_create_journal_entries(api, company_id, accounts, rng, n_entries=args.entries)
        # v2.7.2 — Écritures bancaires BQ (intérêts, frais, retraits, dépôts)
        step_create_bank_movements(api, company_id, accounts, rng, n_movements=args.bank_movements)
        step_verify(api, company_id)

    except RuntimeError as e:
        log("ERR", "Échec : {}", e)
        log("INFO", "Requêtes : {} (erreurs : {})", api.request_count, api.error_count)
        return 2

    elapsed = time.time() - start_time
    print(f"\n{C.BOLD}{C.GREEN}═══════════════════════════════════════════════════════════════{C.RESET}")
    print(f"{C.BOLD}{C.GREEN}  ✓ Seed terminé en {elapsed:.1f}s  {C.RESET}")
    print(f"{C.BOLD}{C.GREEN}  Requêtes API : {api.request_count} (erreurs : {api.error_count})  {C.RESET}")
    print(f"{C.BOLD}{C.GREEN}  Company ID   : {api.company_id}  {C.RESET}")
    print(f"{C.BOLD}{C.GREEN}  Login        : {email} / {password}  {C.RESET}")
    print(f"{C.BOLD}{C.GREEN}═══════════════════════════════════════════════════════════════{C.RESET}\n")

    # Sauvegarde les credentials dans un fichier pour faciliter les tests mobiles.
    # v2.7.1 : utilise le dossier courant par défaut (au lieu d'un chemin absolu
    # /home/z/my-project/download/ qui n'existe que sur la machine de dev).
    # Le dossier de sortie peut être personnalisé via --output-dir.
    import os
    output_dir = args.output_dir if args.output_dir else os.getcwd()
    try:
        os.makedirs(output_dir, exist_ok=True)
    except OSError:
        output_dir = os.getcwd()  # fallback sur dossier courant si création impossible
    creds_file = os.path.join(output_dir, "seed_credentials.json")
    creds = {
        "base_url": args.base_url,
        "email": email,
        "password": password,
        "company_id": api.company_id,
        "fiscal_year": {"start": FY_START.isoformat(), "end": FY_END.isoformat()},
        "business_type": "MIXED_COMMERCE",
        "country": "HT",
        "currency": "HTG",
        "accounting_framework": "PCN_HAITI",
        "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    try:
        with open(creds_file, "w", encoding="utf-8") as f:
            json.dump(creds, f, indent=2, ensure_ascii=False)
        log("OK", "Credentials sauvegardés dans {}", creds_file)
    except IOError as e:
        log("WARN", "Impossible de sauvegarder les credentials : {}", e)

    return 0


if __name__ == "__main__":
    sys.exit(main())

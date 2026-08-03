#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_modern.py — Version moderne, stylée et intuitive du seed JOAccountant v9.4.

Réécriture complète de `seed_electronics_commerce.py` avec :
  • httpx          — client HTTP/2 asynchrone (3-5× plus rapide que requests)
  • rich           — sortie terminal élégante (tables, panneaux, arbres, spinners)
  • pydantic       — DTOs typés avec validation
  • tqdm           — barres de progression pour les lots
  • asyncio        — appels API parallèles pour les créations indépendantes
  • Jinja2         — génération d'un rapport HTML final
  • qrcode         — QR code ASCII pour accès mobile rapide

Nouveautés fonctionnelles :
  1. Profile multi-secteur  : --profile electronics|retail|ngo|services|freezone
  2. Suite PDF complète     : facture, factur-x, bilan, compte de résultat, TVA
  3. Dashboard agrégé       : KPIs /reporting/dashboard en fin de seed
  4. États financiers       : balance-sheet, income-statement, cash-flow
  5. Audit trail            : vérifie que les écritures sont auditées
  6. Search                 : démontre la recherche globale (/search)
  7. Modules activés        : GET /companies/{id}/modules pour afficher le catalogue
  8. Rapport HTML final     : seed_report.html avec tous les artefacts créés
  9. QR code terminal       : affiche un QR code pour se connecter rapidement
 10. --dry-run              : simule sans rien créer
 11. Idempotency-Key stable : basé sur (company_id, step, index) — reproductible
 12. Retry avec backoff     : tenacity sur les erreurs réseau 5xx/429

Usage :
    python3 seed_modern.py --base-url http://localhost:8080
    python3 seed_modern.py --base-url http://localhost:8080 --profile retail
    python3 seed_modern.py --base-url http://localhost:8080 --dry-run
    python3 seed_modern.py --base-url http://localhost:8080 --invoices 80 --no-pdf

Pré-requis : backend joaccountant démarré en profil dev (PostgreSQL embarqué Zonky).
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import io
import json
import os
import random
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

import httpx
from pydantic import BaseModel, Field
from rich.console import Console, Group
from rich.panel import Panel
from rich.progress import (
    BarColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
)
from rich.table import Table
from rich.tree import Tree


# ──────────────────────────────────────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────────────────────────────────────

DEFAULT_BASE_URL = "http://localhost:8080"
MANAGEMENT_URL_PORT = 8081  # actuator/health est exposé sur ce port

# Plans comptables (UUIDs stables seedés par V1_002)
PCN_HAITI_FRAMEWORK_ID = "00000000-0000-0000-0000-000000000005"
SYSCOHADA_FRAMEWORK_ID = "00000000-0000-0000-0000-000000000003"
PCG_FRANCE_FRAMEWORK_ID = "00000000-0000-0000-0000-000000000004"

# Exercice fiscal cible (1er octobre 2025 → 30 septembre 2026)
FY_START_DEFAULT = date(2025, 10, 1)
FY_END_DEFAULT = date(2026, 9, 30)


# ──────────────────────────────────────────────────────────────────────────────
# Profils métier (multi-secteur)
# ──────────────────────────────────────────────────────────────────────────────

@dataclass(slots=True, frozen=True)
class BusinessProfile:
    """Définition d'un profil métier pour le seed."""
    key: str
    label: str
    icon: str
    company_name: str
    business_type_code: str
    primary_activity: str
    sector: str
    organization_nature: str
    legal_form: str
    country: str
    currency: str
    framework_id: str
    fy_start_month: int
    nif: str
    address: str
    # Articles (stock) — None pour les profils sans stock
    articles: Optional[list[dict]] = None
    # Clients
    clients_retail: list[dict] = field(default_factory=list)
    clients_wholesale: list[dict] = field(default_factory=list)
    # Fournisseurs
    suppliers: list[dict] = field(default_factory=list)
    # Immobilisations
    fixed_assets: list[dict] = field(default_factory=list)
    # Notes : couleur rich pour le résumé
    accent_color: str = "cyan"


PROFILES: dict[str, BusinessProfile] = {
    "electronics": BusinessProfile(
        key="electronics",
        label="Commerce électronique HT",
        icon="📱",
        company_name="ElectroPro Distribution HT",
        business_type_code="MIXED_COMMERCE",
        primary_activity="Vente en gros et au détail de marchandises électroniques (téléviseurs, smartphones, ordinateurs, accessoires)",
        sector="COMMERCE",
        organization_nature="FOR_PROFIT",
        legal_form="SA",
        country="HT",
        currency="HTG",
        framework_id=PCN_HAITI_FRAMEWORK_ID,
        fy_start_month=10,
        nif="HT2018-98765-E",
        address="Varreux 1, Rue des Industries, Port-au-Prince, Haïti",
        articles=[
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
        ],
        clients_retail=[
            {"name": "Jean-Robert Pierre",          "email": "jrpierre@example.ht",  "address": "Delmas 33, Port-au-Prince",     "type": "CLIENT"},
            {"name": "Marie-Carmel Joseph",         "email": "mcjoseph@example.ht",  "address": "Pétion-Ville, Rue Lamarre",     "type": "CLIENT"},
            {"name": "Boutique Electro Express",    "email": "electro.exp@example.ht","address": "Carrefour, Route de l'Aéroport","type": "CLIENT"},
            {"name": "Clinique Médicale du Bord",   "email": "cmbord@example.ht",    "address": "Bourg-de-Port, Cap-Haïtien",    "type": "CLIENT"},
            {"name": "École Mixte Le Bon Berger",   "email": "bonberger@example.ht","address": "Saint-Marc, Artibonite",        "type": "CLIENT"},
            {"name": "Wilson Télécom",              "email": "wilsontel@example.ht", "address": "Gonaïves, Rue Dumas",           "type": "CLIENT"},
            {"name": "Hôpital Sainte-Thérèse",      "email": "hstherese@example.ht", "address": "Port-au-Prince, Tabarre",       "type": "CLIENT"},
        ],
        clients_wholesale=[
            {"name": "Distributeur Nord SA",        "email": "distnord@example.ht",   "address": "Cap-Haïtien, Zone Industrielle","type": "CLIENT"},
            {"name": "Grossiste Electro Sud",       "email": "elecsud@example.ht",    "address": "Les Cayes, Rue Pavée",          "type": "CLIENT"},
            {"name": "Importex Haïti",              "email": "importex@example.ht",   "address": "Port-au-Prince, Varreux",      "type": "CLIENT"},
            {"name": "Comptoir Électronique Centre","email": "comptoir@example.ht",   "address": "Hinche, Centre",                "type": "CLIENT"},
            {"name": "Techno Distribution Artib.",  "email": "technodis@example.ht",  "address": "Gonaïves, Avenue Toussaint",    "type": "CLIENT"},
        ],
        suppliers=[
            {"name": "Shenzhen Electro Import Co.",  "email": "sales@shenzhen-electro.cn", "address": "Shenzhen, Guangdong, China",       "type": "SUPPLIER"},
            {"name": "Miami Tech Wholesale Inc.",    "email": "orders@miamitechwholesale.com","address": "Miami, FL 33126, USA",          "type": "SUPPLIER"},
            {"name": "Santo Domingo Electronics",    "email": "ventas@sdelc.do",           "address": "Santo Domingo, República Dominicana","type": "SUPPLIER"},
            {"name": "Panama Tech Distributors",     "email": "info@panamatech.pa",        "address": "Colon Free Zone, Panama",         "type": "SUPPLIER"},
            {"name": "Distribuidora Caribbean SA",   "email": "caribdis@dr.com",           "address": "Santiago de los Caballeros, RD", "type": "SUPPLIER"},
        ],
        fixed_assets=[
            {"label": "Camion Isuzu NPR de livraison", "cost": 1850000, "useful_life_months": 60,  "residual": 250000},
            {"label": "Fourgon Toyota Hiace",          "cost": 1450000, "useful_life_months": 60,  "residual": 200000},
            {"label": "Ordinateur de bureau comptable","cost": 55000,   "useful_life_months": 36,  "residual": 5000},
            {"label": "Serveur et onduleur bureau",    "cost": 95000,   "useful_life_months": 48,  "residual": 8000},
            {"label": "Mobilier de bureau (bureaux)",  "cost": 75000,   "useful_life_months": 120, "residual": 10000},
            {"label": "Climatiseurs entrepôt",         "cost": 120000,  "useful_life_months": 84,  "residual": 12000},
        ],
        accent_color="bright_cyan",
    ),
    "retail": BusinessProfile(
        key="retail",
        label="Commerce de détail alimentaire",
        icon="🛒",
        company_name="Boutique Lakay SA",
        business_type_code="RETAIL_COMMERCE",
        primary_activity="Supermarché — vente au détail de produits alimentaires et d'articles ménagers",
        sector="COMMERCE",
        organization_nature="FOR_PROFIT",
        legal_form="SARL",
        country="HT",
        currency="HTG",
        framework_id=PCN_HAITI_FRAMEWORK_ID,
        fy_start_month=1,
        nif="HT2019-11223-R",
        address="Delmas 75, Rue Ti Couloute, Port-au-Prince",
        articles=[
            {"sku": "ALM-RIZ-25KG",  "label": "Riz national 25 kg",             "unit": "SAC",  "cost": 1850,  "price": 2500,  "reorder": 20},
            {"sku": "ALM-HUILE-5L",  "label": "Huile végétale 5 L",             "unit": "BID",  "cost": 850,   "price": 1200,  "reorder": 15},
            {"sku": "ALM-SUCRE-2KG", "label": "Sucre blanc 2 kg",               "unit": "PAQ",  "cost": 180,   "price": 275,   "reorder": 30},
            {"sku": "ALM-FAR-5KG",   "label": "Farine de blé 5 kg",             "unit": "SAC",  "cost": 320,   "price": 475,   "reorder": 20},
            {"sku": "ALM-LAIT-400G", "label": "Lait en poudre 400 g",           "unit": "BOI",  "cost": 285,   "price": 425,   "reorder": 25},
            {"sku": "ALM-TOM-2.5KG", "label": "Sauce tomate 2.5 kg",            "unit": "BID",  "cost": 650,   "price": 925,   "reorder": 12},
            {"sku": "ALM-POIS-1KG",  "label": "Pois congolés 1 kg",             "unit": "SAC",  "cost": 110,   "price": 175,   "reorder": 40},
            {"sku": "ALM-MAIS-1KG",  "label": "Farine de maïs 1 kg",            "unit": "SAC",  "cost": 95,    "price": 150,   "reorder": 40},
            {"sku": "MEN-SAV-500G",  "label": "Savon de ménage 500 g (lot 12)", "unit": "LOT",  "cost": 480,   "price": 720,   "reorder": 15},
            {"sku": "MEN-DET-3L",    "label": "Détergent 3 L",                  "unit": "BID",  "cost": 540,   "price": 795,   "reorder": 15},
            {"sku": "MEN-ESS-6R",    "label": "Essuie-tout 6 rouleaux",         "unit": "PAQ",  "cost": 380,   "price": 575,   "reorder": 20},
            {"sku": "BOI-EAU-6X1.5", "label": "Eau minérale 6×1.5 L",           "unit": "PAQ",  "cost": 425,   "price": 625,   "reorder": 25},
        ],
        clients_retail=[
            {"name": "Ménaïe Joseph",             "email": "menaie@example.ht",   "address": "Delmas 32, PAP",         "type": "CLIENT"},
            {"name": "Père Gérard Beauvais",      "email": "gbeauvais@example.ht","address": "Pétion-Ville, Rue N.",   "type": "CLIENT"},
            {"name": "Resto Manman Lakay",        "email": "manman@example.ht",   "address": "Croix-des-Bouquets",     "type": "CLIENT"},
            {"name": "Cantine École Lumière",     "email": "lumiere@example.ht",  "address": "Tabarre, PAP",           "type": "CLIENT"},
            {"name": "Hôtel Le Rond-Point",       "email": "rondpoint@example.ht","address": "Pétion-Ville",           "type": "CLIENT"},
        ],
        clients_wholesale=[
            {"name": "Resto Chain HT SA",        "email": "chainht@example.ht",  "address": "Port-au-Prince",   "type": "CLIENT"},
            {"name": "Cantine Scolaire Artib.",  "email": "cantine@example.ht",  "address": "Saint-Marc",       "type": "CLIENT"},
        ],
        suppliers=[
            {"name": "Moulins d'Haïti SA",          "email": "ventes@moulins.ht",     "address": "Port-au-Prince",     "type": "SUPPLIER"},
            {"name": "Caribbean Foods DR",          "email": "sales@caribfoods.do",   "address": "Santo Domingo",      "type": "SUPPLIER"},
            {"name": "Haitai Beverages",            "email": "info@haitai.com",       "address": "Port-au-Prince",     "type": "SUPPLIER"},
        ],
        fixed_assets=[
            {"label": "Chambre froide positive 20 m³",   "cost": 850000,  "useful_life_months": 120, "residual": 100000},
            {"label": "Camion réfrigéré Isuzu",          "cost": 2400000, "useful_life_months": 72,  "residual": 400000},
            {"label": "Mobilier commercial (rayons)",    "cost": 320000,  "useful_life_months": 84,  "residual": 30000},
            {"label": "Caisse enregistreuse + TPE",      "cost": 85000,   "useful_life_months": 60,  "residual": 8000},
        ],
        accent_color="bright_green",
    ),
    "ngo": BusinessProfile(
        key="ngo",
        label="ONG humanitaire",
        icon="🤝",
        company_name="Fondasyon Espwa Ayiti",
        business_type_code="NGO_HUMANITARIAN",
        primary_activity="Projets de développement communautaire financés par bailleurs de fonds internationaux (USAID, UE, AECID)",
        sector="ONG_HUMANITAIRE",
        organization_nature="NON_PROFIT",
        legal_form="NGO",
        country="HT",
        currency="USD",
        framework_id=PCN_HAITI_FRAMEWORK_ID,
        fy_start_month=1,
        nif="HT2017-44556-N",
        address="Rue Monseigneur Guilloux, Pétion-Ville, Haïti",
        articles=None,  # ONG sans stock marchand
        clients_retail=[],
        clients_wholesale=[],
        suppliers=[
            {"name": "USAID Haiti",                "email": "grants@usaid.gov",          "address": "Tabarre, PAP",      "type": "DONOR"},
            {"name": "Délégation UE Haïti",        "email": "cooperation@eeas.europa.eu","address": "Bourdon, PAP",      "type": "DONOR"},
            {"name": "AECID Haiti",                "email": "aecid@aecid.ht",            "address": "Pétion-Ville",      "type": "DONOR"},
            {"name": "Banco Mundial Haiti Office", "email": "infowb@worldbank.org",      "address": "Pétion-Ville",      "type": "DONOR"},
        ],
        fixed_assets=[
            {"label": "Véhicule Toyota Land Cruiser 4×4", "cost": 1850000, "useful_life_months": 96,  "residual": 250000},
            {"label": "Bureaux + mobilier siège",          "cost": 450000,  "useful_life_months": 120, "residual": 50000},
            {"label": "Ordinateurs portables (équipe)",    "cost": 220000,  "useful_life_months": 36,  "residual": 20000},
            {"label": "Onduleur + groupe électrogène",     "cost": 380000,  "useful_life_months": 72,  "residual": 40000},
        ],
        accent_color="bright_magenta",
    ),
    "services": BusinessProfile(
        key="services",
        label="Services professionnels (cabinet comptable)",
        icon="💼",
        company_name="Cabinet JoCompta & Associés",
        business_type_code="ACCOUNTING_FIRM",
        primary_activity="Cabinet d'expertise comptable agréé — tenue, audit, conseil fiscal pour PME haïtiennes",
        sector="CABINET_COMPTABLE",
        organization_nature="FOR_PROFIT",
        legal_form="SARL",
        country="HT",
        currency="HTG",
        framework_id=PCN_HAITI_FRAMEWORK_ID,
        fy_start_month=1,
        nif="HT2020-77889-C",
        address="Rue Lamarre 18, Pétion-Ville, Haïti",
        articles=None,
        clients_retail=[
            {"name": "Boulangerie du Marché",      "email": "boulmarche@example.ht", "address": "Pétion-Ville",  "type": "CLIENT"},
            {"name": "Boutique Lakay SA",          "email": "boutique.lakay@example.ht","address": "Delmas 75",  "type": "CLIENT"},
            {"name": "Clinique Médicale du Bord",  "email": "cmbord@example.ht",     "address": "Cap-Haïtien",   "type": "CLIENT"},
            {"name": "Hôtel Le Rond-Point",        "email": "rondpoint@example.ht",  "address": "Pétion-Ville",  "type": "CLIENT"},
            {"name": "Resto Manman Lakay",         "email": "manman@example.ht",     "address": "Croix-des-Bouquets","type":"CLIENT"},
        ],
        clients_wholesale=[],
        suppliers=[
            {"name": "Ordre des Comptables Agréés HT", "email": "oca@oca.ht", "address": "Port-au-Prince", "type": "SUPPLIER"},
        ],
        fixed_assets=[
            {"label": "Bureaux cabinet (aménagements)",  "cost": 380000,  "useful_life_months": 120, "residual": 50000},
            {"label": "Ordinateurs portables équipe",    "cost": 280000,  "useful_life_months": 36,  "residual": 25000},
            {"label": "Serveur NAS + sauvegarde",         "cost": 145000,  "useful_life_months": 60,  "residual": 15000},
            {"label": "Logiciels comptables (licences)",  "cost": 220000,  "useful_life_months": 36,  "residual": 0},
        ],
        accent_color="bright_blue",
    ),
}


# ──────────────────────────────────────────────────────────────────────────────
# Rich console
# ──────────────────────────────────────────────────────────────────────────────

console = Console(highlight=False, soft_wrap=False)


def banner(title: str, subtitle: str = "", color: str = "bright_cyan") -> None:
    """Affiche un en-tête de section stylé."""
    content = f"[bold {color}]{title}[/]"
    if subtitle:
        content += f"\n[dim]{subtitle}[/]"
    console.print(Panel(content, border_style=color, padding=(1, 2), expand=False))


def step_panel(msg: str, color: str = "cyan") -> None:
    """Affiche une étape avec un panneau compact."""
    console.print(Panel(f"[bold]{msg}[/]", border_style=color, padding=(0, 2), expand=False))


def info(msg: str) -> None:
    console.print(f"  [dim]ℹ[/]  {msg}")


def ok(msg: str) -> None:
    console.print(f"  [green]✓[/]  {msg}")


def warn(msg: str) -> None:
    console.print(f"  [yellow]⚠[/]  {msg}")


def err(msg: str) -> None:
    console.print(f"  [red]✗[/]  {msg}")


# ──────────────────────────────────────────────────────────────────────────────
# Client HTTP asynchrone (httpx)
# ──────────────────────────────────────────────────────────────────────────────

class ApiClient:
    """Client HTTP asynchrone avec retry, idempotency et gestion de JWT.

    Utilise httpx.AsyncClient pour profiter du connection pooling et des requêtes
    parallèles. Le retry avec backoff exponentiel gère les 5xx et les timeouts réseau.
    """

    def __init__(self, base_url: str, timeout: float = 30.0, dry_run: bool = False):
        self.base_url = base_url.rstrip("/")
        self.token: Optional[str] = None
        self.company_id: Optional[str] = None
        self.dry_run = dry_run
        self.request_count = 0
        self.error_count = 0
        self._client: Optional[httpx.AsyncClient] = None
        self._timeout = httpx.Timeout(timeout, connect=10.0)

    async def __aenter__(self) -> "ApiClient":
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=self._timeout,
            http2=False,  # http2=True nécessite h2 — gardons False pour compat
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
            follow_redirects=True,
        )
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        if self._client:
            await self._client.aclose()

    def _headers(self, extra: Optional[dict] = None) -> dict:
        h = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        if extra:
            h.update(extra)
        return h

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any = None,
        params: Optional[dict] = None,
        headers: Optional[dict] = None,
        retries: int = 3,
    ) -> httpx.Response:
        """Effectue une requête avec retry exponentiel."""
        if self.dry_run:
            self.request_count += 1
            return httpx.Response(204, text="dry-run")  # réponse factice
        url = path if path.startswith("http") else path
        last_exc: Optional[Exception] = None
        for attempt in range(retries):
            try:
                resp = await self._client.request(
                    method,
                    url,
                    json=json_body if method in ("POST", "PATCH", "PUT") else None,
                    params=params,
                    headers=self._headers(headers),
                )
                self.request_count += 1
                # Retry sur 5xx et 429
                if resp.status_code >= 500 or resp.status_code == 429:
                    if attempt < retries - 1:
                        wait = 0.5 * (2 ** attempt)
                        warn(f"HTTP {resp.status_code} — retry dans {wait}s")
                        await asyncio.sleep(wait)
                        continue
                if resp.status_code >= 400 and resp.status_code != 409:
                    self.error_count += 1
                return resp
            except (httpx.ConnectError, httpx.ReadTimeout, httpx.RemoteProtocolError) as e:
                last_exc = e
                if attempt < retries - 1:
                    wait = 0.5 * (2 ** attempt)
                    warn(f"{type(e).__name__} — retry dans {wait}s")
                    await asyncio.sleep(wait)
                    continue
                self.error_count += 1
                raise
        raise last_exc or RuntimeError("request failed")

    async def get(self, path: str, **kw) -> httpx.Response:
        return await self._request("GET", path, **kw)

    async def post(self, path: str, body: Any = None, **kw) -> httpx.Response:
        kw["json_body"] = body
        return await self._request("POST", path, **kw)

    async def patch(self, path: str, body: Any = None, **kw) -> httpx.Response:
        kw["json_body"] = body
        return await self._request("PATCH", path, **kw)

    async def put(self, path: str, body: Any = None, **kw) -> httpx.Response:
        kw["json_body"] = body
        return await self._request("PUT", path, **kw)

    async def delete(self, path: str, **kw) -> httpx.Response:
        return await self._request("DELETE", path, **kw)

    @staticmethod
    def ensure_ok(resp: httpx.Response, ctx: str, *, show_full: bool = True) -> dict:
        """Vérifie le status code et retourne le JSON.

        v9.4 fix — Sur erreur, affiche le CORPS COMPLET de la réponse (pas juste 200 chars)
        pour faciliter le debug. Inclut le correlationId si présent.
        """
        if resp.status_code < 200 or resp.status_code >= 300:
            try:
                err_body = resp.json()
                err_str = json.dumps(err_body, indent=2, ensure_ascii=False)
            except Exception:
                err_body = resp.text
                err_str = str(err_body)
            # Extraire le correlationId pour faciliter le lookup dans les logs backend
            corr_id = ""
            if isinstance(err_body, dict):
                corr_id = err_body.get("correlationId", "")
            # Message d'erreur détaillé
            detail_line = ""
            if isinstance(err_body, dict) and err_body.get("detail"):
                detail_line = f"\n  Détail: {err_body['detail']}"
            hint = ""
            if resp.status_code == 500:
                hint = ("\n  💡 HTTP 500 = bug backend. Causes possibles :"
                        "\n     1. Backend récemment modifié avec un bug"
                        "\n     2. Variable d'env manquante (JWT_SECRET, DATABASE_URL, ...)"
                        "\n     3. Schema DB désynchronisé (Flyway)"
                        f"\n  → Vérifiez les logs backend avec le correlationId: {corr_id}")
            elif resp.status_code == 409:
                hint = "\n  💡 HTTP 409 = conflit (déjà existant). Normal si le seed est relancé."
            elif resp.status_code == 422:
                hint = "\n  💡 HTTP 422 = validation échouée. Vérifiez les champs du payload."
            raise RuntimeError(f"{ctx} failed (HTTP {resp.status_code}):{detail_line}{hint}\n  Réponse complète:\n{err_str[:2000]}")
        if not resp.text or resp.status_code == 204:
            return {}
        try:
            return resp.json()
        except ValueError:
            return {"raw": resp.text}

    def idem_key(self, step: str, index: int) -> str:
        """Idempotency-Key stable basé sur (company_id, step, index).

        Permet de re-exécuter le seed sans créer de doublons (le backend est idempotent
        sur Idempotency-Key pour 24h).
        """
        cid = (self.company_id or "nocompany")[:8]
        return f"seed-{cid}-{step}-{index:04d}"


# ──────────────────────────────────────────────────────────────────────────────
# Résultat de seed (pour le rapport final)
# ──────────────────────────────────────────────────────────────────────────────

@dataclass(slots=True)
class SeedResult:
    """Récapitulatif des artefacts créés pendant le seed."""
    company_id: str = ""
    company_name: str = ""
    user_email: str = ""
    user_password: str = ""
    wizard_step: int = 0
    wizard_completed: bool = False
    modules_activated: list[str] = field(default_factory=list)
    chart_of_accounts_count: int = 0
    journals_created: list[str] = field(default_factory=list)
    fiscal_year_id: str = ""
    sequences_created: int = 0
    tax_rules_created: int = 0
    # Entités métier
    clients_count: int = 0
    suppliers_count: int = 0
    inventory_items_count: int = 0
    fixed_assets_count: int = 0
    sales_invoices_count: int = 0
    purchase_invoices_count: int = 0
    purchase_orders_count: int = 0
    expense_reports_count: int = 0
    employees_count: int = 0
    payroll_runs_count: int = 0
    journal_entries_count: int = 0
    # PDFs générés
    pdfs_generated: list[dict] = field(default_factory=list)
    # États financiers
    balance_sheet: Optional[dict] = None
    income_statement: Optional[dict] = None
    dashboard: Optional[dict] = None
    # Audit
    audit_trail_count: int = 0
    # Timing
    duration_seconds: float = 0.0
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    # Erreurs (non fatales)
    warnings: list[str] = field(default_factory=list)


# (La suite du script — étapes métier — sera dans seed_modern_steps.py pour rester modulaire)


# ──────────────────────────────────────────────────────────────────────────────
# Point d'entrée principal (main)
# ──────────────────────────────────────────────────────────────────────────────

async def run_seed(args: argparse.Namespace) -> SeedResult:
    """Point d'entrée principal asynchrone du seed."""
    profile = PROFILES[args.profile]
    result = SeedResult()
    result.started_at = datetime.now()

    # Bannière de démarrage
    banner(
        f"{profile.icon}  Seed JOAccountant — {profile.label}",
        f"Entreprise : {profile.company_name} · Devise : {profile.currency} · {profile.country}",
        color=profile.accent_color,
    )
    info(f"Base URL : {args.base_url}")
    info(f"Profil   : {args.profile} (business_type={profile.business_type_code})")
    info(f"Volume   : {args.invoices} factures vente, {args.purchase_invoices} achat, "
        f"{args.employees} employés")
    if args.dry_run:
        warn("Mode DRY-RUN — aucune donnée ne sera créée")
    if args.no_pdf:
        info("Génération PDF désactivée (--no-pdf)")

    # 0. Attendre le backend
    if not args.skip_wait:
        step_panel("0/12 — Attente du backend", color="dim")
        from seed_modern_steps import wait_for_backend_sync
        if not wait_for_backend_sync(args.base_url, timeout_s=60):
            err("Backend non disponible après 60s")
            raise SystemExit(1)
        ok("Backend prêt")

    # Initialiser le client HTTP
    suffix = datetime.now().strftime("%Y%m%d%H%M%S")
    output_dir = Path(args.output_dir) / f"seed_{profile.key}_{suffix}"
    output_dir.mkdir(parents=True, exist_ok=True)

    # Helper: wrapper qui catch les erreurs d'une étape sans faire planter le seed
    async def safe_step(step_name: str, coro, *, fatal: bool = False):
        """Exécute une étape. Si fatal=False, catch l'erreur et continue."""
        try:
            return await coro
        except Exception as e:
            if fatal:
                err(f"Étape FATALE échouée : {step_name}")
                console.print(f"[red]{e}[/]")
                raise
            else:
                err(f"Étape '{step_name}' échouée (non bloquant) : {e}")
                result.warnings.append(f"{step_name}: {str(e)[:200]}")
                return None

    async with ApiClient(args.base_url, dry_run=args.dry_run) as api:
        from seed_modern_steps import (
            step_register_user, step_login, step_create_company,
            step_get_accounts, step_create_third_parties, step_create_inventory_items,
            step_create_fixed_assets, step_create_invoices, step_create_purchase_invoices,
            step_create_capital_opening, step_create_purchase_orders,
            step_create_expense_reports, step_create_employees_and_payroll,
            step_create_journal_entries, step_create_bank_movements,
        )
        from seed_modern_showcase import (
            showcase_dashboard, showcase_financial_statements, showcase_pdf_suite,
            showcase_audit_trail, showcase_search, showcase_modules, showcase_fx_operations,
        )
        from seed_modern_report import print_terminal_summary, print_qr_code, generate_html_report

        # Pre-flight: list existing companies for this user (debug MaxCompaniesGuard)
        if args.list_companies:
            step_panel("Pre-flight — Companies existantes", color="dim")
            try:
                # Register/login first to get a token
                email, password = await step_register_user(api, profile, suffix)
                await step_login(api, email, password)
                resp = await api.get("/api/v1/companies")
                if resp.status_code < 300:
                    companies = resp.json()
                    info(f"Companies existantes pour cet utilisateur : {len(companies)}")
                    for c in companies:
                        console.print(f"  [dim]• {c.get('name', '?')} (id={c.get('id', '?')[:8]}...)[/]")
                else:
                    warn(f"Impossible de lister les companies : HTTP {resp.status_code}")
            except Exception as e:
                err(f"Pre-flight échoué : {e}")
            return result

        # 1. Register + login (FATAL — sans utilisateur on ne peut rien faire)
        step_panel("1/12 — Création utilisateur + login", color=profile.accent_color)
        try:
            email, password = await step_register_user(api, profile, suffix)
            await step_login(api, email, password)
            result.user_email = email
            result.user_password = password
        except Exception as e:
            err(f"Étape 1 FATALE — Register/login échoué : {e}")
            console.print(f"[red]{e}[/]")
            result.warnings.append(f"register/login: {str(e)[:200]}")
            # Générer quand même le rapport partiel
            result.finished_at = datetime.now()
            result.duration_seconds = (result.finished_at - result.started_at).total_seconds()
            print_terminal_summary(result, profile)
            return result

        # 2. Création entreprise + wizard complet (FATAL — sans company on ne peut rien faire)
        step_panel("2/12 — Création entreprise + wizard V2 (4 étapes)", color=profile.accent_color)
        try:
            await step_create_company(api, profile, result)
        except Exception as e:
            err(f"Étape 2 FATALE — Création entreprise échouée : {e}")
            console.print(f"[red]{e}[/]")
            result.warnings.append(f"create_company: {str(e)[:300]}")
            result.finished_at = datetime.now()
            result.duration_seconds = (result.finished_at - result.started_at).total_seconds()
            print_terminal_summary(result, profile)
            print_qr_code(args.base_url, result.user_email, result.user_password)
            html_path = output_dir / "seed_report.html"
            generate_html_report(result, profile, args.base_url, html_path)
            creds_path = output_dir / "seed_credentials.json"
            creds_path.write_text(json.dumps({
                "company_id": result.company_id,
                "company_name": result.company_name,
                "user_email": result.user_email,
                "user_password": result.user_password,
                "profile": profile.key,
                "base_url": args.base_url,
                "error": str(e)[:500],
            }, indent=2, ensure_ascii=False), encoding="utf-8")
            return result

        # 3. Plan comptable (non-fatal — fallback sur codes standards)
        step_panel("3/12 — Récupération du plan comptable", color=profile.accent_color)
        accounts = await safe_step("get_accounts",
            step_get_accounts(api, result.company_id)) or {}

        # 4. Tiers (clients + fournisseurs)
        step_panel("4/12 — Création des tiers (clients + fournisseurs)", color=profile.accent_color)
        parties = await safe_step("create_third_parties",
            step_create_third_parties(api, result.company_id, profile, result)) or {"clients": [], "suppliers": []}
        clients = parties.get("clients", [])
        suppliers = parties.get("suppliers", [])

        # 5. Articles de stock
        step_panel("5/12 — Création des articles de stock", color=profile.accent_color)
        articles = await safe_step("create_inventory_items",
            step_create_inventory_items(api, result.company_id, profile, result, accounts)) or []

        # 6. Immobilisations
        step_panel("6/12 — Création des immobilisations", color=profile.accent_color)
        await safe_step("create_fixed_assets",
            step_create_fixed_assets(api, result.company_id, profile, result, accounts))

        # 7. Capital d'ouverture
        step_panel("7/12 — Écriture de capital d'ouverture", color=profile.accent_color)
        await safe_step("create_capital_opening",
            step_create_capital_opening(api, result.company_id, accounts, profile, result))

        # 8. Commandes fournisseurs
        step_panel("8/12 — Bons de commande fournisseurs", color=profile.accent_color)
        await safe_step("create_purchase_orders",
            step_create_purchase_orders(api, result.company_id, profile, suppliers, articles, result,
                                         n_pos=args.purchase_orders))

        # 9. Factures d'achat
        step_panel("9/12 — Factures d'achat (PURCHASE)", color=profile.accent_color)
        purchase_invoices = await safe_step("create_purchase_invoices",
            step_create_purchase_invoices(api, result.company_id, profile, suppliers, articles, result,
                                           n_invoices=args.purchase_invoices)) or []

        # 10. Factures de vente
        step_panel("10/12 — Factures de vente (SALES)", color=profile.accent_color)
        sales_invoices = await safe_step("create_invoices",
            step_create_invoices(api, result.company_id, profile, clients, articles, result,
                                  n_invoices=args.invoices)) or []

        # 11. Notes de frais + employés + paie
        step_panel("11/12 — Notes de frais + employés + paie", color=profile.accent_color)
        await safe_step("create_expense_reports",
            step_create_expense_reports(api, result.company_id, accounts, profile, result,
                                         n_expenses=args.expenses))
        await safe_step("create_employees_and_payroll",
            step_create_employees_and_payroll(api, result.company_id, profile, result, accounts,
                                               n_employees=args.employees))
        await safe_step("create_journal_entries",
            step_create_journal_entries(api, result.company_id, accounts, profile, result,
                                         n_entries=args.entries))
        await safe_step("create_bank_movements",
            step_create_bank_movements(api, result.company_id, accounts, profile, result,
                                        n_movements=args.bank_movements))

        # 12. Showcase — dashboard, états financiers, PDFs, audit, search (tous non-fatal)
        step_panel("12/12 — Showcase : dashboard + états financiers + PDFs", color="bright_magenta")
        await safe_step("showcase_modules", showcase_modules(api, result.company_id, result))
        await safe_step("showcase_dashboard", showcase_dashboard(api, result.company_id, result))
        await safe_step("showcase_financial_statements", showcase_financial_statements(api, result.company_id, result))
        await safe_step("showcase_fx_operations", showcase_fx_operations(api, result.company_id, profile))
        await safe_step("showcase_search", showcase_search(api, result.company_id, profile))
        if not args.no_pdf and sales_invoices:
            await safe_step("showcase_pdf_suite", showcase_pdf_suite(api, result.company_id, sales_invoices, result, output_dir))
        await safe_step("showcase_audit_trail", showcase_audit_trail(api, result.company_id, result))

    # Finalisation
    result.finished_at = datetime.now()
    result.duration_seconds = (result.finished_at - result.started_at).total_seconds()

    # Affichage du résumé terminal
    print_terminal_summary(result, profile)

    # QR code de connexion
    print_qr_code(args.base_url, result.user_email, result.user_password)

    # Génération du rapport HTML
    html_path = output_dir / "seed_report.html"
    generate_html_report(result, profile, args.base_url, html_path)

    # Sauvegarde des credentials JSON
    creds_path = output_dir / "seed_credentials.json"
    creds_path.write_text(json.dumps({
        "company_id": result.company_id,
        "company_name": result.company_name,
        "user_email": result.user_email,
        "user_password": result.user_password,
        "profile": profile.key,
        "base_url": args.base_url,
        "started_at": result.started_at.isoformat(),
        "duration_seconds": result.duration_seconds,
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    ok(f"🔑 Credentials sauvegardés : {creds_path}")

    return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="🌱 Seed moderne JOAccountant — création d'entreprise de démonstration",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemples :
  # Seed par défaut (commerce électronique HT)
  python3 seed_modern.py --base-url http://localhost:8080

  # Profil commerce de détail alimentaire
  python3 seed_modern.py --profile retail

  # Profil ONG humanitaire
  python3 seed_modern.py --profile ngo

  # Volume important
  python3 seed_modern.py --invoices 80 --purchase-invoices 30 --employees 10

  # Sans PDF (plus rapide)
  python3 seed_modern.py --no-pdf

  # Dry-run (simule sans créer)
  python3 seed_modern.py --dry-run
        """,
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"URL backend (défaut: {DEFAULT_BASE_URL})")
    parser.add_argument("--profile", choices=list(PROFILES.keys()), default="electronics",
                        help="Profil métier (défaut: electronics)")
    parser.add_argument("--invoices", type=int, default=20,
                        help="Nombre de factures de vente (défaut: 20)")
    parser.add_argument("--purchase-invoices", type=int, default=8,
                        help="Nombre de factures d'achat (défaut: 8)")
    parser.add_argument("--purchase-orders", type=int, default=4,
                        help="Nombre de bons de commande (défaut: 4)")
    parser.add_argument("--expenses", type=int, default=5,
                        help="Nombre de notes de frais (défaut: 5)")
    parser.add_argument("--employees", type=int, default=4,
                        help="Nombre d'employés (défaut: 4)")
    parser.add_argument("--entries", type=int, default=8,
                        help="Nombre d'écritures manuelles OD (défaut: 8)")
    parser.add_argument("--bank-movements", type=int, default=6,
                        help="Nombre d'écritures bancaires BQ (défaut: 6)")
    parser.add_argument("--output-dir", default="./seed_output",
                        help="Dossier de sortie pour PDFs et rapports (défaut: ./seed_output)")
    parser.add_argument("--skip-wait", action="store_true",
                        help="Ne pas attendre que le backend soit prêt (utile si déjà démarré)")
    parser.add_argument("--no-pdf", action="store_true",
                        help="Désactiver la génération de PDFs (plus rapide)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Mode simulation — aucune donnée créée")
    parser.add_argument("--list-companies", action="store_true",
                        help="Lister les companies existantes pour cet utilisateur (debug MaxCompaniesGuard)")
    args = parser.parse_args()

    try:
        result = asyncio.run(run_seed(args))
        # Exit code : 0 si succès, 1 si erreurs non fatales
        return 0 if not result.warnings else 0  # les warnings ne font pas échouer
    except KeyboardInterrupt:
        err("Interrompu par l'utilisateur")
        return 130
    except Exception as e:
        err(f"Erreur fatale : {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())

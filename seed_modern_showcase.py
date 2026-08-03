#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_modern_showcase.py — Étapes "showcase" qui démontrent les modules backend
avancés (PDF, dashboard, états financiers, audit, search).

Importé par `seed_modern.py`. Ces étapes ne créent pas de données, mais appellent
les endpoints GET qui génèrent des documents ou agrègent des KPIs.
"""

from __future__ import annotations

import asyncio
import base64
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from rich.table import Table

from seed_modern import ApiClient, SeedResult, info, ok, warn, err
from seed_modern import console


# ──────────────────────────────────────────────────────────────────────────────
# 1. Dashboard agrégé (GET /reporting/dashboard)
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_dashboard(
    api: ApiClient,
    company_id: str,
    result: SeedResult,
) -> None:
    """Récupère le dashboard agrégé (KPIs : CA, marges, en cours, etc.)."""
    resp = await api.get(f"/api/v1/companies/{company_id}/reporting/dashboard")
    if resp.status_code >= 400:
        warn(f"Dashboard non récupéré : HTTP {resp.status_code}")
        return
    data = resp.json()
    result.dashboard = data
    # Afficher un tableau des KPIs
    table = Table(title="📊 Dashboard — KPIs agrégés", border_style="bright_cyan", show_lines=False)
    table.add_column("KPI", style="cyan", no_wrap=True)
    table.add_column("Valeur", style="green", justify="right")
    # KPIs communs (varie selon backend)
    kpi_fields = [
        ("totalRevenue", "Chiffre d'affaires"),
        ("totalExpenses", "Total charges"),
        ("netIncome", "Résultat net"),
        ("grossMargin", "Marge brute"),
        ("operatingMargin", "Marge opérationnelle"),
        ("outstandingInvoices", "Factures en attente"),
        ("overdueInvoices", "Factures échues"),
        ("cashPosition", "Trésorerie"),
        ("inventoryValue", "Valeur stock"),
        ("monthlyBurn", "Burn rate mensuel"),
        ("customerCount", "Clients"),
        ("supplierCount", "Fournisseurs"),
    ]
    any_kpi = False
    for key, label in kpi_fields:
        val = data.get(key)
        if val is not None:
            any_kpi = True
            if isinstance(val, (int, float)):
                table.add_row(label, f"{val:,.2f}")
            else:
                table.add_row(label, str(val))
    if any_kpi:
        console.print(table)
    else:
        # Si le format est différent, afficher les clés disponibles
        info(f"Dashboard disponible avec clés : {list(data.keys())[:10]}")


# ──────────────────────────────────────────────────────────────────────────────
# 2. États financiers (balance-sheet, income-statement, cash-flow)
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_financial_statements(
    api: ApiClient,
    company_id: str,
    result: SeedResult,
) -> None:
    """Récupère le bilan + compte de résultat + cash-flow."""
    statements = [
        ("balance-sheet", "Bilan comptable"),
        ("income-statement", "Compte de résultat"),
        ("cash-flow-statement", "Tableau de flux de trésorerie"),
    ]
    for endpoint, label in statements:
        resp = await api.get(f"/api/v1/companies/{company_id}/financial-statements/{endpoint}")
        if resp.status_code >= 400:
            warn(f"{label} non récupéré : HTTP {resp.status_code}")
            continue
        data = resp.json()
        # Stocker dans le résultat
        if endpoint == "balance-sheet":
            result.balance_sheet = data
        elif endpoint == "income-statement":
            result.income_statement = data
        # Afficher un résumé
        _print_statement_summary(label, data)


def _print_statement_summary(label: str, data: dict) -> None:
    """Affiche un résumé compact d'un état financier."""
    table = Table(title=f"📋 {label}", border_style="bright_blue", show_lines=False)
    table.add_column("Poste", style="cyan")
    table.add_column("Montant", style="green", justify="right")

    # Format générique : on cherche les clés "total*", "assets", "liabilities", etc.
    found = False
    if isinstance(data, dict):
        for key, val in data.items():
            if isinstance(val, (int, float)) and any(
                kw in key.lower() for kw in ["total", "assets", "liabilities", "equity", "revenue", "expense", "net"]
            ):
                table.add_row(key.replace("_", " ").title(), f"{val:,.2f}")
                found = True
            elif isinstance(val, dict):
                # Sous-objet — extraire les totaux
                for sub_key, sub_val in val.items():
                    if isinstance(sub_val, (int, float)) and any(
                        kw in sub_key.lower() for kw in ["total", "amount"]
                    ):
                        table.add_row(f"{key} → {sub_key}".replace("_", " ").title(),
                                      f"{sub_val:,.2f}")
                        found = True
    if found:
        console.print(table)
    else:
        info(f"{label} disponible (clés : {list(data.keys())[:5] if isinstance(data, dict) else type(data).__name__})")


# ──────────────────────────────────────────────────────────────────────────────
# 3. Suite PDF — téléchargement de documents de démonstration
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_pdf_suite(
    api: ApiClient,
    company_id: str,
    invoices: list[dict],
    result: SeedResult,
    output_dir: Path,
) -> None:
    """Télécharge une suite de PDFs de démonstration.

    Génère :
    - 1 PDF de facture de vente (première facture disponible)
    - 1 Factur-X XML (facture électronique européenne)
    - 1 PDF/A-3 avec Factur-X embarqué
    - 1 PDF de bilan
    - 1 PDF de compte de résultat
    - 1 PDF d'aged-balance clients
    - 1 PDF de déclaration TVA
    - 1 PDF de projection IS
    """
    pdf_dir = output_dir / "pdfs"
    pdf_dir.mkdir(parents=True, exist_ok=True)

    pdfs_to_download: list[dict] = []

    # 1. PDF facture (si on a des factures — préférer une facture ISSUED/PAID/RECEIVED)
    # v9.4 fix — Le backend refuse de générer le PDF d'une facture DRAFT (409 INVOICE_NOT_ISSUED).
    # On cherche donc une facture ISSUED/PAID/RECEIVED dans la liste.
    # Si toutes sont DRAFT, on en émet une (DRAFT→ISSUED) avant de générer le PDF.
    issued_invoice = None
    # Debug: show all invoice statuses
    if invoices:
        from seed_modern import info as _info
        statuses = [inv.get("status", "?") for inv in invoices]
        _info(f"Factures disponibles: {len(invoices)} (statuses: {statuses})")
    for inv in invoices:
        if str(inv.get("status", "")).upper() in ("ISSUED", "PAID", "RECEIVED"):
            issued_invoice = inv
            break
    if not issued_invoice and invoices:
        # Si aucune n'est ISSUED, on trouve une DRAFT et on l'émet
        for inv in invoices:
            if str(inv.get("status", "DRAFT")).upper() == "DRAFT":
                try:
                    resp = await api.post(f"/api/v1/companies/{company_id}/invoices/{inv['id']}/issue")
                    if resp.status_code < 300:
                        inv["status"] = "ISSUED"
                        issued_invoice = inv
                        break
                    elif resp.status_code == 404:
                        # v9.4 fix — Si l'issue retourne 404, la facture n'existe pas (timing issue)
                        # On continue avec la suivante
                        continue
                except Exception:
                    pass
    if issued_invoice:
        inv_id = issued_invoice["id"]
        pdfs_to_download.append({
            "label": f"Facture vente #{inv_id[:8]}",
            "path": f"/api/v1/companies/{company_id}/invoices/{inv_id}/pdf",
            "filename": f"invoice-{inv_id[:8]}.pdf",
            "type": "PDF",
        })
        # 2. Factur-X XML
        pdfs_to_download.append({
            "label": f"Factur-X XML #{inv_id[:8]}",
            "path": f"/api/v1/companies/{company_id}/invoices/{inv_id}/factur-x",
            "filename": f"factur-x-{inv_id[:8]}.xml",
            "type": "XML",
        })
        # 3. PDF/A-3 avec Factur-X embarqué
        pdfs_to_download.append({
            "label": f"PDF/A-3 Factur-X #{inv_id[:8]}",
            "path": f"/api/v1/companies/{company_id}/invoices/{inv_id}/factur-x-pdf",
            "filename": f"factur-x-pdf-{inv_id[:8]}.pdf",
            "type": "PDF",
        })

    # 4. Bilan PDF
    pdfs_to_download.append({
        "label": "Bilan comptable",
        "path": f"/api/v1/companies/{company_id}/financial-statements/balance-sheet/pdf",
        "filename": "balance-sheet.pdf",
        "type": "PDF",
    })
    # 5. Compte de résultat PDF
    pdfs_to_download.append({
        "label": "Compte de résultat",
        "path": f"/api/v1/companies/{company_id}/financial-statements/income-statement/pdf",
        "filename": "income-statement.pdf",
        "type": "PDF",
    })
    # 6. Cash-flow PDF
    pdfs_to_download.append({
        "label": "Tableau de flux de trésorerie",
        "path": f"/api/v1/companies/{company_id}/financial-statements/cash-flow-statement/pdf",
        "filename": "cash-flow-statement.pdf",
        "type": "PDF",
    })
    # 7. Aged-balance clients PDF (requires ?type=receivables)
    pdfs_to_download.append({
        "label": "Balance âgée clients",
        "path": f"/api/v1/companies/{company_id}/reporting/aged-balance/pdf?type=receivables",
        "filename": "aged-balance-customers.pdf",
        "type": "PDF",
    })
    # 8. Déclaration TVA PDF (requires ?taxType=VAT&from=...&to=...)
    fy_start_str = "2025-10-01"
    fy_end_str = "2026-09-30"
    pdfs_to_download.append({
        "label": "Déclaration TVA (PDF)",
        "path": f"/api/v1/companies/{company_id}/tax/declarations/pdf?taxType=VAT&from={fy_start_str}&to={fy_end_str}",
        "filename": "vat-declaration.pdf",
        "type": "PDF",
    })
    # 9. Projection IS PDF (requires ?from=...&to=...)
    pdfs_to_download.append({
        "label": "Projection Impôt Société",
        "path": f"/api/v1/companies/{company_id}/tax/corporate-tax/projection/pdf?from={fy_start_str}&to={fy_end_str}",
        "filename": "corporate-tax-projection.pdf",
        "type": "PDF",
    })

    downloaded = 0
    for pdf_spec in pdfs_to_download:
        try:
            resp = await api.get(pdf_spec["path"])
            if resp.status_code < 300 and resp.content:
                pdf_path = pdf_dir / pdf_spec["filename"]
                pdf_path.write_bytes(resp.content)
                result.pdfs_generated.append({
                    "label": pdf_spec["label"],
                    "filename": pdf_spec["filename"],
                    "size_bytes": len(resp.content),
                    "type": pdf_spec["type"],
                })
                downloaded += 1
                ok(f"📄 {pdf_spec['label']} ({len(resp.content):,} bytes)")
            else:
                warn(f"{pdf_spec['label']} non téléchargé : HTTP {resp.status_code}")
        except Exception as e:
            warn(f"{pdf_spec['label']} échoué : {e}")

    info(f"{downloaded}/{len(pdfs_to_download)} documents téléchargés dans {pdf_dir}")


# ──────────────────────────────────────────────────────────────────────────────
# 4. Audit trail (GET /audit-trail)
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_audit_trail(
    api: ApiClient,
    company_id: str,
    result: SeedResult,
) -> None:
    """Vérifie que l'audit trail a enregistré toutes les actions."""
    resp = await api.get(f"/api/v1/companies/{company_id}/audit-trail?page=0&size=5")
    if resp.status_code >= 400:
        warn(f"Audit trail non récupéré : HTTP {resp.status_code}")
        return
    data = resp.json()
    # La réponse peut être paginée
    if isinstance(data, dict):
        entries = data.get("content", data.get("entries", []))
        total = data.get("totalElements", len(entries))
    else:
        entries = data
        total = len(entries)
    result.audit_trail_count = total
    ok(f"🔎 Audit trail : {total} entrées enregistrées (5 plus récentes affichées)")
    # Afficher les 5 plus récentes
    if entries:
        table = Table(title="Dernières actions auditées", border_style="bright_yellow", show_lines=False)
        table.add_column("Timestamp", style="dim")
        table.add_column("Action", style="cyan")
        table.add_column("Utilisateur", style="green")
        table.add_column("Entité", style="magenta")
        for entry in entries[:5]:
            ts = entry.get("timestamp", entry.get("createdAt", "?"))
            action = entry.get("action", entry.get("eventType", "?"))
            user = entry.get("userId", entry.get("userEmail", "?"))[:8] if entry.get("userId") else "?"
            entity = entry.get("entityType", entry.get("entity", "?"))
            table.add_row(str(ts)[:19], str(action), str(user), str(entity))
        console.print(table)


# ──────────────────────────────────────────────────────────────────────────────
# 5. Search global (GET /search)
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_search(
    api: ApiClient,
    company_id: str,
    profile,
) -> None:
    """Démontre la recherche globale (/search)."""
    # Recherche sur un mot-clé pertinent selon le profil
    queries = ["facture", "client", profile.company_name.split()[0]]
    for q in queries[:2]:  # 2 recherches max
        resp = await api.get(f"/api/v1/companies/{company_id}/search?q={q}&limit=5")
        if resp.status_code >= 400:
            warn(f"Search '{q}' échoué : HTTP {resp.status_code}")
            continue
        data = resp.json()
        results = data if isinstance(data, list) else data.get("results", data.get("content", []))
        ok(f"🔍 Search '{q}' → {len(results)} résultats")


# ──────────────────────────────────────────────────────────────────────────────
# 6. Modules activés (GET /companies/{id}/modules)
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_modules(
    api: ApiClient,
    company_id: str,
    result: SeedResult,
) -> None:
    """Affiche la liste des modules activés pour cette entreprise."""
    resp = await api.get(f"/api/v1/companies/{company_id}/modules")
    if resp.status_code >= 400:
        warn(f"Modules non récupérés : HTTP {resp.status_code}")
        return
    data = resp.json()
    modules = data if isinstance(data, list) else data.get("modules", [])
    ok(f"📦 {len(modules)} modules activés pour cette entreprise")
    # Afficher un tableau compact
    if modules:
        table = Table(title="Modules activés", border_style="bright_green", show_lines=False, padding=(0, 1))
        table.add_column("Code", style="cyan", no_wrap=True)
        table.add_column("Statut", style="green")
        for mod in modules[:15]:  # top 15
            code = mod.get("moduleCode", mod.get("code", "?"))
            active = mod.get("active", mod.get("enabled", True))
            status = "✓ actif" if active else "✗ inactif"
            table.add_row(str(code), status)
        if len(modules) > 15:
            table.add_row(f"... +{len(modules) - 15} autres", "")
        console.print(table)


# ──────────────────────────────────────────────────────────────────────────────
# 7. FX Operations (taux de change)
# ──────────────────────────────────────────────────────────────────────────────

async def showcase_fx_operations(
    api: ApiClient,
    company_id: str,
    profile,
) -> None:
    """Crée un taux de change HTG→USD (utile car les fournisseurs sont internationaux)."""
    if profile.currency == "USD":
        return  # pas besoin de FX si la devise fonctionnelle est USD
    # Taux HTG → USD (approximatif)
    body = {
        "fromCurrency": "USD",
        "toCurrency": profile.currency,
        "rate": 132.50,  # 1 USD = 132.50 HTG (approximatif)
        "rateDate": datetime.now().date().isoformat(),
    }
    resp = await api.post(
        f"/api/v1/companies/{company_id}/fx-operations/rates",
        body,
        headers={"Idempotency-Key": api.idem_key("fx", 0)},
    )
    if resp.status_code < 300:
        ok("💱 Taux de change USD→HTG créé (132.50)")
    elif resp.status_code == 409:
        ok("💱 Taux de change USD→HTG déjà existant (idempotent)")
    else:
        warn(f"FX rate non créé : HTTP {resp.status_code}")

    # Démonstration : convertir un montant
    resp = await api.get(
        f"/api/v1/companies/{company_id}/fx-operations/convert"
        f"?fromAmount=1000&fromCurrency=USD&toCurrency={profile.currency}"
    )
    if resp.status_code < 300:
        data = resp.json()
        converted = data.get("toAmount", data.get("convertedAmount", "?"))
        ok(f"💱 Conversion : 1 000 USD = {converted:,} {profile.currency}")

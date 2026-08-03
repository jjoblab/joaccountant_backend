#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_modern_report.py — Génération du rapport HTML final + résumé terminal.

Importé par `seed_modern.py`. Produit :
  1. Un affichage terminal riche (tables, panneaux, arbre)
  2. Un fichier HTML standalone (seed_report.html) avec tous les artefacts
  3. Un QR code ASCII dans le terminal pour accès mobile rapide
"""

from __future__ import annotations

import base64
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from rich.panel import Panel
from rich.table import Table
from rich.tree import Tree
from rich.console import Group

from seed_modern import SeedResult, console, banner, ok, info, warn
from seed_modern import BusinessProfile  # type: ignore


# ──────────────────────────────────────────────────────────────────────────────
# 1. Résumé terminal riche
# ──────────────────────────────────────────────────────────────────────────────

def print_terminal_summary(result: SeedResult, profile: BusinessProfile) -> None:
    """Affiche un résumé complet et stylé dans le terminal."""
    banner("✓ Seed terminé", f"Durée : {result.duration_seconds:.1f}s · {profile.icon} {profile.label}",
           color=profile.accent_color)

    # Tableau principal des artefacts créés
    table = Table(
        title=f"📊 Entreprise : {result.company_name}",
        border_style=profile.accent_color,
        show_lines=True,
        title_style="bold",
    )
    table.add_column("Catégorie", style="cyan", no_wrap=True)
    table.add_column("Détail", style="white")
    table.add_column("Quantité", style="green", justify="right")

    # Identité
    table.add_row("🏢 Identité", f"{result.company_name}", "")
    table.add_row("   Company ID", result.company_id, "")
    table.add_row("   Utilisateur", result.user_email, "")
    table.add_row("   Mot de passe", result.user_password, "")

    # Wizard
    table.add_row("✨ Wizard", f"Étape {result.wizard_step}/4 · Complété: {'✓' if result.wizard_completed else '✗'}", "")
    table.add_row("   Modules activés", ", ".join(result.modules_activated[:5]) + ("..." if len(result.modules_activated) > 5 else ""),
                  str(len(result.modules_activated)))
    table.add_row("   Comptes PCN", "Plan comptable initialisé", str(result.chart_of_accounts_count))
    table.add_row("   Journaux", ", ".join(result.journals_created), str(len(result.journals_created)))
    table.add_row("   Séquences doc.", "Préfixes FAC/EC/FA/AV/BUL", str(result.sequences_created))
    table.add_row("   Règles TVA", "TVA 10% HT + TCA", str(result.tax_rules_created))

    # Entités métier
    table.add_row("👥 Tiers", f"Clients + Donors", str(result.clients_count))
    table.add_row("   Fournisseurs", f"Suppliers", str(result.suppliers_count))
    table.add_row("📦 Articles stock", f"SKU électroniques", str(result.inventory_items_count))
    table.add_row("🏗️ Immobilisations", f"Camions + bureaux + serveurs", str(result.fixed_assets_count))

    # Transactions
    table.add_row("💰 Factures vente", "Workflow issue/paid", str(result.sales_invoices_count))
    table.add_row("   Factures achat", "Direction PURCHASE", str(result.purchase_invoices_count))
    table.add_row("   Bons de commande", "Avec change-status", str(result.purchase_orders_count))
    table.add_row("🧾 Notes de frais", "Submit + approve", str(result.expense_reports_count))
    table.add_row("👥 Employés", "Salaires + positions", str(result.employees_count))
    table.add_row("   Campagnes paie", "Avril→Sept 2026", str(result.payroll_runs_count))

    # Écritures
    table.add_row("📒 Écritures comptables", "OD + BQ + capital ouverture", str(result.journal_entries_count))

    # Showcase
    table.add_row("📄 PDFs générés", "Factures + bilans + TVA + IS", str(len(result.pdfs_generated)))
    table.add_row("🔎 Audit trail", "Entrées enregistrées", str(result.audit_trail_count))

    # Timing
    table.add_row("⏱️ Performance",
                  f"Début: {result.started_at:%H:%M:%S} · Fin: {result.finished_at:%H:%M:%S}",
                  f"{result.duration_seconds:.1f}s")
    table.add_row("🌐 Requêtes API", f"Erreurs: {result.warnings.__len__() if hasattr(result.warnings, '__len__') else 0}",
                  "")

    console.print(table)

    # Détail des PDFs générés
    if result.pdfs_generated:
        pdf_table = Table(title="📄 Documents PDF/XML générés",
                          border_style="bright_magenta", show_lines=False)
        pdf_table.add_column("Document", style="cyan")
        pdf_table.add_column("Fichier", style="blue")
        pdf_table.add_column("Taille", style="green", justify="right")
        for pdf in result.pdfs_generated:
            size_kb = pdf["size_bytes"] / 1024
            pdf_table.add_row(pdf["label"], pdf["filename"], f"{size_kb:.1f} KB")
        console.print(pdf_table)


# ──────────────────────────────────────────────────────────────────────────────
# 2. QR Code ASCII (pour accès mobile)
# ──────────────────────────────────────────────────────────────────────────────

def print_qr_code(login_url: str, email: str, password: str) -> None:
    """Affiche un QR code ASCII contenant les credentials de login."""
    try:
        import qrcode
        # Construire une URL de login encodée
        credentials = f"joaccountant://login?email={email}&password={password}&url={login_url}"
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_L,
            box_size=1,
            border=1,
        )
        qr.add_data(credentials)
        qr.make(fit=True)
        # Afficher en ASCII (inversé pour terminal clair sur fond sombre)
        matrix = qr.get_matrix()
        ascii_qr = []
        for row in matrix:
            line = "".join("  " if cell else "██" for cell in row)
            ascii_qr.append(line)
        qr_text = "\n".join(ascii_qr)
        console.print(Panel(
            f"[dim]{qr_text}[/]\n\n[cyan]Scan pour te connecter rapidement[/]\n[dim]Email: {email}[/]",
            title="📱 QR Code de connexion",
            border_style="bright_green",
            padding=(1, 2),
        ))
    except ImportError:
        # qrcode non disponible — afficher juste les credentials
        console.print(Panel(
            f"[cyan]URL:[/] {login_url}\n[cyan]Email:[/] {email}\n[cyan]Password:[/] {password}",
            title="🔑 Credentials",
            border_style="bright_green",
            padding=(1, 2),
        ))


# ──────────────────────────────────────────────────────────────────────────────
# 3. Rapport HTML standalone
# ──────────────────────────────────────────────────────────────────────────────

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<title>Seed Report — {company_name}</title>
<style>
  body {{ font-family: 'Segoe UI', system-ui, sans-serif; margin: 0; padding: 0; background: #0f172a; color: #e2e8f0; }}
  .container {{ max-width: 1200px; margin: 0 auto; padding: 2rem; }}
  h1 {{ color: {accent}; border-bottom: 2px solid {accent}; padding-bottom: 0.5rem; }}
  h2 {{ color: #94a3b8; margin-top: 2rem; }}
  .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 1rem; margin: 1rem 0; }}
  .card {{ background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 1rem; }}
  .card h3 {{ margin: 0 0 0.5rem 0; color: {accent}; font-size: 0.9rem; text-transform: uppercase; }}
  .card .value {{ font-size: 1.8rem; font-weight: bold; color: #f1f5f9; }}
  .card .detail {{ font-size: 0.85rem; color: #64748b; margin-top: 0.25rem; }}
  table {{ width: 100%; border-collapse: collapse; margin: 1rem 0; background: #1e293b; border-radius: 8px; overflow: hidden; }}
  th {{ background: #334155; padding: 0.75rem; text-align: left; font-size: 0.85rem; text-transform: uppercase; color: #94a3b8; }}
  td {{ padding: 0.75rem; border-top: 1px solid #334155; font-size: 0.9rem; }}
  tr:hover td {{ background: #2d3748; }}
  .badge {{ display: inline-block; padding: 0.2rem 0.6rem; border-radius: 12px; font-size: 0.75rem; font-weight: 600; }}
  .badge-success {{ background: #064e3b; color: #34d399; }}
  .badge-info {{ background: #1e3a8a; color: #60a5fa; }}
  .badge-warning {{ background: #78350f; color: #fbbf24; }}
  .pdfs {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 0.75rem; margin: 1rem 0; }}
  .pdf-item {{ background: #1e293b; padding: 0.75rem; border-radius: 6px; border-left: 3px solid {accent}; }}
  .pdf-item .label {{ font-size: 0.9rem; color: #f1f5f9; }}
  .pdf-item .size {{ font-size: 0.75rem; color: #64748b; }}
  .footer {{ margin-top: 3rem; padding-top: 1rem; border-top: 1px solid #334155; color: #64748b; font-size: 0.85rem; }}
  .json-block {{ background: #0f172a; border: 1px solid #334155; border-radius: 6px; padding: 1rem; font-family: monospace; font-size: 0.8rem; overflow-x: auto; max-height: 300px; overflow-y: auto; }}
</style>
</head>
<body>
<div class="container">
  <h1>{icon} Seed Report — {company_name}</h1>
  <p style="color:#64748b;">Généré le {generated_at} · Durée: {duration:.1f}s · Profil: {profile_label}</p>

  <h2>🏢 Identité</h2>
  <div class="grid">
    <div class="card"><h3>Company ID</h3><div class="value" style="font-size:1rem;font-family:monospace;">{company_id}</div></div>
    <div class="card"><h3>Utilisateur</h3><div class="value" style="font-size:1rem;">{user_email}</div><div class="detail">Mot de passe: {user_password}</div></div>
    <div class="card"><h3>Profil métier</h3><div class="value" style="font-size:1rem;">{profile_label}</div><div class="detail">{business_type_code}</div></div>
    <div class="card"><h3>Wizard</h3><div class="value">{wizard_status}</div><div class="detail">{modules_count} modules activés</div></div>
  </div>

  <h2>📊 Données créées</h2>
  <div class="grid">
    <div class="card"><h3>Tiers (clients + donors)</h3><div class="value">{clients_count}</div></div>
    <div class="card"><h3>Fournisseurs</h3><div class="value">{suppliers_count}</div></div>
    <div class="card"><h3>Articles stock</h3><div class="value">{inventory_items_count}</div></div>
    <div class="card"><h3>Immobilisations</h3><div class="value">{fixed_assets_count}</div></div>
    <div class="card"><h3>Factures vente</h3><div class="value">{sales_invoices_count}</div></div>
    <div class="card"><h3>Factures achat</h3><div class="value">{purchase_invoices_count}</div></div>
    <div class="card"><h3>Bons de commande</h3><div class="value">{purchase_orders_count}</div></div>
    <div class="card"><h3>Notes de frais</h3><div class="value">{expense_reports_count}</div></div>
    <div class="card"><h3>Employés</h3><div class="value">{employees_count}</div></div>
    <div class="card"><h3>Campagnes paie</h3><div class="value">{payroll_runs_count}</div></div>
    <div class="card"><h3>Écritures comptables</h3><div class="value">{journal_entries_count}</div></div>
    <div class="card"><h3>Comptes PCN</h3><div class="value">{chart_of_accounts_count}</div></div>
  </div>

  <h2>📄 Documents PDF générés</h2>
  <div class="pdfs">
    {pdfs_html}
  </div>

  <h2>📦 Modules activés</h2>
  <div class="json-block">{modules_list}</div>

  <h2>🔎 Audit & Showcase</h2>
  <div class="grid">
    <div class="card"><h3>Entrées audit trail</h3><div class="value">{audit_trail_count}</div></div>
    <div class="card"><h3>Séquences doc.</h3><div class="value">{sequences_created}</div></div>
    <div class="card"><h3>Règles TVA</h3><div class="value">{tax_rules_created}</div></div>
    <div class="card"><h3>Journaux créés</h3><div class="value">{journals_count}</div></div>
  </div>

  {dashboard_section}

  <h2>🔑 Credentials</h2>
  <div class="card">
    <h3>Login</h3>
    <div class="value" style="font-size:1rem;">URL: {base_url}</div>
    <div class="detail">Email: {user_email}</div>
    <div class="detail">Password: {user_password}</div>
  </div>

  <div class="footer">
    <p>Seed généré par <code>seed_modern.py</code> v9.4 — JOAccountant</p>
    <p>Backend: {base_url} · Company: {company_id}</p>
  </div>
</div>
</body>
</html>"""


def generate_html_report(
    result: SeedResult,
    profile: BusinessProfile,
    base_url: str,
    output_path: Path,
) -> None:
    """Génère le rapport HTML standalone."""
    # Section dashboard si disponible
    dashboard_section = ""
    if result.dashboard:
        dashboard_section = f"""  <h2>📊 Dashboard agrégé</h2>
  <div class="json-block">{json.dumps(result.dashboard, indent=2, default=str, ensure_ascii=False)}</div>"""

    # PDFs HTML
    pdfs_html = ""
    for pdf in result.pdfs_generated:
        size_kb = pdf["size_bytes"] / 1024
        pdfs_html += (
            f'<div class="pdf-item">'
            f'<div class="label">{pdf["label"]}</div>'
            f'<div class="size">{pdf["filename"]} · {size_kb:.1f} KB</div>'
            f'</div>'
        )
    if not pdfs_html:
        pdfs_html = '<div class="detail">Aucun PDF généré</div>'

    # Modules list
    modules_list = ", ".join(f'<span class="badge badge-success">{m}</span>' for m in result.modules_activated)
    if not modules_list:
        modules_list = '<span class="badge badge-warning">Aucun module</span>'

    html = HTML_TEMPLATE.format(
        company_name=result.company_name,
        company_id=result.company_id,
        user_email=result.user_email,
        user_password=result.user_password,
        icon=profile.icon,
        profile_label=profile.label,
        business_type_code=profile.business_type_code,
        accent=profile.accent_color.replace("bright_", "#").replace("cyan", "#06b6d4").replace("green", "#10b981")
            .replace("magenta", "#ec4899").replace("blue", "#3b82f6") if hasattr(profile, "accent_color")
            else "#06b6d4",
        generated_at=datetime.now().strftime("%d/%m/%Y à %H:%M:%S"),
        duration=result.duration_seconds,
        wizard_status="✓ Complété" if result.wizard_completed else "✗ Incomplet",
        modules_count=len(result.modules_activated),
        clients_count=result.clients_count,
        suppliers_count=result.suppliers_count,
        inventory_items_count=result.inventory_items_count,
        fixed_assets_count=result.fixed_assets_count,
        sales_invoices_count=result.sales_invoices_count,
        purchase_invoices_count=result.purchase_invoices_count,
        purchase_orders_count=result.purchase_orders_count,
        expense_reports_count=result.expense_reports_count,
        employees_count=result.employees_count,
        payroll_runs_count=result.payroll_runs_count,
        journal_entries_count=result.journal_entries_count,
        chart_of_accounts_count=result.chart_of_accounts_count,
        pdfs_html=pdfs_html,
        modules_list=modules_list,
        audit_trail_count=result.audit_trail_count,
        sequences_created=result.sequences_created,
        tax_rules_created=result.tax_rules_created,
        journals_count=len(result.journals_created),
        dashboard_section=dashboard_section,
        base_url=base_url,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(html, encoding="utf-8")
    ok(f"📄 Rapport HTML généré : {output_path}")

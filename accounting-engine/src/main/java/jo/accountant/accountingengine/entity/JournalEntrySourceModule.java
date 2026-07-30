package jo.accountant.accountingengine.entity;

/**
 * Module d'origine d'une écriture comptable (§13 Phase 5).
 *
 * <p>Permet de tracer quel module a généré l'écriture — utile pour l'audit et pour empêcher
 * qu'un module modifie une écriture générée par un autre.
 *
 * <ul>
 *   <li>{@link #MANUAL} — saisie manuelle via le moteur comptable</li>
 *   <li>{@link #FIXED_ASSETS} — amortissement (Phase 8)</li>
 *   <li>{@link #INVENTORY} — COGS / variation de stock (Phase 9)</li>
 *   <li>{@link #INVOICING} — facturation client (Phase 12)</li>
 *   <li>{@link #FUNDS_GRANTS} — fonds dédiés (Phase 14)</li>
 *   <li>{@link #REVERSAL} — contre-passation d'une écriture existante</li>
 *   <li>{@link #PURCHASING} — facture fournisseur (restructuration 2026-07-24 — :purchasing)</li>
 *   <li>{@link #EXPENSES} — note de frais approuvée (restructuration 2026-07-24 — :expenses)</li>
 *   <li>{@link #PAYROLL} — paie consolidée (restructuration 2026-07-24 — :payroll)</li>
 * </ul>
 */
public enum JournalEntrySourceModule {
    MANUAL,
    FIXED_ASSETS,
    INVENTORY,
    INVOICING,
    FUNDS_GRANTS,
    REVERSAL,
    // Restructuration 2026-07-24 (suite) — 4 nouveaux modules bonus
    PURCHASING,
    EXPENSES,
    PAYROLL
}

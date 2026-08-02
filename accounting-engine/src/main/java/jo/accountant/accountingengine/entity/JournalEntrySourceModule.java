package jo.accountant.accountingengine.entity;

/**
 * Module d'origine d'une écriture comptable (§13.
 *
 * <p>Permet de tracer quel module a généré l'écriture — utile pour l'audit et pour empêcher
 * qu'un module modifie une écriture générée par un autre.
 *
 * <ul>
 * <li>{@link #MANUAL} — saisie manuelle via le moteur comptable</li>
 * <li>{@link #FIXED_ASSETS} — amortissement</li>
 * <li>{@link #INVENTORY} — COGS / variation de stock</li>
 * <li>{@link #INVOICING} — facturation client</li>
 * <li>{@link #FUNDS_GRANTS} — fonds dédiés</li>
 * <li>{@link #REVERSAL} — contre-passation d'une écriture existante</li>
 * <li>{@link #PURCHASING} — facture fournisseur (:purchasing)</li>
 * <li>{@link #EXPENSES} — note de frais approuvée (:expenses)</li>
 * <li>{@link #PAYROLL} — paie consolidée (:payroll)</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum JournalEntrySourceModule {
 MANUAL,
 FIXED_ASSETS,
 INVENTORY,
 INVOICING,
 FUNDS_GRANTS,
 REVERSAL,
 //(suite) — 4 nouveaux modules bonus
 PURCHASING,
 EXPENSES,
 PAYROLL
}

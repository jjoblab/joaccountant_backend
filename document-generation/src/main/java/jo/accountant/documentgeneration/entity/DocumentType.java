package jo.accountant.documentgeneration.entity;

/**
 * Type de document générable en PDF (§8, §13 Phase 11).
 *
 * <p>Chaque module producteur de documents imprimables ajoute une valeur ici.
 * Les valeurs présentes correspondent aux consommateurs identifiés :
 * <ul>
 *   <li>{@link #INVOICE} — facture client (Phase 12)</li>
 *   <li>{@link #CREDIT_NOTE} — avoir (Phase 12)</li>
 *   <li>{@link #DONATION_RECEIPT} — reçu de don (Phase 14)</li>
 *   <li>{@link #BALANCE_SHEET} — bilan (Phase 6 / 17)</li>
 *   <li>{@link #INCOME_STATEMENT} — compte de résultat (Phase 6 / 17)</li>
 *   <li>{@link #GENERAL_LEDGER} — grand livre (Phase 17)</li>
 *   <li>{@link #DONOR_REPORT} — rapport bailleur (Phase 14 / 17)</li>
 *   <li>{@link #PAYSLIP} — bulletin de paie (restructuration 2026-07-24 — module :payroll)</li>
 * </ul>
 *
 * <p>Note : la facture d'achat (module :purchasing) n'a pas de PDF au MVP — document interne,
 * pas envoyé à un tiers externe. Aucune valeur {@code PURCHASE_INVOICE} ici (§2.5 du prompt).
 */
public enum DocumentType {
    INVOICE,
    CREDIT_NOTE,
    DONATION_RECEIPT,
    BALANCE_SHEET,
    INCOME_STATEMENT,
    GENERAL_LEDGER,
    DONOR_REPORT,
    // Restructuration 2026-07-24 (suite) — module :payroll
    PAYSLIP
}

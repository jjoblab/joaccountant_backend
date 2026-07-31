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
 *
 * <p><b>step2-backend — Reports Hub (v2.4.0)</b> : 12 nouvelles valeurs ajoutées pour les
 * exports PDF du Reports Hub mobile. Le suffixe {@code _REPORT} distingue ces nouveaux
 * types de rapports (URLs dédiées {@code /.../{report}/pdf}) des types historiques
 * (URLs legacy {@code /reporting/exports/{statement}?format=pdf}).
 * <ul>
 *   <li>{@link #BALANCE_SHEET_REPORT} — bilan (endpoint dédié /financial-statements/balance-sheet/pdf)</li>
 *   <li>{@link #INCOME_STATEMENT_REPORT} — compte de résultat (endpoint dédié)</li>
 *   <li>{@link #CASH_FLOW_STATEMENT_REPORT} — tableau de flux de trésorerie (IAS 7 / TAFIRE)</li>
 *   <li>{@link #STATEMENT_OF_CHANGES_IN_EQUITY_REPORT} — tableau de variation des capitaux propres (IAS 1.106)</li>
 *   <li>{@link #TRIAL_BALANCE_REPORT} — balance générale</li>
 *   <li>{@link #LEDGER_REPORT} — grand livre par compte</li>
 *   <li>{@link #AGED_BALANCE_RECEIVABLES_REPORT} — balance âgée clients</li>
 *   <li>{@link #AGED_BALANCE_PAYABLES_REPORT} — balance âgée fournisseurs</li>
 *   <li>{@link #CORPORATE_TAX_PROJECTION_REPORT} — projection d'IS</li>
 *   <li>{@link #VAT_DECLARATION_REPORT} — déclaration TVA (Haïti art. 191 / France CA3)</li>
 *   <li>{@link #TCA_DECLARATION_REPORT} — déclaration TCA (Haïti art. 196)</li>
 *   <li>{@link #PAYROLL_SUMMARY_REPORT} — synthèse de paie agrégée par période</li>
 * </ul>
 *
 * <p><b>step7-backend — Reports Hub v2.5.0</b> : 2 nouveaux types pour les derniers
 * rapports manquants du Reports Hub mobile (LETTERING + CNSS_RETURN), pour atteindre
 * 19/19 rapports fonctionnels.
 * <ul>
 *   <li>{@link #LETTERING_REPORT} — liste des lettrages d'un tiers (endpoint dédié /third-parties/lettrage/pdf)</li>
 *   <li>{@link #CNSS_RETURN_REPORT} — bordereau CNSS/OFATMA/AST agrégé par employé sur une période (endpoint /payroll/cnss-return/pdf)</li>
 * </ul>
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
    PAYSLIP,
    // step2-backend — Reports Hub v2.4.0 : 12 nouveaux types pour les PDF dédiés
    BALANCE_SHEET_REPORT,
    INCOME_STATEMENT_REPORT,
    CASH_FLOW_STATEMENT_REPORT,
    STATEMENT_OF_CHANGES_IN_EQUITY_REPORT,
    TRIAL_BALANCE_REPORT,
    LEDGER_REPORT,
    AGED_BALANCE_RECEIVABLES_REPORT,
    AGED_BALANCE_PAYABLES_REPORT,
    CORPORATE_TAX_PROJECTION_REPORT,
    VAT_DECLARATION_REPORT,
    TCA_DECLARATION_REPORT,
    PAYROLL_SUMMARY_REPORT,
    // step7-backend — Reports Hub v2.5.0 : 2 derniers types pour atteindre 19/19 rapports
    LETTERING_REPORT,
    CNSS_RETURN_REPORT
}

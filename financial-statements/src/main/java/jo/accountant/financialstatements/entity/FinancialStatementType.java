package jo.accountant.financialstatements.entity;

/**
 * Type d'état financier (§13++ v7-2 IAS 1.106).
 *
 * <ul>
 * <li>{@link #BALANCE_SHEET} — bilan (Actif = Passif + Capitaux propres)</li>
 * <li>{@link #INCOME_STATEMENT} — compte de résultat (Produits − Charges = Résultat net)</li>
 * <li>{@link #CASH_FLOW_STATEMENT} — tableau de flux de trésorerie (IAS 7 / SYSCOHADA TAFIRE).
 *obligatoire en IFRS (IAS 7) et SYSCOHADA. La méthode
 * indirecte est utilisée : résultat net ± variations BFR ± amortissements ± cessions.</li>
 * <li>{@link #STATEMENT_OF_CHANGES_IN_EQUITY} — tableau de variation des capitaux propres
 * (IAS 1.106). V84 — v7-2 : obligatoire en IFRS_FULL, ajouté pour conformité PME4
 * (Caribbean Textiles, zone franche).</li>
 * </ul>
 *
 * <p>Les autres états (TAFIRE SYSCOHADA complet, liasse fiscale française) sont hors périmètre.
 
 *
 * @author jo@Dev


*/
public enum FinancialStatementType {
 BALANCE_SHEET,
 INCOME_STATEMENT,
 CASH_FLOW_STATEMENT, //ajout
 STATEMENT_OF_CHANGES_IN_EQUITY // V84 — v7-2 : IAS 1.106
}

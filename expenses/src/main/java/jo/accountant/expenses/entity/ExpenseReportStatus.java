package jo.accountant.expenses.entity;

/**
 * Statut d'une note de frais (module :expenses).
 *
 * <p>Cycle de vie : DRAFT → SUBMITTED → APPROVED → PAID (ou REJECTED au stade SUBMITTED).
 *
 * <p>L'approbation délègue à `JOURNAL_ENTRY_POST` (§2.2 du prompt — choix de cohérence
 * avec `:invoicing`/`:fixed-assets`/`:inventory`). Pas de `ApprovalActionType` dédié —
 * la transition APPROVED → génération d'écriture se fait en une seule étape au niveau du
 * service (la validation par seuil est gérée par `:accounting-engine` au postage).
 */
public enum ExpenseReportStatus {
 DRAFT,
 SUBMITTED,
 APPROVED,
 REJECTED,
 PAID
}

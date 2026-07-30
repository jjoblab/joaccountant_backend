package jo.accountant.payroll.entity;

/**
 * Statut d'une campagne de paie (restructuration 2026-07-24 — module :payroll).
 *
 * <p>Cycle de vie :
 * <ul>
 *   <li>{@link #DRAFT} — campagne créée pour une période (mois/année), pas encore calculée.</li>
 *   <li>{@link #IN_PROGRESS} — v8-7 : la campagne est en cours de calcul asynchrone
 *       (Spring Batch thirteenthMonthJob ou fallback {@code @Async} ThirteenthMonthAsyncRunner).
 *       Statut intermédiaire entre DRAFT et CALCULATED pour les gros volumes (&gt; 1000 employés)
 *       où le calcul dépasse le timeout HTTP 30s.</li>
 *   <li>{@link #CALCULATED} — {@code calculate()} a généré un {@code Payslip} par employé
 *       {@code ACTIVE} à cette date, avec calcul brut→net via les {@code WithholdingRule}
 *       de {@code :tax} applicables aux employés.</li>
 *   <li>{@link #APPROVED} — la campagne a été approuvée (délègue à {@code JOURNAL_ENTRY_POST},
 *       voir §2.4 du prompt). Le postage de l'écriture consolidée se fait à la transition
 *       APPROVED (génération) — pas à un stade séparé, contrairement à la convention
 *       nominale de :invoicing. Choix de cohérence avec §2.2 (expenses).</li>
 *   <li>{@link #PAID} — les virements effectifs ont été faits (marquage manuel — la
 *       génération du fichier de virement n'est pas dans le scope MVP).</li>
 *   <li>{@link #CLOSED} — clôturée, plus modifiable.</li>
 * </ul>
 */
public enum PayrollRunStatus {
    DRAFT,
    IN_PROGRESS,
    CALCULATED,
    APPROVED,
    PAID,
    CLOSED
}

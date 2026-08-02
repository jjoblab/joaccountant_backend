package jo.accountant.payroll.entity;

/**
 * V86 — v7-4 : Type de campagne de paie.
 *
 * <p>Distingue les campagnes mensuelles régulières des campagnes spéciales (13e mois).
 *
 * <ul>
 *   <li>{@link #REGULAR} — paie mensuelle normale (12 campagnes par an).</li>
 *   <li>{@link #THIRTEENTH_MONTH} — 13e mois (Code du Travail Haïti art. 153). Versé en
 *       décembre, calculé au prorata de l'ancienneté. Cotisations CNSS/OFATMA/AST non
 *       appliquées ; ITS appliqué (Code Fiscal art. 156).</li>
 * </ul>
 */
public enum PayrollRunType {
    REGULAR,
    THIRTEENTH_MONTH
}

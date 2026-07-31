package jo.accountant.reporting.dto;

import java.math.BigDecimal;

/**
 * Comparaison de période (Task v2.5.0-task18 — Analytics Dashboard).
 *
 * <p>Somme des montants TTC des factures de ventes (statuts ISSUED,
 * PARTIALLY_PAID, PAID) sur 4 périodes glissantes :
 * <ul>
 *   <li>{@code currentMonth}  — mois en cours (M) ;</li>
 *   <li>{@code previousMonth} — mois précédent (M-1) ;</li>
 *   <li>{@code currentYear}   — année en cours (Y) ;</li>
 *   <li>{@code previousYear}  — année précédente (Y-1).</li>
 * </ul>
 *
 * <p>Le mobile en déduit la variation % M vs M-1 et Y vs Y-1 pour afficher
 * deux mini bar charts comparatifs. Si une période est vide (aucune facture),
 * la valeur est 0.
 *
 * <p>Toutes les valeurs sont en devise fonctionnelle de l'entreprise.
 *
 * @param currentMonth  somme TTC des ventes du mois courant
 * @param previousMonth somme TTC des ventes du mois précédent
 * @param currentYear   somme TTC des ventes de l'année courante
 * @param previousYear  somme TTC des ventes de l'année précédente
 */
public record AnalyticsPeriodComparison(
    BigDecimal currentMonth,
    BigDecimal previousMonth,
    BigDecimal currentYear,
    BigDecimal previousYear
) {}

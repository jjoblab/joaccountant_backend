package jo.accountant.reporting.dto;

/**
 * Ratio financier (Analytics Dashboard).
 *
 * <p>Représente un indicateur de structure/performance calculé à partir du
 * bilan et du compte de résultat. Trois ratios sont exposés dans le MVP :
 * <ul>
 * <li><b>Liquidité générale</b> = actif courant / passif courant ;</li>
 * <li><b>Solvabilité</b> = total actif / total passif ;</li>
 * <li><b>Rentabilité nette</b> = résultat net / total produits × 100.</li>
 * </ul>
 *
 * <p>{@code interpretation} est un court texte localisé (FR) décrivant le
 * verdict (ex. "Bonne liquidité", "Solvabilité fragile") — calculé côté
 * backend à partir de seuils métier conventionnels.
 *
 * @param label libellé du ratio (ex. "Liquidité générale")
 * @param value valeur numérique arrondie à 2 décimales
 * @param formula formule de calcul (ex. "Actif courant ÷ Passif courant")
 * @param interpretation verdict localisé (ex. "Bonne liquidité (≥ 1,5)")
 
 *
 * @author jo@Dev


*/
public record AnalyticsRatio(
 String label,
 double value,
 String formula,
 String interpretation
) {}

package jo.accountant.fixedassets.entity;

/**
 * Méthode d'amortissement (§13 Phase 8).
 *
 * <ul>
 *   <li>{@link #STRAIGHT_LINE} — amortissement linéaire. Montant constant par période =
 *       (coût d'acquisition − valeur résiduelle) / durée de vie en mois.</li>
 *   <li>{@link #DECLINING_BALANCE} — amortissement dégressif. Taux dégressif appliqué au
 *       solde net comptable restant. Plus utilisé en début de vie de l'actif.</li>
 * </ul>
 */
public enum DepreciationMethod {
    STRAIGHT_LINE,
    DECLINING_BALANCE
}

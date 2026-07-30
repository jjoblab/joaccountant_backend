package jo.accountant.core.tax;

/**
 * Type de barème d'une règle de retenue à la source — Finding #14.
 *
 * <ul>
 *   <li>{@link #FLAT} — taux unique appliqué à toute la base (comportement historique avant
 *       V46, rétro-compatible). Le champ {@code rate} de {@code WithholdingRule} est utilisé.</li>
 *   <li>{@link #PROGRESSIVE} — barème progressif par tranches, stocké dans
 *       {@code bracketsJson} au format
 *       {@code [{"threshold":0,"rate":0},{"threshold":50000,"rate":10},{"threshold":100000,"rate":15}]}.
 *       La retenue est calculée par tranches successives (voir
 *       {@code PurchasingService.calculateSupplierWithholding}).</li>
 * </ul>
 *
 * <p>Le défaut est {@link #FLAT} pour conserver le comportement historique des règles
 * existantes (aucun impact pour les entreprises qui n'ont pas opté pour le barème progressif).
 *
 * <p>Cette énumération est définie dans {@code :core} (et non dans {@code :tax}) car elle est
 * référencée à la fois par {@code :tax} (sur l'entité {@code WithholdingRule}) et par
 * {@code :purchasing} (via {@link jo.accountant.core.port.WithholdingRulePort}) — sans
 * dépendance circulaire Gradle.
 */
public enum WithholdingBracketType {
    /** Taux unique appliqué à toute la base (comportement historique). */
    FLAT,
    /** Barème progressif par tranches (stored in {@code bracketsJson}). */
    PROGRESSIVE
}

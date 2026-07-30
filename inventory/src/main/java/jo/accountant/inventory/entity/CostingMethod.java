package jo.accountant.inventory.entity;

/**
 * Méthode de valorisation du stock (§13 Phase 9).
 *
 * <ul>
 *   <li>{@link #FIFO} — First In, First Out. Les sorties sont valorisées au coût des
 *       premières entrées non encore consommées. Utilise {@link StockValuationLayer}
 *       pour suivre les couches de stock restantes.</li>
 *   <li>{@link #WEIGHTED_AVERAGE} — Coût moyen pondéré. Le coût unitaire est recalculé
 *       à chaque entrée : (valeur stock actuel + valeur entrée) / (quantité stock + quantité entrée).</li>
 * </ul>
 *
 * <p><strong>LIFO n'est pas implémenté</strong> — IFRS l'interdit. Aucun flag "LIFO" n'est
 * exposé nulle part, même désactivé. C'est un choix délibéré du prompt maître §13 Phase 9.
 */
public enum CostingMethod {
    FIFO,
    WEIGHTED_AVERAGE
}

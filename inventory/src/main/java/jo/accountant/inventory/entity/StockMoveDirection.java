package jo.accountant.inventory.entity;

/**
 * Direction d'un mouvement de stock (§13 Phase 9).
 *
 * <ul>
 *   <li>{@link #IN} — entrée en stock (réception, retour, production)</li>
 *   <li>{@link #OUT} — sortie de stock (vente, consommation, perte)</li>
 *   <li>{@link #TRANSFER} — transfert entre entrepôts (sortie d'un entrepôt + entrée dans un autre)</li>
 * </ul>
 *
 * <p>Les sorties ({@link #OUT}) déclenchent le calcul du COGS et la génération d'une écriture
 * comptable. Les entrées ({@link #IN}) créent une couche de valorisation (FIFO) ou mettent
 * à jour le coût moyen (WEIGHTED_AVERAGE). Les transferts ({@link #TRANSFER}) sont traités
 * comme une sortie + une entrée sans impact COGS.
 */
public enum StockMoveDirection {
    IN,
    OUT,
    TRANSFER
}

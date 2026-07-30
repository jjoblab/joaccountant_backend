package jo.accountant.purchaseorders.entity;

/**
 * Statut d'une commande fournisseur (Finding #10).
 *
 * <p>Cycle de vie :
 * <ul>
 *   <li>{@link #DRAFT} — commande en cours de saisie, modifiable</li>
 *   <li>{@link #SUBMITTED} — commande soumise pour validation interne</li>
 *   <li>{@link #RECEIVED} — commande reçue (marchandises livrées), prête pour 3-way match</li>
 *   <li>{@link #CLOSED} — commande cloturée (facture reçue et rapprochée)</li>
 * </ul>
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    RECEIVED,
    CLOSED
}

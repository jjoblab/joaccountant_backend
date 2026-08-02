package jo.accountant.purchaseorders.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un 3-way match entre une facture fournisseur et une commande.
 *
 * <p>Le 3-way match vérifie la cohérence entre une commande (PurchaseOrder) et une facture
 * fournisseur (PurchaseInvoice) sur 3 dimensions :
 * <ol>
 * <li><b>Existence de commande</b> — au moins une commande existe pour le fournisseur.</li>
 * <li><b>Quantités</b> — pour chaque ligne facturée, la quantité facturée doit être ≤ à la
 * quantité commandée (la commande peut être livrée en plusieurs fois).</li>
 * <li><b>Prix</b> — pour chaque ligne facturée, le prix unitaire facturé doit être égal au
 * prix unitaire commandé.</li>
 * </ol>
 *
 * <p>Si toutes les vérifications passent, {@link #matches} = {@code true} et
 * {@link #discrepancies} est vide. Sinon, {@code matches} = {@code false} et la liste
 * {@code discrepancies} détaille les écarts constatés.
 *
 * @param invoiceId facture fournisseur testée
 * @param purchaseOrderId commande rapprochée (peut être null si aucune commande n'existe)
 * @param matches {@code true} si le 3-way match passe (aucune divergence)
 * @param discrepancies liste des écarts constatés (vide si {@code matches})
 
 *
 * @author jo@Dev


*/
public record ThreeWayMatchResult(
 UUID invoiceId,
 UUID purchaseOrderId,
 boolean matches,
 List<Discrepancy> discrepancies
) {

 /**
 * Un écart constaté lors du 3-way match.
 *
 * @param type type d'écart (NO_PURCHASE_ORDER, QUANTITY_EXCEEDED, PRICE_MISMATCH,
 * NO_MATCHING_PO_LINE)
 * @param detail description lisible de l'écart
 * @param invoiceLineId ligne de facture concernée (peut être null si l'écart est global)
 * @param poLineId ligne de commande concernée (peut être null si non trouvée)
 * @param expected valeur attendue (quantité commandée ou prix commandé)
 * @param actual valeur constatée (quantité facturée ou prix facturé)
 */
 public record Discrepancy(
 String type,
 String detail,
 UUID invoiceLineId,
 UUID poLineId,
 BigDecimal expected,
 BigDecimal actual
 ) {}
}

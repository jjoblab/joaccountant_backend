package jo.accountant.purchasing.entity;

/**
 * Statut d'une facture d'achat (restructuration 2026-07-24 — module :purchasing).
 *
 * <p>Cycle de vie :
 * <ul>
 *   <li>{@link #DRAFT} — création, lignes éditables, pas de numéro interne, pas d'écriture.</li>
 *   <li>{@link #RECEIVED} — {@code receive()} a attribué le numéro interne via
 *       document-numbering et généré l'écriture comptable (Débit Achats + TVA déductible /
 *       Crédit Fournisseur).</li>
 *   <li>{@link #PARTIALLY_PAID} — au moins un paiement partiel enregistré.</li>
 *   <li>{@link #PAID} — {@code paidAmount == totalAmount}.</li>
 *   <li>{@link #VOID} — facture annulée (disponible tant que non payée).</li>
 * </ul>
 */
public enum PurchaseInvoiceStatus {
    DRAFT,
    RECEIVED,
    PARTIALLY_PAID,
    PAID,
    VOID
}

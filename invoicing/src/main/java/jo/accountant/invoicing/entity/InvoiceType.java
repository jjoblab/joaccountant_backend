package jo.accountant.invoicing.entity;

/**
 * Type de facture (§13 Phase 12).
 *
 * <ul>
 *   <li>{@link #STANDARD} — facture classique</li>
 *   <li>{@link #CREDIT_NOTE} — avoir. A sa propre séquence dans document-numbering
 *       (DocumentType.CREDIT_NOTE). Corrige ou annule une facture STANDARD.</li>
 * </ul>
 */
public enum InvoiceType {
    STANDARD,
    CREDIT_NOTE
}

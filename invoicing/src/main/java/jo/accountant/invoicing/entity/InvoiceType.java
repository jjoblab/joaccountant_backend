package jo.accountant.invoicing.entity;

/**
 * InvoiceType — type de facture unifié (v9.0).
 *
 * <p>Remplace les enums séparés {@code InvoiceType} (SALES: STANDARD, CREDIT_NOTE)
 * et {@code PurchaseInvoiceType} (PURCHASE: STANDARD, DEBIT_NOTE) par une seule
 * enum qui couvre les deux directions.
 *
 * <ul>
 *   <li>{@link #STANDARD} — facture classique (vente ou achat) ;</li>
 *   <li>{@link #CREDIT_NOTE} — avoir client (vente uniquement, lié à une facture originale via {@code creditNoteForInvoiceId}) ;</li>
 *   <li>{@link #DEBIT_NOTE} — note de débit fournisseur (achat uniquement, avoir fournisseur).</li>
 * </ul>
 *
 * <p>Contrainte : {@link #CREDIT_NOTE} n'est valide qu'avec {@link InvoiceDirection#SALES},
 * et {@link #DEBIT_NOTE} n'est valide qu'avec {@link InvoiceDirection#PURCHASE}.
 *
 * @since v9.0
 */
public enum InvoiceType {
    STANDARD,
    CREDIT_NOTE,
    DEBIT_NOTE
}

package jo.accountant.purchasing.entity;

/**
 * Type de facture d'achat (module :purchasing).
 *
 * <p>{@link #STANDARD} — facture fournisseur classique.
 * {@link #DEBIT_NOTE} — note de débit (avoir fournisseur — ajustement positif du côté
 * fournisseur, négatif du côté entreprise).
 
 *
 * @author jo@Dev


*/
public enum PurchaseInvoiceType {
 STANDARD,
 DEBIT_NOTE
}

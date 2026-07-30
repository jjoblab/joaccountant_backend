package jo.accountant.purchasing.entity;

/**
 * Type de facture d'achat (restructuration 2026-07-24 — module :purchasing).
 *
 * <p>{@link #STANDARD} — facture fournisseur classique.
 * {@link #DEBIT_NOTE} — note de débit (avoir fournisseur — ajustement positif du côté
 * fournisseur, négatif du côté entreprise).
 */
public enum PurchaseInvoiceType {
    STANDARD,
    DEBIT_NOTE
}

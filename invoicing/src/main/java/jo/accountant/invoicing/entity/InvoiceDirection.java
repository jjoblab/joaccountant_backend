package jo.accountant.invoicing.entity;

/**
 * InvoiceDirection — direction d'une facture dans le cycle comptable.
 *
 * <p>v9.0 — Unification architecturale : une seule entité {@link Invoice}
 * remplace {@code SalesInvoice} et {@code PurchaseInvoice}. La direction
 * détermine le sens comptable (D/C) et les fonctionnalités disponibles.
 *
 * <ul>
 *   <li>{@link #SALES} — facture de vente (client). Débit Client / Crédit Ventes + TVA.
 *       Génère un numéro via {@code DocumentType.SALES_INVOICE} (scopeKey="VT").
 *       Supporte : multi-taxe par ligne, autoliquidation (reverse-charge),
 *       TVA sur encaissement, retenue à la source (RS), Factur-X, signature électronique.</li>
 *   <li>{@link #PURCHASE} — facture d'achat (fournisseur). Débit Achats + TVA déductible / Crédit Fournisseur.
 *       Génère un numéro via {@code DocumentType.PURCHASE_INVOICE} (scopeKey="AC").
 *       Supporte : retenue à la source fournisseur (calculée dynamiquement),
 *       compte de charge par ligne, DEBIT_NOTE (avoir fournisseur).</li>
 * </ul>
 *
 * @since v9.0
 */
public enum InvoiceDirection {
    SALES,
    PURCHASE
}

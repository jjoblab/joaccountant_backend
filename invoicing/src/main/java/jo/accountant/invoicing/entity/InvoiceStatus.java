package jo.accountant.invoicing.entity;

/**
 * Statut d'une facture (§13.
 *
 * <ul>
 * <li>{@link #DRAFT} — brouillon, modifiable. Pas encore de invoiceNumber.</li>
 * <li>{@link #ISSUED} — émise. invoiceNumber attribué via document-numbering. Écriture
 * comptable générée (Débit Client / Crédit Ventes + TVA). <strong>Immuable</strong> —
 * correction par avoir ({@link InvoiceType#CREDIT_NOTE}).</li>
 * <li>{@link #PARTIALLY_PAID} — partiellement réglée (au moins un règlement enregistré
 * mais solde non nul).</li>
 * <li>{@link #PAID} — entièrement réglée. Solde = 0.</li>
 * <li>{@link #VOID} — annulée. Conserve son numéro (règle numérotation sans trou, §6).</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PARTIALLY_PAID,
    PAID,
    VOID
}

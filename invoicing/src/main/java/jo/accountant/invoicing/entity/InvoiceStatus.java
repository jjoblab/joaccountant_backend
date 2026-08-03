package jo.accountant.invoicing.entity;

/**
 * InvoiceStatus — statut unifié d'une facture (v9.0).
 *
 * <p>Remplace les enums séparées {@code InvoiceStatus} (SALES: DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID)
 * et {@code PurchaseInvoiceStatus} (PURCHASE: DRAFT, RECEIVED, PARTIALLY_PAID, PAID, VOID).
 *
 * <p>Le statut {@code RECEIVED} (achat) est fusionné avec {@link #ISSUED} — les deux
 * représentent le moment où la facture est comptabilisée (écriture postée). Le terme
 * « ISSUED » (émise) est plus universel et cohérent entre les deux directions.
 *
 * <ul>
 *   <li>{@link #DRAFT} — brouillon (modifiable, supprimable, pas d'impact comptable) ;</li>
 *   <li>{@link #ISSUED} — émise/reçue (écriture comptable postée, immuable sauf par avoir/void) ;</li>
 *   <li>{@link #PARTIALLY_PAID} — partiellement payée (paiement partiel enregistré) ;</li>
 *   <li>{@link #PAID} — entièrement payée ;</li>
 *   <li>{@link #VOID} — annulée (contre-passation comptable générée si était ISSUED).</li>
 * </ul>
 *
 * <p>Transitions autorisées :
 * <pre>
 *   DRAFT → ISSUED (issue/receive — génère écriture)
 *   DRAFT → VOID (annulation sans impact)
 *   DRAFT → (deleted) (suppression définitive)
 *   ISSUED → PARTIALLY_PAID (paiement partiel)
 *   ISSUED → PAID (paiement total)
 *   ISSUED → VOID (annulation — génère contre-passation)
 *   PARTIALLY_PAID → PAID (paiement du solde)
 *   PARTIALLY_PAID → VOID (refusé — utiliser un avoir)
 *   PAID → VOID (refusé — utiliser un avoir)
 *   VOID → (terminal)
 * </pre>
 *
 * @since v9.0
 */
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PARTIALLY_PAID,
    PAID,
    VOID
}

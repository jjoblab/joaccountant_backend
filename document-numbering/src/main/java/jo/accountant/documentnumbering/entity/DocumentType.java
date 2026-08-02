package jo.accountant.documentnumbering.entity;

/**
 * Types de documents numérotés par le module {@code document-numbering} (§6).
 *
 * <p>Cet enum est volontairement extensible : chaque nouveau module métier qui émet un document
 * numéroté visible par un tiers externe (client, bailleur, administration fiscale) ajoute une
 * valeur ici. Les valeurs présentes correspondent aux consommateurs identifiés dans le prompt
 * maître v2.1 :
 *
 * <ul>
 * <li>{@link #JOURNAL_ENTRY} — consommé par {@code JournalEntry.reference} (Phase 5)</li>
 * <li>{@link #SALES_INVOICE} — consommé par {@code SalesInvoice.invoiceNumber} (Phase 12)</li>
 * <li>{@link #CREDIT_NOTE} — consommé par les avoirs (Phase 12, séquence dédiée)</li>
 * <li>{@link #DONATION_RECEIPT} — consommé par {@code DonationReceipt.receiptNumber} (Phase 14)</li>
 * <li>{@link #PURCHASE_INVOICE} — consommé par {@code PurchaseInvoice.invoiceNumber}
 * (module :purchasing)</li>
 * <li>{@link #PAYSLIP} — consommé par {@code Payslip.payslipNumber}
 * (module :payroll)</li>
 * </ul>
 *
 * <p>Rappel (§6) : ce module ne doit JAMAIS être confondu avec {@code AccountNumberingTemplate}
 * (Phase 3, {@code chart-of-accounts}) qui génère des codes de comptes hiérarchiques (ex.
 * {@code 411000}). Les deux mécanismes ne partagent ni entité ni service.
 */
public enum DocumentType {
 JOURNAL_ENTRY,
 SALES_INVOICE,
 CREDIT_NOTE,
 DONATION_RECEIPT,
 // Restructuration 2026-07-24 (suite) — 4 nouveaux modules bonus
 PURCHASE_INVOICE,
 PAYSLIP,
 // v2.7.0 (2026-08-02) — Séquences pour les immobilisations et articles de stock
 ASSET,
 INVENTORY_ITEM
}

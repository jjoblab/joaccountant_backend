package jo.accountant.purchasing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Facture fournisseur (restructuration 2026-07-24 — module :purchasing).
 *
 * <p>Une facture d'achat peut être de type {@link PurchaseInvoiceType#STANDARD} (facture
 * classique) ou {@link PurchaseInvoiceType#DEBIT_NOTE} (note de débit — avoir fournisseur).
 *
 * <p>Cycle de vie : DRAFT → RECEIVED → PARTIALLY_PAID → PAID (ou VOID à tout moment tant
 * que non payée). Le {@link #invoiceNumber} est attribué via document-numbering au passage
 * DRAFT → RECEIVED (DocumentType.PURCHASE_INVOICE, scopeKey = code journal "AC").
 *
 * <p>Symétrique de {@code SalesInvoice} du module :invoicing — débit/crédit inversés.
 */
@Entity
@Table(name = "purchase_invoice")
public class PurchaseInvoice extends TenantAwareEntity {

    /** Tiers de type SUPPLIER — référencé par son ID (FK logique vers third_party.id). */
    @Column(name = "third_party_id", nullable = false)
    private UUID thirdPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 15)
    private PurchaseInvoiceType type = PurchaseInvoiceType.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PurchaseInvoiceStatus status = PurchaseInvoiceStatus.DRAFT;

    /** Null en DRAFT, assigné via document-numbering au passage DRAFT → RECEIVED. */
    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    /** Numéro de facture fourni par le fournisseur (référence externe, texte libre). */
    @Column(name = "supplier_reference", length = 100)
    private String supplierReference;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** ID de l'écriture comptable générée au passage DRAFT → RECEIVED. */
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    public UUID getThirdPartyId() { return thirdPartyId; }
    public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

    public PurchaseInvoiceType getType() { return type; }
    public void setType(PurchaseInvoiceType type) { this.type = type; }

    public PurchaseInvoiceStatus getStatus() { return status; }
    public void setStatus(PurchaseInvoiceStatus status) { this.status = status; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getSupplierReference() { return supplierReference; }
    public void setSupplierReference(String supplierReference) { this.supplierReference = supplierReference; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

    /** Solde restant à payer = totalAmount - paidAmount. */
    public BigDecimal getBalanceDue() {
        return totalAmount.subtract(paidAmount);
    }
}

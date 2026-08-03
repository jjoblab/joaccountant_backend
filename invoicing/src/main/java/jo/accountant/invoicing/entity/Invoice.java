package jo.accountant.invoicing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Invoice — entité unifiée pour les factures de vente ET d'achat (v9.0).
 *
 * <p><b>Unification architecturale</b> : cette entité remplace progressivement
 * les deux entités parallèles {@code SalesInvoice} (table {@code sales_invoice})
 * et {@code PurchaseInvoice} (table {@code purchase_invoice}) par une seule
 * entité avec un champ {@link #direction} qui détermine le sens comptable.
 *
 * <p>La table {@code invoice} est créée par la migration Flyway V29 qui
 * migre les données depuis les deux anciennes tables. Les anciennes tables
 * sont conservées comme VIEWS (compatibilité descendante) le temps que
 * tout le code soit migré vers l'entité unifiée.
 *
 * <h3>Champs par direction</h3>
 * <table border="1">
 *   <tr><th>Champ</th><th>SALES</th><th>PURCHASE</th><th>Notes</th></tr>
 *   <tr><td>direction</td><td>SALES</td><td>PURCHASE</td><td>Obligatoire</td></tr>
 *   <tr><td>type</td><td>STANDARD / CREDIT_NOTE</td><td>STANDARD / DEBIT_NOTE</td><td>Obligatoire</td></tr>
 *   <tr><td>thirdPartyId</td><td>Client</td><td>Fournisseur</td><td>Obligatoire (les 2)</td></tr>
 *   <tr><td>supplierReference</td><td>—</td><td>Réf fournisseur</td><td>PURCHASE only</td></tr>
 *   <tr><td>creditNoteForInvoiceId</td><td>FK facture originale</td><td>—</td><td>SALES CREDIT_NOTE only</td></tr>
 *   <tr><td>reverseCharge</td><td>Autoliquidation</td><td>—</td><td>SALES only</td></tr>
 *   <tr><td>vatSettlementEntryId</td><td>TVA sur encaissement</td><td>—</td><td>SALES only</td></tr>
 *   <tr><td>vatDeferredAmount</td><td>TVA différée (4438)</td><td>—</td><td>SALES only</td></tr>
 *   <tr><td>withholdingRate/Amount/NetReceivable/RuleId</td><td>RS client</td><td>— (calculé dynamiquement)</td><td>SALES: persisté ; PURCHASE: non persisté</td></tr>
 * </table>
 *
 * <h3>Cycle de vie</h3>
 * <pre>
 *   DRAFT → ISSUED → PARTIALLY_PAID → PAID
 *                ↘ VOID (contre-passation si ISSUED)
 *   DRAFT → VOID (sans impact)
 *   DRAFT → (deleted)
 * </pre>
 *
 * @since v9.0
 */
@Entity
@Table(name = "invoice")
public class Invoice extends TenantAwareEntity {

    // ════════════════════════════════════════════════════════════════════════
    //  Champs communs (SALES + PURCHASE)
    // ════════════════════════════════════════════════════════════════════════

    /** Direction : SALES (vente client) ou PURCHASE (achat fournisseur). */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private InvoiceDirection direction = InvoiceDirection.SALES;

    /** Type : STANDARD, CREDIT_NOTE (SALES), DEBIT_NOTE (PURCHASE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 15)
    private InvoiceType type = InvoiceType.STANDARD;

    /** Statut : DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    /** UUID du tiers (client pour SALES, fournisseur pour PURCHASE). */
    @Column(name = "third_party_id", nullable = false)
    private UUID thirdPartyId;

    /** Numéro de facture (null en DRAFT, assigné au passage DRAFT → ISSUED). */
    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    /** Date d'émission (SALES) / de réception (PURCHASE) — assignée au passage ISSUED. */
    @Column(name = "issue_date")
    private LocalDate issueDate;

    /** Date d'échéance de paiement. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Code ISO 4217 (HTG, USD, EUR, ...). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Total HT — somme des lignes (quantity × unitPrice × (1 - discountPercent/100)). */
    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    /** Total TVA — somme des taxes par ligne. */
    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** Total TTC = subtotal + taxAmount. */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Montant déjà réglé (cumulé via recordPayment). */
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** ID de l'écriture comptable générée au passage DRAFT → ISSUED. */
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    // ════════════════════════════════════════════════════════════════════════
    //  Champs spécifiques PURCHASE
    // ════════════════════════════════════════════════════════════════════════

    /** PURCHASE only — référence externe du fournisseur (n° facture fournisseur). */
    @Column(name = "supplier_reference", length = 100)
    private String supplierReference;

    // ════════════════════════════════════════════════════════════════════════
    //  Champs spécifiques SALES
    // ════════════════════════════════════════════════════════════════════════

    /** SALES CREDIT_NOTE only — FK vers la facture originale corrigée. */
    @Column(name = "credit_note_for_invoice_id")
    private UUID creditNoteForInvoiceId;

    /** SALES only — ID de l'écriture de bascule TVA (4438 → 443 au règlement). */
    @Column(name = "vat_settlement_entry_id")
    private UUID vatSettlementEntryId;

    /** SALES only — TVA différée restante (mode ENCAISSEMENT). */
    @Column(name = "vat_deferred_amount", precision = 19, scale = 4)
    private BigDecimal vatDeferredAmount = BigDecimal.ZERO;

    /** SALES only — autoliquidation intra-UE B2B (reverse-charge). */
    @Column(name = "is_reverse_charge", nullable = false)
    private boolean reverseCharge = false;

    /** SALES only — taux de retenue à la source (RS Haïti art. 156-1). */
    @Column(name = "withholding_rate", precision = 5, scale = 2)
    private BigDecimal withholdingRate;

    /** SALES only — montant RS retenu par le client. */
    @Column(name = "withholding_amount", precision = 19, scale = 4)
    private BigDecimal withholdingAmount;

    /** SALES only — montant net à recevoir = totalAmount − withholdingAmount. */
    @Column(name = "net_receivable", precision = 19, scale = 4)
    private BigDecimal netReceivable;

    /** SALES only — FK vers la WithholdingRule appliquée. */
    @Column(name = "withholding_rule_id")
    private UUID withholdingRuleId;

    // ════════════════════════════════════════════════════════════════════════
    //  Collection transiente (lignes)
    // ════════════════════════════════════════════════════════════════════════

    /** Lignes de la facture (non persistées ici — gérées par InvoiceLine). */
    @Transient
    private List<InvoiceLine> lines = new ArrayList<>();

    // ════════════════════════════════════════════════════════════════════════
    //  Getters / Setters
    // ════════════════════════════════════════════════════════════════════════

    public InvoiceDirection getDirection() { return direction; }
    public void setDirection(InvoiceDirection direction) { this.direction = direction; }

    public InvoiceType getType() { return type; }
    public void setType(InvoiceType type) { this.type = type; }

    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }

    public UUID getThirdPartyId() { return thirdPartyId; }
    public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

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

    public String getSupplierReference() { return supplierReference; }
    public void setSupplierReference(String supplierReference) { this.supplierReference = supplierReference; }

    public UUID getCreditNoteForInvoiceId() { return creditNoteForInvoiceId; }
    public void setCreditNoteForInvoiceId(UUID creditNoteForInvoiceId) { this.creditNoteForInvoiceId = creditNoteForInvoiceId; }

    public UUID getVatSettlementEntryId() { return vatSettlementEntryId; }
    public void setVatSettlementEntryId(UUID vatSettlementEntryId) { this.vatSettlementEntryId = vatSettlementEntryId; }

    public BigDecimal getVatDeferredAmount() { return vatDeferredAmount; }
    public void setVatDeferredAmount(BigDecimal vatDeferredAmount) {
        this.vatDeferredAmount = vatDeferredAmount != null ? vatDeferredAmount : BigDecimal.ZERO;
    }

    public boolean isReverseCharge() { return reverseCharge; }
    public void setReverseCharge(boolean reverseCharge) { this.reverseCharge = reverseCharge; }

    public BigDecimal getWithholdingRate() { return withholdingRate; }
    public void setWithholdingRate(BigDecimal withholdingRate) { this.withholdingRate = withholdingRate; }

    public BigDecimal getWithholdingAmount() { return withholdingAmount; }
    public void setWithholdingAmount(BigDecimal withholdingAmount) { this.withholdingAmount = withholdingAmount; }

    public BigDecimal getNetReceivable() { return netReceivable; }
    public void setNetReceivable(BigDecimal netReceivable) { this.netReceivable = netReceivable; }

    public UUID getWithholdingRuleId() { return withholdingRuleId; }
    public void setWithholdingRuleId(UUID withholdingRuleId) { this.withholdingRuleId = withholdingRuleId; }

    public List<InvoiceLine> getLines() { return lines; }
    public void setLines(List<InvoiceLine> lines) {
        this.lines = lines != null ? lines : new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Méthodes métier (rich aggregate)
    // ════════════════════════════════════════════════════════════════════════

    /** Solde restant à payer = totalAmount - paidAmount. */
    public BigDecimal getBalanceDue() {
        return totalAmount.subtract(paidAmount);
    }

    /** @return true si la facture est de vente (direction SALES). */
    public boolean isSales() {
        return direction == InvoiceDirection.SALES;
    }

    /** @return true si la facture est d'achat (direction PURCHASE). */
    public boolean isPurchase() {
        return direction == InvoiceDirection.PURCHASE;
    }

    /** @return true si la facture est un avoir (CREDIT_NOTE ou DEBIT_NOTE). */
    public boolean isCorrective() {
        return type == InvoiceType.CREDIT_NOTE || type == InvoiceType.DEBIT_NOTE;
    }

    /**
     * Recalcule les totaux depuis les lignes. Anti-tampering : si un service
     * a modifié les lignes sans recalculer les totaux, cette méthode restaure
     * les valeurs correctes.
     */
    public void recalculateTotals() {
        if (this.lines == null || this.lines.isEmpty()) {
            this.subtotal = BigDecimal.ZERO;
            this.taxAmount = BigDecimal.ZERO;
            this.totalAmount = BigDecimal.ZERO;
            return;
        }
        this.subtotal = this.lines.stream()
                .map(InvoiceLine::getLineTotalHt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.taxAmount = this.lines.stream()
                .map(InvoiceLine::getLineTotalTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.totalAmount = this.subtotal.add(this.taxAmount);
    }

    /**
     * Transition DRAFT → ISSUED. Valide que la facture a au moins une ligne
     * et recalcule les totaux. Ne positionne PAS invoiceNumber (responsabilité
     * du service appelant via DocumentNumberingService).
     */
    public void issue() {
        if (this.status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException(
                "Cannot issue invoice in status: " + this.status
                + " (invoiceId=" + this.getId() + ")");
        }
        if (this.lines == null || this.lines.isEmpty()) {
            throw new IllegalStateException(
                "Cannot issue invoice without lines (invoiceId=" + this.getId() + ")");
        }
        recalculateTotals();
        this.status = InvoiceStatus.ISSUED;
        this.issueDate = LocalDate.now();
    }

    /** Transition ISSUED/PARTIALLY_PAID → PAID. */
    public void markPaid() {
        if (this.status != InvoiceStatus.ISSUED && this.status != InvoiceStatus.PARTIALLY_PAID) {
            throw new IllegalStateException(
                "Cannot mark as paid invoice in status: " + this.status
                + " (invoiceId=" + this.getId() + ")");
        }
        this.status = InvoiceStatus.PAID;
    }

    /** Transition ISSUED → PARTIALLY_PAID. */
    public void markPartiallyPaid() {
        if (this.status != InvoiceStatus.ISSUED) {
            throw new IllegalStateException(
                "Cannot mark as partially paid invoice in status: " + this.status
                + " (invoiceId=" + this.getId() + ")");
        }
        this.status = InvoiceStatus.PARTIALLY_PAID;
    }
}

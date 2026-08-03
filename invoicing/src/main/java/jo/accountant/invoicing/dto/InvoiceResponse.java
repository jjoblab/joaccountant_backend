package jo.accountant.invoicing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.InvoiceDirection;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.InvoiceType;

/**
 * InvoiceResponse — DTO unifié pour les factures de vente ET d'achat (v9.0).
 *
 * <p>Remplace progressivement {@code InvoiceResponse} (sales) et
 * {@code PurchaseInvoiceResponse} (purchase) par une seule réponse qui
 * couvre les deux directions.
 *
 * @since v9.0
 */
public record InvoiceResponse(
    UUID id,
    UUID companyId,
    InvoiceDirection direction,
    InvoiceType type,
    InvoiceStatus status,
    UUID thirdPartyId,
    String thirdPartyName,
    String invoiceNumber,
    String supplierReference,
    LocalDate issueDate,
    LocalDate dueDate,
    String currency,
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal balanceDue,
    UUID creditNoteForInvoiceId,
    UUID journalEntryId,
    UUID vatSettlementEntryId,
    BigDecimal vatDeferredAmount,
    boolean reverseCharge,
    BigDecimal withholdingRate,
    BigDecimal withholdingAmount,
    BigDecimal netReceivable,
    String withholdingRuleCode,
    List<LineResponse> lines,
    Instant createdAt,
    Instant updatedAt
) {
    public record LineResponse(
        UUID id,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discountPercent,
        BigDecimal taxRate,
        UUID itemId,
        UUID timesheetEntryId,
        UUID expenseAccountId,
        BigDecimal lineTotalHt,
        BigDecimal lineTotalTax,
        List<TaxApplicationResponse> taxes
    ) {
        /** Constructeur backward-compat sans expenseAccountId (SALES lines). */
        public LineResponse(
            UUID id, String description, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal discountPercent, BigDecimal taxRate,
            UUID itemId, UUID timesheetEntryId,
            BigDecimal lineTotalHt, BigDecimal lineTotalTax,
            List<TaxApplicationResponse> taxes
        ) {
            this(id, description, quantity, unitPrice, discountPercent, taxRate,
                 itemId, timesheetEntryId, null, lineTotalHt, lineTotalTax, taxes);
        }
    }

    public record TaxApplicationResponse(
        String taxType,
        String taxCode,
        String taxLabel,
        BigDecimal rate,
        BigDecimal taxableBase,
        BigDecimal taxAmount
    ) {}

    /**
     * Constructeur backward-compat pour les appelants qui n'ont pas encore
     * été migrés vers les champs unifiés (SALES only, sans supplierReference).
     */
    public InvoiceResponse(
        UUID id, UUID companyId, UUID thirdPartyId, String thirdPartyName,
        InvoiceType type, InvoiceStatus status, String invoiceNumber,
        LocalDate issueDate, LocalDate dueDate, String currency,
        BigDecimal subtotal, BigDecimal taxAmount, BigDecimal totalAmount,
        BigDecimal paidAmount, BigDecimal balanceDue,
        UUID creditNoteForInvoiceId, UUID journalEntryId,
        List<LineResponse> lines,
        Instant createdAt, Instant updatedAt,
        boolean reverseCharge,
        BigDecimal withholdingRate, BigDecimal withholdingAmount,
        BigDecimal netReceivable, String withholdingRuleCode
    ) {
        this(id, companyId, InvoiceDirection.SALES, type, status, thirdPartyId,
             thirdPartyName, invoiceNumber, null, issueDate, dueDate, currency,
             subtotal, taxAmount, totalAmount, paidAmount, balanceDue,
             creditNoteForInvoiceId, journalEntryId, null, BigDecimal.ZERO,
             reverseCharge, withholdingRate, withholdingAmount, netReceivable,
             withholdingRuleCode, lines, createdAt, updatedAt);
    }
}

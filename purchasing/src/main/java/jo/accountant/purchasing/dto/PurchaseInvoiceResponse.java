package jo.accountant.purchasing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.purchasing.entity.PurchaseInvoiceStatus;
import jo.accountant.purchasing.entity.PurchaseInvoiceType;

public record PurchaseInvoiceResponse(
    UUID id,
    UUID companyId,
    UUID thirdPartyId,
    String thirdPartyName,
    PurchaseInvoiceType type,
    PurchaseInvoiceStatus status,
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
    UUID journalEntryId,
    List<LineResponse> lines,
    Instant createdAt,
    Instant updatedAt
) {
    public record LineResponse(
        UUID id,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        UUID expenseAccountId,
        BigDecimal lineTotalHt,
        BigDecimal lineTotalTax
    ) {}
}

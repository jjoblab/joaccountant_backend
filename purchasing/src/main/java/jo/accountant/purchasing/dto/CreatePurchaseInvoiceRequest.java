package jo.accountant.purchasing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.purchasing.entity.PurchaseInvoiceType;

/**
 * Corps de requête pour {@code POST .../purchase-invoices}.
 *
 * <p><b>Audit v4.7 §6.3 (session 8) — Validation DTOs</b> : ajout des annotations
 * {@code @DecimalMin} sur les montants pour rejeter les valeurs négatives.
 */
public record CreatePurchaseInvoiceRequest(
    @NotNull UUID thirdPartyId,
    PurchaseInvoiceType type,
    String supplierReference,
    LocalDate issueDate,
    LocalDate dueDate,
    String currency,
    @NotEmpty List<LineDto> lines
) {
    public record LineDto(
        @NotNull String description,
        @NotNull @DecimalMin(value = "0", message = "Quantity must be >= 0") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0", message = "Unit price must be >= 0") BigDecimal unitPrice,
        @DecimalMin(value = "0", message = "Tax rate must be >= 0") @DecimalMax(value = "100", message = "Tax rate must be <= 100") BigDecimal taxRate,
        UUID expenseAccountId
    ) {
        public LineDto {
            if (taxRate == null) taxRate = BigDecimal.ZERO;
        }
    }
}

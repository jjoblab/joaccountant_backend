package jo.accountant.purchaseorders.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;

/**
 * Réponse d'une commande fournisseur (Finding #10).
 */
public record PurchaseOrderResponse(
    UUID id,
    UUID companyId,
    UUID supplierId,
    String supplierName,
    String orderNumber,
    LocalDate orderDate,
    PurchaseOrderStatus status,
    String currency,
    BigDecimal totalAmount,
    List<LineResponse> lines,
    Instant createdAt,
    Instant updatedAt
) {
    public record LineResponse(
        UUID id,
        UUID itemId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal receivedQuantity,
        BigDecimal lineTotal
    ) {}
}

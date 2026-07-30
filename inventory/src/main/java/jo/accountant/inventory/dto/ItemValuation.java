package jo.accountant.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Valorisation de stock d'un article — {@code GET .../items/{id}/valuation}.
 */
public record ItemValuation(
    UUID itemId,
    String sku,
    String label,
    BigDecimal totalQuantity,
    BigDecimal totalValue,
    BigDecimal averageUnitCost,
    List<LayerDetail> layers
) {
    public record LayerDetail(
        UUID layerId,
        BigDecimal quantityRemaining,
        BigDecimal unitCost,
        java.time.LocalDate receiptDate
    ) {}
}

package jo.accountant.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.inventory.entity.CostingMethod;

public record CreateItemRequest(
    @NotBlank String sku,
    @NotBlank String label,
    @NotBlank String unitOfMeasure,
    CostingMethod costingMethod,
    BigDecimal reorderThreshold,
    @NotNull UUID inventoryAccountId,
    @NotNull UUID cogsAccountId
) {
    public CreateItemRequest {
        if (costingMethod == null) costingMethod = CostingMethod.FIFO;
    }
}

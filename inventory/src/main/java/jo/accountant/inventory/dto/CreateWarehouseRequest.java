package jo.accountant.inventory.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWarehouseRequest(@NotBlank String label) {}

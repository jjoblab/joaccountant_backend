package jo.accountant.inventory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * CreateWarehouseRequest.
 *
 * @author jo@Dev


 */

public record CreateWarehouseRequest(@NotBlank String label) {}

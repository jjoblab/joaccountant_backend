package jo.accountant.purchaseorders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;

/**
 * Corps de requête pour créer une commande fournisseur.
 *
 * @param supplierId identifiant du tiers SUPPLIER
 * @param orderNumber numéro de commande (unique par entreprise)
 * @param orderDate date de la commande
 * @param currency code ISO 4217 (défaut HTG)
 * @param status statut initial (défaut DRAFT)
 * @param lines lignes de la commande (au moins une)
 
 *
 * @author jo@Dev


*/
public record CreatePurchaseOrderRequest(
 @NotNull UUID supplierId,
 @NotBlank String orderNumber,
 @NotNull LocalDate orderDate,
 String currency,
 PurchaseOrderStatus status,
 @NotNull List<LineDto> lines
) {
 public CreatePurchaseOrderRequest {
 if (currency == null || currency.isBlank()) currency = "HTG";
 if (status == null) status = PurchaseOrderStatus.DRAFT;
 if (lines == null) lines = List.of();
 }

 /** Ligne de commande — description, quantité, prix unitaire, article optionnel. */
 public record LineDto(
 UUID itemId,
 @NotBlank String description,
 @NotNull BigDecimal quantity,
 @NotNull BigDecimal unitPrice
 ) {}
}

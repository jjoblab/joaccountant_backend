package jo.accountant.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.inventory.entity.CostingMethod;

/**
 * Réponse d'un article de stock (audit E-8, correction #1).
 *
 * <p>Auparavant, le backend n'exposait aucun endpoint {@code GET /items} — seul
 * {@code POST /items} était implémenté et renvoyait l'entité JPA {@link jo.accountant.inventory.entity.Item}
 * directement. L'audit E-7 (P0 #1) a identifié que le mobile {@code InventoryRepository.loadItems()}
 * appelait un endpoint inexistant et recevait systématiquement un 404, laissant
 * l'écran Inventaire vide.
 *
 * <p>Ce DTO est désormais renvoyé par {@code GET /api/v1/companies/{companyId}/inventory/items}
 * (tri par SKU ascendant) et par {@code POST /items} (au lieu de l'entité brute).
 *
 * <p>Champs miroir de {@link jo.accountant.inventory.entity.Item} :
 * <ul>
 *   <li>{@code id} — UUID ;</li>
 *   <li>{@code companyId} — UUID du tenant ;</li>
 *   <li>{@code sku} — code article unique par tenant ;</li>
 *   <li>{@code label} — libellé ;</li>
 *   <li>{@code unitOfMeasure} — unité (pièce, kg, l, m², …) ;</li>
 *   <li>{@code costingMethod} — FIFO ou WEIGHTED_AVERAGE ;</li>
 *   <li>{@code reorderThreshold} — seuil de réapprovisionnement (nullable) ;</li>
 *   <li>{@code inventoryAccountId} — UUID du compte de stock (ACTIF) ;</li>
 *   <li>{@code cogsAccountId} — UUID du compte de COGS (CHARGES).</li>
 * </ul>
 *
 * <p>Note : pas de champ {@code description} (n'existe pas sur l'entité backend) —
 * aligné avec la correction mobile E-8 #5 (InventoryItemDto).
 */
public record ItemResponse(
    UUID id,
    UUID companyId,
    String sku,
    String label,
    String unitOfMeasure,
    CostingMethod costingMethod,
    BigDecimal reorderThreshold,
    UUID inventoryAccountId,
    UUID cogsAccountId
) {}

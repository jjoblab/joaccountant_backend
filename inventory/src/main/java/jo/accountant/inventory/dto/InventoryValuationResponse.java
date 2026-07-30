package jo.accountant.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ligne de valorisation agrégée de stock — {@code GET .../inventory/valuation} (Part E1).
 *
 * <p>Une ligne par couple (article, entrepôt) ayant un stock restant &gt; 0. La quantité
 * et la valeur sont agrégées sur toutes les couches FIFO/moyen pondéré non épuisées de ce
 * couple. Le coût unitaire retourné est le coût moyen pondéré (= totalValue / quantity).
 *
 * <p>Utilisé pour le rapport de valorisation d'inventaire (CSV {@code inventory_valuation}
 * exposé par :reporting, Part E4).
 *
 * @param itemId      identifiant de l'article
 * @param sku         code article unique par tenant
 * @param label       libellé de l'article
 * @param warehouseId identifiant de l'entrepôt
 * @param warehouse   libellé de l'entrepôt (null si l'entrepôt a été supprimé)
 * @param quantity    quantité restante agrégée
 * @param unitCost    coût unitaire moyen pondéré (= totalValue / quantity, ou 0 si quantity=0)
 * @param totalValue  valeur totale = somme(quantity × unitCost) des couches restantes
 */
public record InventoryValuationResponse(
    UUID itemId,
    String sku,
    String label,
    UUID warehouseId,
    String warehouse,
    BigDecimal quantity,
    BigDecimal unitCost,
    BigDecimal totalValue
) {}

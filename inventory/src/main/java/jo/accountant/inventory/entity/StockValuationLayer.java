package jo.accountant.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Couche de valorisation de stock — utilisée pour le mode FIFO (§13 Phase 9).
 *
 * <p>Chaque entrée de stock ({@link StockMoveDirection#IN}) crée une couche avec la quantité
 * reçue et le coût unitaire. Les sorties ({@link StockMoveDirection#OUT}) consomment les
 * couches les plus anciennes en premier (First In, First Out).
 *
 * <p>{@link #quantityRemaining} diminue au fur et à mesure des sorties. Quand elle atteint 0,
 * la couche est épuisée mais conservée pour audit.
 *
 * <p>Pour {@link CostingMethod#WEIGHTED_AVERAGE}, cette entité n'est pas utilisée — le coût
 * moyen est recalculé à chaque entrée et stocké directement sur les StockMove.
 */
@Entity
@Table(name = "stock_valuation_layer")
public class StockValuationLayer extends TenantAwareEntity {

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** Quantité reçue à l'origine. */
    @Column(name = "quantity_received", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityReceived;

    /** Quantité restante — diminue avec les sorties. */
    @Column(name = "quantity_remaining", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityRemaining;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    /** Référence vers le StockMove qui a créé cette couche. */
    @Column(name = "source_stock_move_id")
    private UUID sourceStockMoveId;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getWarehouseId() { return warehouseId; }
    public void setWarehouseId(UUID warehouseId) { this.warehouseId = warehouseId; }

    public BigDecimal getQuantityReceived() { return quantityReceived; }
    public void setQuantityReceived(BigDecimal quantityReceived) { this.quantityReceived = quantityReceived; }

    public BigDecimal getQuantityRemaining() { return quantityRemaining; }
    public void setQuantityRemaining(BigDecimal quantityRemaining) { this.quantityRemaining = quantityRemaining; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public LocalDate getReceiptDate() { return receiptDate; }
    public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }

    public UUID getSourceStockMoveId() { return sourceStockMoveId; }
    public void setSourceStockMoveId(UUID sourceStockMoveId) { this.sourceStockMoveId = sourceStockMoveId; }
}

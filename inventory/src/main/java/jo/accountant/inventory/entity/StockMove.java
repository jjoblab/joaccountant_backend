package jo.accountant.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Mouvement de stock (§13 Phase 9).
 *
 * <p>Chaque mouvement a une direction ({@link StockMoveDirection}) :
 * <ul>
 *   <li>{@link StockMoveDirection#IN} — entrée : augmente le stock, crée une couche FIFO ou
 *       met à jour le coût moyen</li>
 *   <li>{@link StockMoveDirection#OUT} — sortie : diminue le stock, calcule le COGS selon la
 *       méthode de valorisation, génère une écriture comptable</li>
 *   <li>{@link StockMoveDirection#TRANSFER} — transfert entre entrepôts</li>
 * </ul>
 *
 * <p>{@link #journalEntryId} référence l'écriture comptable générée pour les sorties
 * (COGS). Null pour les entrées (pas d'écriture comptable en Phase 9 — l'entrée de stock
 * est constatée au moment de la facture fournisseur, Phase 12) et pour les transferts.
 */
@Entity
@Table(name = "stock_move")
public class StockMove extends TenantAwareEntity {

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** Pour TRANSFER uniquement : entrepôt de destination. */
    @Column(name = "to_warehouse_id")
    private UUID toWarehouseId;

    @Column(name = "move_date", nullable = false)
    private LocalDate moveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private StockMoveDirection direction;

    /** Quantité — toujours positive. Le sens est donné par {@link #direction}. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** Coût unitaire. Pour IN : coût d'achat. Pour OUT : calculé selon costingMethod. */
    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    /** Coût total = quantity × unitCost. */
    @Column(name = "total_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCost;

    /** Document source (ex. numéro de facture, bon de livraison). */
    @Column(name = "source_document", length = 100)
    private String sourceDocument;

    /** ID de l'écriture comptable générée (COGS pour les sorties). Null pour IN et TRANSFER. */
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getWarehouseId() { return warehouseId; }
    public void setWarehouseId(UUID warehouseId) { this.warehouseId = warehouseId; }

    public UUID getToWarehouseId() { return toWarehouseId; }
    public void setToWarehouseId(UUID toWarehouseId) { this.toWarehouseId = toWarehouseId; }

    public LocalDate getMoveDate() { return moveDate; }
    public void setMoveDate(LocalDate moveDate) { this.moveDate = moveDate; }

    public StockMoveDirection getDirection() { return direction; }
    public void setDirection(StockMoveDirection direction) { this.direction = direction; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }

    public String getSourceDocument() { return sourceDocument; }
    public void setSourceDocument(String sourceDocument) { this.sourceDocument = sourceDocument; }

    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }
}

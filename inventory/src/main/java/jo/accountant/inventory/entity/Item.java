package jo.accountant.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Article / produit stocké (§13.
 *
 * <p>Référence deux comptes du plan comptable :
 * <ul>
 * <li>{@link #inventoryAccountId} — compte de stock (ex. 30 "Stocks de marchandises" en SYSCOHADA)</li>
 * <li>{@link #cogsAccountId} — compte de COGS / variation de stock (ex. 603 "Variation de stocks")</li>
 * </ul>
 *
 * <p>{@link #costingMethod} détermine comment les sorties sont valorisées :
 * <ul>
 * <li>{@link CostingMethod#FIFO} — utilise {@link StockValuationLayer} pour suivre les couches</li>
 * <li>{@link CostingMethod#WEIGHTED_AVERAGE} — recalcule le coût moyen à chaque entrée</li>
 * </ul>
 *
 * <p>{@link #reorderThreshold} — si renseigné et que le stock total passe sous ce seuil,
 * un événement de domaine est publié (consommé par :notifications. Nullable :
 * null = pas de seuil défini pour cet article.
 */
@Entity
@Table(name = "item",
    uniqueConstraints = @UniqueConstraint(name = "uc_item_company_sku",
        columnNames = {"company_id", "sku"}))
/**
 * Item.
 *
 * @author jo@Dev


 */

public class Item extends TenantAwareEntity {

    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "unit_of_measure", nullable = false, length = 20)
    private String unitOfMeasure;

    @Enumerated(EnumType.STRING)
    @Column(name = "costing_method", nullable = false, length = 20)
    private CostingMethod costingMethod = CostingMethod.FIFO;

    /** Seuil de réapprovisionnement. Null = pas de seuil. */
    @Column(name = "reorder_threshold", precision = 19, scale = 4)
    private BigDecimal reorderThreshold;

    @Column(name = "inventory_account_id", nullable = false)
    private UUID inventoryAccountId;

    @Column(name = "cogs_account_id", nullable = false)
    private UUID cogsAccountId;

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getUnitOfMeasure() { return unitOfMeasure; }
    public void setUnitOfMeasure(String unitOfMeasure) { this.unitOfMeasure = unitOfMeasure; }

    public CostingMethod getCostingMethod() { return costingMethod; }
    public void setCostingMethod(CostingMethod costingMethod) { this.costingMethod = costingMethod; }

    public BigDecimal getReorderThreshold() { return reorderThreshold; }
    public void setReorderThreshold(BigDecimal reorderThreshold) { this.reorderThreshold = reorderThreshold; }

    public UUID getInventoryAccountId() { return inventoryAccountId; }
    public void setInventoryAccountId(UUID inventoryAccountId) { this.inventoryAccountId = inventoryAccountId; }

    public UUID getCogsAccountId() { return cogsAccountId; }
    public void setCogsAccountId(UUID cogsAccountId) { this.cogsAccountId = cogsAccountId; }
}

package jo.accountant.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Entrepôt de stockage (§13 Phase 9).
 *
 * <p>Un entrepôt est un lieu physique où est stocké un article. Une entreprise peut avoir
 * plusieurs entrepôts (ex. boutique principale, dépôt secondaire). Les transferts entre
 * entrepôts sont possibles via {@link StockMoveDirection#TRANSFER}.
 */
@Entity
@Table(name = "warehouse",
    uniqueConstraints = @UniqueConstraint(name = "uc_wh_company_label",
        columnNames = {"company_id", "label"}))
public class Warehouse extends TenantAwareEntity {

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}

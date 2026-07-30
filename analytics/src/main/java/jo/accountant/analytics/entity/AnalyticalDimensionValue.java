package jo.accountant.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Valeur d'une dimension analytique (§5).
 *
 * <p>Exemples : pour un plan "Fonds/Projets" — "Subvention CRS 2026", "Projet client X",
 * "Boutique Pétion-Ville". Hiérarchie parent/enfant optionnelle via {@link #parentId}.
 *
 * <p>Une valeur est rattachée à un plan via {@link #planId}. Le {@code companyId} est
 * répété ici (et non joint via le plan) pour permettre un filtrage tenant-direct sans
 * jointure — convention de performance pour les écritures volumineuses.
 */
@Entity
@Table(name = "analytical_dimension_value",
    uniqueConstraints = @UniqueConstraint(name = "uc_adv_plan_code",
        columnNames = {"plan_id", "code"}))
public class AnalyticalDimensionValue extends TenantAwareEntity {

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** Parent optionnel pour une hiérarchie parent/enfant. */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

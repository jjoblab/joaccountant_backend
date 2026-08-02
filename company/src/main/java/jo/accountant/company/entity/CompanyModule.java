package jo.accountant.company.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Flag d'activation de module par société (§11).
 *
 * <p>Consommé via {@code companyModuleService.isEnabled(companyId, moduleCode)} — jamais via un
 * check éparpillé {@code if (sector == ...)} dans le code métier (principe 7).
 *
 * <p>EST une {@link TenantAwareEntity} — toujours requêtée dans un contexte tenant.
 */
@Entity
@Table(name = "company_module",
    uniqueConstraints = @UniqueConstraint(name = "uc_company_module", columnNames = {"company_id", "module_code"}))
/**
 * CompanyModule.
 *
 * @author jo@Dev


 */

public class CompanyModule extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "module_code", nullable = false)
    private ModuleCode moduleCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    public ModuleCode getModuleCode() { return moduleCode; }
    public void setModuleCode(ModuleCode moduleCode) { this.moduleCode = moduleCode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
}

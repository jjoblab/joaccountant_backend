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

/**
 * Ligne du mapping « type métier → module activé » (§4.1 — restructuration :company).
 *
 * <p>Remplace le {@code switch} compilé {@code SectorModuleMapping.modulesFor(Sector)} par une
 * table de référence extensible sans déploiement. Une ligne = un {@link ModuleCode} activé
 * automatiquement à la complétion du wizard pour le {@link BusinessType} correspondant.
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} : donnée de référence
 * globale, comme {@link BusinessType} et {@code AccountingFramework}.
 */
@Entity
@Table(name = "business_type_module",
    uniqueConstraints = @UniqueConstraint(name = "uc_business_type_module",
        columnNames = {"business_type_code", "module_code"}))
public class BusinessTypeModule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private java.util.UUID id;

    @Column(name = "business_type_code", nullable = false, length = 60)
    private String businessTypeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "module_code", nullable = false, length = 40)
    private ModuleCode moduleCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public java.util.UUID getId() { return id; }
    public void setId(java.util.UUID id) { this.id = id; }
    public String getBusinessTypeCode() { return businessTypeCode; }
    public void setBusinessTypeCode(String code) { this.businessTypeCode = code; }
    public ModuleCode getModuleCode() { return moduleCode; }
    public void setModuleCode(ModuleCode code) { this.moduleCode = code; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

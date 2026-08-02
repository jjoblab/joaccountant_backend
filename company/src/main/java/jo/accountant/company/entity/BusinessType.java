package jo.accountant.company.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Type métier de l'organisation (§4.1 — restructuration du module :company).
 *
 * <p>Concept central du nouveau modèle : LE moteur d'activation des modules sectoriels.
 * Remplace l'ancien {@code switch} Java de {@code SectorModuleMapping} par une table de
 * référence extensible sans déploiement. Le {@code code} (clé naturelle) est référencé par
 * {@link Company#getBusinessTypeCode()} et par les tables {@code business_type_module}
 * et {@code business_type_required_field}.
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} : donnée de référence
 * <em>globale</em> (au même titre que {@code AccountingFramework} dans {@code :core}).
 * ArchUnit Rule 5 (qui restreint l'usage de {@code TenantAwareEntity} dans :company à
 * {@code CompanyModule}) est volontairement ciblée par nom.
 *
 * <p>Le code {@code CUSTOM} remplace l'ancien secteur {@code MIXTE} (bug documenté —
 * voir {@code company/README.md}) : un type métier {@code CUSTOM} signifie que l'utilisateur
 * sélectionne manuellement les modules à l'du wizard (aucune auto-activation).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "business_type")
public class BusinessType {

    /** Code naturel (clé primaire) — ex. {@code RETAIL_COMMERCE}, {@code SCHOOL}, {@code CUSTOM}. */
    @Id
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_organization_nature", nullable = false, length = 30)
    private OrganizationNature defaultOrganizationNature;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_sector", nullable = false, length = 30)
    private Sector defaultSector;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public OrganizationNature getDefaultOrganizationNature() { return defaultOrganizationNature; }
    public void setDefaultOrganizationNature(OrganizationNature n) { this.defaultOrganizationNature = n; }
    public Sector getDefaultSector() { return defaultSector; }
    public void setDefaultSector(Sector s) { this.defaultSector = s; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

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
 * Champ additionnel obligatoire pour un type métier donné (§4.1 — restructuration :company).
 *
 * <p>Modèle générique pour les champs spécifiques requis selon le type d'organisation —
 * ex. numéro d'agrément ministériel pour une école, numéro de licence sanitaire pour un
 * hôpital, numéro d'ordre professionnel pour un cabinet comptable, devise de reporting
 * bailleur pour une ONG. Les valeurs effectives sont stockées sur {@link Company#getExtraAttributes()}
 * (colonne JSONB).
 *
 * <p>Ces champs NE sont PAS codés un par un en Java — c'est exactement ce que cette table
 * doit éviter. Ajouter un champ obligatoire à un nouveau type métier = une insertion de
 * référence en base, pas une modification de code + redéploiement.
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} : donnée de référence
 * globale.
 */
@Entity
@Table(name = "business_type_required_field",
    uniqueConstraints = @UniqueConstraint(name = "uc_bt_required_field",
        columnNames = {"business_type_code", "field_key"}))
public class BusinessTypeRequiredField {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private java.util.UUID id;

    @Column(name = "business_type_code", nullable = false, length = 60)
    private String businessTypeCode;

    @Column(name = "field_key", nullable = false, length = 60)
    private String fieldKey;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    /** Type de la valeur attendue — oriente le rendu du formulaire dynamique côté client. */
    public enum FieldType { STRING, NUMBER, DATE, BOOLEAN }

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false, length = 20)
    private FieldType fieldType;

    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

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
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String key) { this.fieldKey = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public FieldType getFieldType() { return fieldType; }
    public void setFieldType(FieldType t) { this.fieldType = t; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean r) { this.required = r; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int o) { this.displayOrder = o; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

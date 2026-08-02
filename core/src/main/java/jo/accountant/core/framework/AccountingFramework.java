package jo.accountant.core.framework;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Données de référence décrivant chaque référentiel comptable supporté (§4).
 *
 * <p>Seed-only — non modifiable par les utilisateurs. Chargé par la migration
 * {@code V3__core_seeds.sql}.
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} — les référentiels sont des
 * données de référence globales partagées par tous les tenants.
 */
@Entity
@Table(name = "accounting_framework")
public class AccountingFramework {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "numbering_mode", nullable = false)
    private NumberingMode numberingMode;

    @Column(name = "label", nullable = false)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mandated_class_seed_json", columnDefinition = "jsonb")
    private String mandatedClassSeedJson;

    @Column(name = "mandatory_statements")
    private String mandatoryStatements;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public NumberingMode getNumberingMode() { return numberingMode; }
    public void setNumberingMode(NumberingMode numberingMode) { this.numberingMode = numberingMode; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getMandatedClassSeedJson() { return mandatedClassSeedJson; }
    public void setMandatedClassSeedJson(String mandatedClassSeedJson) { this.mandatedClassSeedJson = mandatedClassSeedJson; }
    public String getMandatoryStatements() { return mandatoryStatements; }
    public void setMandatoryStatements(String mandatoryStatements) { this.mandatoryStatements = mandatoryStatements; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

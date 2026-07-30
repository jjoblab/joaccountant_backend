package jo.accountant.tax.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tax.WithholdingBracketType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Règle de retenue à la source (§13 Phase 16).
 *
 * <p>Pertinent pour la retenue à la source sur prestations de service, fréquente en Haïti
 * et en zone OHADA. {@link #applicableThirdPartyTypes} filtre les tiers concernés
 * (ex. "SUPPLIER" pour la retenue sur factures fournisseurs).
 */
@Entity
@Table(name = "withholding_rule")
public class WithholdingRule {

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    /** Types de tiers concernés (JSONB array : ex. ["SUPPLIER"]). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applicable_third_party_types", columnDefinition = "jsonb")
    private String applicableThirdPartyTypes;

    /**
     * Type de barème — Finding #14.
     *
     * <p>{@link WithholdingBracketType#FLAT} (défaut) : taux unique appliqué à toute la base.
     * {@link WithholdingBracketType#PROGRESSIVE} : barème progressif par tranches, stocké dans
     * {@link #bracketsJson} au format {@code [{"threshold":0,"rate":0},...]}. La retenue est
     * calculée par tranches successives côté {@code PurchasingService.calculateSupplierWithholding}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "bracket_type", nullable = false, length = 15)
    private WithholdingBracketType bracketType = WithholdingBracketType.FLAT;

    /**
     * Barème progressif par tranches (JSONB) — Finding #14.
     *
     * <p>Format : {@code [{"threshold":0,"rate":0},{"threshold":50000,"rate":10},
     * {"threshold":100000,"rate":15}]}. Chaque entrée définit un palier : la part de la base
     * comprise entre ce {@code threshold} et le suivant est taxée au {@code rate} indiqué.
     * Le dernier palier est ouvert (pas de plafond).
     *
     * <p>Utilisé uniquement quand {@link #bracketType} = {@link WithholdingBracketType#PROGRESSIVE}.
     * Ignoré sinon. Null autorisé (sera interprété comme un barème vide → retenue 0).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brackets_json", columnDefinition = "jsonb")
    private String bracketsJson;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public String getApplicableThirdPartyTypes() { return applicableThirdPartyTypes; }
    public void setApplicableThirdPartyTypes(String applicableThirdPartyTypes) { this.applicableThirdPartyTypes = applicableThirdPartyTypes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public WithholdingBracketType getBracketType() { return bracketType; }
    public void setBracketType(WithholdingBracketType bracketType) {
        this.bracketType = bracketType != null ? bracketType : WithholdingBracketType.FLAT;
    }
    public String getBracketsJson() { return bracketsJson; }
    public void setBracketsJson(String bracketsJson) { this.bracketsJson = bracketsJson; }
}

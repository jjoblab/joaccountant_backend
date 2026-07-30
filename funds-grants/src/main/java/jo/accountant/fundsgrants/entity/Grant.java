package jo.accountant.fundsgrants.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Subvention / don d'un bailleur (§13 Phase 14).
 *
 * <p>Une subvention est rattachée à un bailleur (ThirdParty de type DONOR) et optionnellement
 * à une valeur analytique (plan "Fonds/Projets") qui permet de tracer les charges et produits
 * liés à cette subvention.
 *
 * <p>{@link #restrictionType} détermine si le mécanisme des fonds dédiés s'applique à la clôture :
 * <ul>
 *   <li>{@link RestrictionType#RESTRICTED} → à la clôture, calcul du solde (produits − charges
 *       tagués analytiquement). Si positif (ressource affectée non utilisée), soumission d'une
 *       {@link jo.accountant.approvalworkflow.entity.ApprovalRequest} pour l'écriture proposée.</li>
 *   <li>{@link RestrictionType#UNRESTRICTED} → pas de fonds dédiés.</li>
 * </ul>
 */
@Entity
@Table(name = "fg_grant",
    uniqueConstraints = @UniqueConstraint(name = "uc_fg_grant_company_code",
        columnNames = {"company_id", "code"}))
public class Grant extends TenantAwareEntity {

    /** Bailleur (ThirdParty de type DONOR). */
    @Column(name = "donor_third_party_id", nullable = false)
    private UUID donorThirdPartyId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "restriction_type", nullable = false, length = 15)
    private RestrictionType restrictionType = RestrictionType.RESTRICTED;

    /**
     * Valeur analytique (plan "Fonds/Projets") rattachée à cette subvention.
     * Permet de tracer les charges et produits liés via les tags analytiques des écritures.
     */
    @Column(name = "analytical_value_id")
    private UUID analyticalValueId;

    public UUID getDonorThirdPartyId() { return donorThirdPartyId; }
    public void setDonorThirdPartyId(UUID donorThirdPartyId) { this.donorThirdPartyId = donorThirdPartyId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public RestrictionType getRestrictionType() { return restrictionType; }
    public void setRestrictionType(RestrictionType restrictionType) { this.restrictionType = restrictionType; }

    public UUID getAnalyticalValueId() { return analyticalValueId; }
    public void setAnalyticalValueId(UUID analyticalValueId) { this.analyticalValueId = analyticalValueId; }
}

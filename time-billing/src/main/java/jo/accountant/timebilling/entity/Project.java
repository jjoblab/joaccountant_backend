package jo.accountant.timebilling.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Projet de prestation de service (§13.
 *
 * <p>Un projet est rattaché optionnellement à un client (tiers de type CLIENT, via
 * {@link #clientThirdPartyId}). Le {@code code} est unique par entreprise.
 *
 * <p>{@link #billingType} détermine comment le projet est facturé :
 * <ul>
 * <li>{@link BillingType#FIXED_FEE} — forfait : prix convenu, WIP informatif</li>
 * <li>{@link BillingType#TIME_AND_MATERIALS} — régie : facturation au temps passé × taux</li>
 * </ul>
 */
@Entity
@Table(name = "tb_project",
    uniqueConstraints = @UniqueConstraint(name = "uc_tb_project_company_code",
        columnNames = {"company_id", "code"}))
/**
 * Project.
 *
 * @author jo@Dev


 */

public class Project extends TenantAwareEntity {

    /** Tiers client (optionnel — null pour un projet interne). */
    @Column(name = "client_third_party_id")
    private UUID clientThirdPartyId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_type", nullable = false, length = 25)
    private BillingType billingType = BillingType.TIME_AND_MATERIALS;

    public UUID getClientThirdPartyId() { return clientThirdPartyId; }
    public void setClientThirdPartyId(UUID clientThirdPartyId) { this.clientThirdPartyId = clientThirdPartyId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    public BillingType getBillingType() { return billingType; }
    public void setBillingType(BillingType billingType) { this.billingType = billingType; }
}

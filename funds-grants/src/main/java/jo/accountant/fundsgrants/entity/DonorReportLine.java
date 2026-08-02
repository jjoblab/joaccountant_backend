package jo.accountant.fundsgrants.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne de rapport bailleur ventilée par cost category (v6-3 — formats bailleurs structurés).
 *
 * <p>Une {@code DonorReportLine} matérialise, pour un (grant, année, trimestre,
 * cost_category) donné, les montants :
 * <ul>
 * <li>{@link #budgetAmount} — budget alloué à cette catégorie pour la période.</li>
 * <li>{@link #actualAmount} — montant réel dépensé (issu des écritures comptables
 * taguées par grant + cost_category — alimentation à implémenter en v7).</li>
 * <li>{@link #varianceAmount} — colonne GENERATED ALWAYS AS STORED au niveau DB
 * (= budget − actual). Marquée {@code insertable = false, updatable = false} côté JPA.</li>
 * <li>{@link #costShareAmount} — participation de l'ONG (cost share / match funding).</li>
 * </ul>
 *
 * <p>Le service {@link jo.accountant.fundsgrants.service.DonorReportExporter} agrège
 * ces lignes pour produire les CSV structurés conformes aux formats bailleurs
 * (USAID SF-425, EU PRAG, Banque Mondiale).
 *
 * <p><b>ÉTAT D'AVANCEMENT</b> : squelette. L'alimentation réelle des lignes depuis les
 * écritures comptables sera faite en v7 via un job de ventilation post-écriture.
 * En attendant, les exports retournent des zéros avec une structure CSV valide.
 */
@Entity
@Table(name = "donor_report_line",
    uniqueConstraints = @UniqueConstraint(name = "uc_donor_report_line_uniq",
        columnNames = {"company_id", "grant_id", "period_year", "period_quarter", "cost_category"}))
/**
 * DonorReportLine.
 *
 * @author jo@Dev


 */

public class DonorReportLine extends TenantAwareEntity {

    @Column(name = "grant_id", nullable = false)
    private UUID grantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "donor_type", nullable = false, length = 20)
    private DonorType donorType;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    /**
     * Trimestre 1-4 pour les rapports trimestriels (USAID SF-425, BM).
     * NULL pour les rapports annuels (EU PRAG).
     */
    @Column(name = "period_quarter")
    private Integer periodQuarter;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_category", nullable = false, length = 50)
    private CostCategory costCategory;

    @Column(name = "budget_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal budgetAmount = BigDecimal.ZERO;

    @Column(name = "actual_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal actualAmount = BigDecimal.ZERO;

    /**
     * Variance = budget − actual. Colonne GENERATED ALWAYS AS STORED côté DB — JPA
     * ne l'écrit jamais (insertable = false, updatable = false). La valeur est lue
     * fraîchement depuis la DB à chaque requête via le repository.
     */
    @Column(name = "variance_amount", insertable = false, updatable = false,
        precision = 19, scale = 4)
    private BigDecimal varianceAmount;

    @Column(name = "cost_share_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal costShareAmount = BigDecimal.ZERO;

    @Column(name = "description", length = 500)
    private String description;

    public UUID getGrantId() { return grantId; }
    public void setGrantId(UUID grantId) { this.grantId = grantId; }

    public DonorType getDonorType() { return donorType; }
    public void setDonorType(DonorType donorType) { this.donorType = donorType; }

    public int getPeriodYear() { return periodYear; }
    public void setPeriodYear(int periodYear) { this.periodYear = periodYear; }

    public Integer getPeriodQuarter() { return periodQuarter; }
    public void setPeriodQuarter(Integer periodQuarter) { this.periodQuarter = periodQuarter; }

    public CostCategory getCostCategory() { return costCategory; }
    public void setCostCategory(CostCategory costCategory) { this.costCategory = costCategory; }

    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) {
        this.budgetAmount = budgetAmount == null ? BigDecimal.ZERO : budgetAmount;
    }

    public BigDecimal getActualAmount() { return actualAmount; }
    public void setActualAmount(BigDecimal actualAmount) {
        this.actualAmount = actualAmount == null ? BigDecimal.ZERO : actualAmount;
    }

    public BigDecimal getVarianceAmount() { return varianceAmount; }

    public BigDecimal getCostShareAmount() { return costShareAmount; }
    public void setCostShareAmount(BigDecimal costShareAmount) {
        this.costShareAmount = costShareAmount == null ? BigDecimal.ZERO : costShareAmount;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

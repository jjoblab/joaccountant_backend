package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Période fiscale — typiquement mensuelle (§13.
 *
 * <p>Une période appartient à un {@link FiscalYear}. Par défaut, un exercice est découpé en
 * 12 périodes mensuelles, mais l'utilisateur peut créer des périodes personnalisées
 * (par exemple trimestrielles) si besoin.
 *
 * <p>Le postage d'une écriture exige que la période correspondant à la date d'écriture soit
 * {@link FiscalPeriodStatus#OPEN}. Une période LOCKED refuse toute nouvelle écriture.
 */
@Entity
@Table(name = "fiscal_period",
    uniqueConstraints = @UniqueConstraint(name = "uc_fp_year_dates",
        columnNames = {"fiscal_year_id", "start_date", "end_date"}))
/**
 * FiscalPeriod.
 *
 * @author jo@Dev


 */

public class FiscalPeriod extends TenantAwareEntity {

    @Column(name = "fiscal_year_id", nullable = false)
    private UUID fiscalYearId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private FiscalPeriodStatus status = FiscalPeriodStatus.OPEN;

    @Column(name = "label", length = 50)
    private String label;

    public UUID getFiscalYearId() { return fiscalYearId; }
    public void setFiscalYearId(UUID fiscalYearId) { this.fiscalYearId = fiscalYearId; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public FiscalPeriodStatus getStatus() { return status; }
    public void setStatus(FiscalPeriodStatus status) { this.status = status; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}

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
 * Exercice fiscal d'une entreprise (§13 Phase 5).
 *
 * <p>Un exercice est défini par sa plage de dates (startDate → endDate). Les exercices ne
 * peuvent pas se chevaucher pour une même entreprise (vérifié côté service).
 *
 * <p>Statuts :
 * <ul>
 *   <li>{@link FiscalYearStatus#OPEN} à la création.</li>
 *   <li>{@link FiscalYearStatus#LOCKED} — en cours de clôture, plus aucune écriture.</li>
 *   <li>{@link FiscalYearStatus#CLOSED} — définitivement clôturé.</li>
 * </ul>
 *
 * <p>Une fois CLOSED, l'exercice et toutes ses périodes sont immuables. La clôture définitive
 * est typiquement faite après génération des états financiers (Phase 6) et des écritures
 * de clôture (report à nouveau, résultat de l'exercice).
 */
@Entity
@Table(name = "fiscal_year",
    uniqueConstraints = @UniqueConstraint(name = "uc_fy_company_dates",
        columnNames = {"company_id", "start_date", "end_date"}))
public class FiscalYear extends TenantAwareEntity {

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private FiscalYearStatus status = FiscalYearStatus.OPEN;

    @Column(name = "label", length = 100)
    private String label;

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public FiscalYearStatus getStatus() { return status; }
    public void setStatus(FiscalYearStatus status) { this.status = status; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}

package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Exercice fiscal d'une entreprise (§13.
 *
 * <p>Un exercice est défini par sa plage de dates (startDate → endDate). Les exercices ne
 * peuvent pas se chevaucher pour une même entreprise (vérifié côté service).
 *
 * <p>Statuts :
 * <ul>
 * <li>{@link FiscalYearStatus#OPEN} à la création.</li>
 * <li>{@link FiscalYearStatus#LOCKED} — en cours de clôture, plus aucune écriture.</li>
 * <li>{@link FiscalYearStatus#CLOSED} — définitivement clôturé.</li>
 * </ul>
 *
 * <p>Une fois CLOSED, l'exercice et toutes ses périodes sont immuables. La clôture définitive
 * est typiquement faite après génération des états financierset des écritures
 * de clôture (report à nouveau, résultat de l'exercice).
 */
@Entity
@Table(name = "fiscal_year",
    uniqueConstraints = @UniqueConstraint(name = "uc_fy_company_dates",
        columnNames = {"company_id", "start_date", "end_date"}))
/**
 * FiscalYear.
 *
 * @author jo@Dev


 */

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

    /**
     * Fix Dim 5 H4 (audit v9.4) — Timestamp de clôture de l'exercice.
     * NULL tant que l'exercice est OPEN/LOCKED. Peuplé par FiscalYearClosingService.closeFiscalYear.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Fix Dim 5 H4 (audit v9.4) — ID de l'utilisateur qui a clôturé l'exercice.
     * NULL tant que l'exercice est OPEN/LOCKED. Peuplé par FiscalYearClosingService.closeFiscalYear.
     */
    @Column(name = "closed_by")
    private UUID closedBy;

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public FiscalYearStatus getStatus() { return status; }
    public void setStatus(FiscalYearStatus status) { this.status = status; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public UUID getClosedBy() { return closedBy; }
    public void setClosedBy(UUID closedBy) { this.closedBy = closedBy; }
}

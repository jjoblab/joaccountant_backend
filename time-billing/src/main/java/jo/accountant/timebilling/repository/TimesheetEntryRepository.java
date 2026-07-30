package jo.accountant.timebilling.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.timebilling.entity.TimesheetEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimesheetEntryRepository extends JpaRepository<TimesheetEntry, UUID> {

    /** Toutes les entrées d'un projet. */
    List<TimesheetEntry> findByProjectIdOrderByEntryDate(UUID projectId);

    /** Entrées facturables (approved + billable + non invoiced) d'un projet — pour GET /unbilled. */
    List<TimesheetEntry> findByProjectIdAndApprovedTrueAndBillableTrueAndInvoicedFalseOrderByEntryDate(UUID projectId);

    /**
     * Toutes les entrées d'une entreprise dont la {@code entryDate} est comprise entre
     * {@code start} et {@code end} (inclus), triées par {@code entryDate} descendant.
     *
     * <p>Utilisé par {@code GET /api/v1/companies/{companyId}/time-billing/utilization?from=&to=}
     * (Part E3) pour agréger les heures par (projet, consultant) sur la période.
     */
    List<TimesheetEntry> findByCompanyIdAndEntryDateBetweenOrderByEntryDateDesc(
        UUID companyId, LocalDate start, LocalDate end);
}

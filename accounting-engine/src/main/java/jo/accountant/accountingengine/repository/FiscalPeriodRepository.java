package jo.accountant.accountingengine.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalPeriodStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des périodes fiscales.
 */
public interface FiscalPeriodRepository extends JpaRepository<FiscalPeriod, UUID> {

    /** Toutes les périodes d'un exercice, triées par date de début. */
    List<FiscalPeriod> findByFiscalYearIdOrderByStartDateAsc(UUID fiscalYearId);

    /** Période contenant la date donnée, dans l'exercice donné. */
    Optional<FiscalPeriod> findByFiscalYearIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
        UUID fiscalYearId, LocalDate date, LocalDate date2);

    /** Périodes par entreprise et statut. */
    List<FiscalPeriod> findByFiscalYearIdAndStatus(UUID fiscalYearId, FiscalPeriodStatus status);
}

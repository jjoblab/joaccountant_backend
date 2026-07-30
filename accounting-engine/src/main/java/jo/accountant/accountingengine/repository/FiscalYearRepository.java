package jo.accountant.accountingengine.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.FiscalYearStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des exercices fiscaux.
 */
public interface FiscalYearRepository extends JpaRepository<FiscalYear, UUID> {

    /** Tous les exercices de l'entreprise, triés par date de début. */
    List<FiscalYear> findByCompanyIdOrderByStartDateAsc(UUID companyId);

    /** Exercice chevauchant la date donnée (devrait être unique pour une entreprise saine). */
    Optional<FiscalYear> findByCompanyIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
        UUID companyId, LocalDate startDate, LocalDate endDate);

    /** Compte les exercices OPEN — utile pour empêcher la création d'un nouvel exercice
     *  si l'ancien n'est pas clôturé (selon politique, optionnel). */
    long countByCompanyIdAndStatus(UUID companyId, FiscalYearStatus status);
}

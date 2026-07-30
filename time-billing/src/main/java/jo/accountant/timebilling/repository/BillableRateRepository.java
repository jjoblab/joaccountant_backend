package jo.accountant.timebilling.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.timebilling.entity.BillableRate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillableRateRepository extends JpaRepository<BillableRate, UUID> {

    /** Toutes les tarifs de l'entreprise. */
    List<BillableRate> findByCompanyId(UUID companyId);

    /** Taux spécifique au couple (projet, ressource) — le plus spécifique. */
    Optional<BillableRate> findByCompanyIdAndProjectIdAndResourceUserId(
        UUID companyId, UUID projectId, UUID resourceUserId);

    /** Taux spécifique au projet (toutes ressources). */
    Optional<BillableRate> findByCompanyIdAndProjectIdAndResourceUserIdIsNull(
        UUID companyId, UUID projectId);

    /** Taux spécifique à la ressource (tous projets). */
    Optional<BillableRate> findByCompanyIdAndProjectIdIsNullAndResourceUserId(
        UUID companyId, UUID resourceUserId);

    /** Taux par défaut de l'entreprise. */
    Optional<BillableRate> findByCompanyIdAndProjectIdIsNullAndResourceUserIdIsNull(UUID companyId);
}

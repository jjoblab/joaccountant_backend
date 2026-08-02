package jo.accountant.analytics.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.analytics.entity.AnalyticalDimensionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des plans analytiques.
 
 *
 * @author jo@Dev


*/
public interface AnalyticalDimensionPlanRepository
    extends JpaRepository<AnalyticalDimensionPlan, UUID> {

    /** Plan par code, dans l'entreprise donnée. */
    Optional<AnalyticalDimensionPlan> findByCompanyIdAndCode(UUID companyId, String code);

    /** Tous les plans de l'entreprise. */
    List<AnalyticalDimensionPlan> findByCompanyId(UUID companyId);

    /** Compte les plans actifs — pour appliquer la recommandation "2 à 4 max". */
    long countByCompanyIdAndActiveTrue(UUID companyId);
}

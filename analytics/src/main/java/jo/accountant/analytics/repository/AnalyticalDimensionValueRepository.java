package jo.accountant.analytics.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.analytics.entity.AnalyticalDimensionValue;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des valeurs analytiques.
 */
public interface AnalyticalDimensionValueRepository
    extends JpaRepository<AnalyticalDimensionValue, UUID> {

    /** Valeur par (planId, code). */
    Optional<AnalyticalDimensionValue> findByPlanIdAndCode(UUID planId, String code);

    /** Toutes les valeurs d'un plan. */
    List<AnalyticalDimensionValue> findByPlanIdOrderByCode(UUID planId);

    /** Vérifie l'existence d'une valeur (utile pour validation côté accounting-engine). */
    Optional<AnalyticalDimensionValue> findByIdAndPlanId(UUID id, UUID planId);
}

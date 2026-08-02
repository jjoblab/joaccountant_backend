package jo.accountant.notifications.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.notifications.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA AlertRule.
 *
 * @author jo@Dev


 */

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {
    List<AlertRule> findByCompanyId(UUID companyId);
    List<AlertRule> findByCompanyIdAndActiveTrue(UUID companyId);
}

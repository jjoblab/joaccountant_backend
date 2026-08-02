package jo.accountant.notifications.repository;

import jo.accountant.notifications.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA NotificationTemplate.
 *
 * @author jo@Dev


 */

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}

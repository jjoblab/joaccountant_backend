package jo.accountant.notifications.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.notifications.entity.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA NotificationPreference.
 *
 * @author jo@Dev


 */

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    Optional<NotificationPreference> findByUserIdAndCompanyIdAndType(UUID userId, UUID companyId, String type);
}

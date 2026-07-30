package jo.accountant.notifications.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.notifications.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des notifications.
 *
 * <p><b>Finding #3 — pagination Pageable</b> : variantes paginées ({@code Page<>}) disponibles
 * pour l'endpoint {@code GET /notifications}. Les variantes {@code List<>} sont conservées pour
 * rétro-compatibilité (appels internes sans pagination).
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);
    List<Notification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(UUID recipientUserId, jo.accountant.notifications.entity.NotificationStatus status);

    // ── Finding #3 — variantes paginées (rétro-compat : les méthodes List<> ci-dessus sont conservées) ──

    /** Variante paginée — toutes les notifications d'un utilisateur, triées par createdAt desc. */
    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);
}

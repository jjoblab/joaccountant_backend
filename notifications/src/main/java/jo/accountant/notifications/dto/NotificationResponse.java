package jo.accountant.notifications.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.notifications.entity.NotificationChannel;
import jo.accountant.notifications.entity.NotificationStatus;

/**
 * NotificationResponse.
 *
 * @author jo@Dev


 */

public record NotificationResponse(
    UUID id, UUID companyId, UUID recipientUserId, String type,
    String payloadJson, NotificationChannel channel, NotificationStatus status,
    Instant createdAt, Instant sentAt, Instant readAt
) {}

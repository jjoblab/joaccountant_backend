package jo.accountant.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement d'audit standardisé publié par chaque module métier lors des create/update/activate/delete
 * (§3.6). Consommé de manière asynchrone par le listener de :audit-trail — même pattern
 * architectural réutilisé par :notifications (§9).
 *
 * <p>Value object immuable. Utiliser la fabrique statique {@link #of}.
 */
public record AuditEvent(
    UUID companyId,
    UUID actorUserId,
    String entityType,
    UUID entityId,
    String action,
    String oldValueJson,
    String newValueJson,
    Instant occurredAt,
    String correlationId
) {

    public static AuditEvent of(UUID companyId, UUID actorUserId, String entityType, UUID entityId,
                                String action, String oldValueJson, String newValueJson,
                                String correlationId) {
        return new AuditEvent(
            companyId,
            actorUserId,
            entityType,
            entityId,
            action,
            oldValueJson,
            newValueJson,
            Instant.now(),
            correlationId == null ? UUID.randomUUID().toString() : correlationId
        );
    }
}

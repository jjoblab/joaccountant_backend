package jo.accountant.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement d'audit standardisé publié par chaque module métier lors des create/update/activate/delete
 * (§3.6). Consommé de manière asynchrone par le listener de :audit-trail — même pattern
 * architectural réutilisé par :notifications (§9).
 *
 * <p>Value object immuable. Utiliser la fabrique statique {@link #of}.
 
 *
 * @author jo@Dev


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
    String correlationId,
    // v9.4 fix — Champs forensiques capturés au moment de la publication de l'événement
    // (pas au moment de la consommation asynchrone). Sans ces champs, le listener asynchrone
    // @TransactionalEventListener(AFTER_COMMIT) tourne sur un thread différent du ThreadLocal
    // TenantContext → IP/userAgent/executionContext sont perdus (null).
    String ipAddress,
    String userAgent,
    String executionContext
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
            correlationId == null ? UUID.randomUUID().toString() : correlationId,
            // v9.4 — Capturer les champs forensiques depuis TenantContext AU MOMENT de
            // la création de l'événement (thread HTTP courant, pas le thread async du listener).
            jo.accountant.core.tenant.TenantContext.getIpAddress(),
            jo.accountant.core.tenant.TenantContext.getUserAgent(),
            jo.accountant.core.tenant.TenantContext.getExecutionContext()
        );
    }

    /**
     * Constructeur backward-compat sans les champs forensiques (pour les callers existants
     * qui n'ont pas encore été migrés). Les champs forensiques sont null.
     */
    public static AuditEvent ofLegacy(UUID companyId, UUID actorUserId, String entityType, UUID entityId,
                                      String action, String oldValueJson, String newValueJson,
                                      String correlationId) {
        return new AuditEvent(
            companyId, actorUserId, entityType, entityId, action,
            oldValueJson, newValueJson, Instant.now(),
            correlationId == null ? UUID.randomUUID().toString() : correlationId,
            null, null, null
        );
    }
}

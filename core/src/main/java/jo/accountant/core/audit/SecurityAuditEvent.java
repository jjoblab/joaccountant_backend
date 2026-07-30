package jo.accountant.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Événement d'audit <b>sécurité</b> — variante de {@link AuditEvent} pour les actions qui ne
 * sont pas des mutations métier (CRUD) mais des événements de sécurité : LOGIN_SUCCESS,
 * LOGIN_FAILED, LOGOUT, REFRESH_TOKEN_ROTATED, REFRESH_TOKEN_REUSED, PASSWORD_RESET_REQUESTED,
 * PASSWORD_RESET_CONSUMED, ROLE_INVITED, ROLE_UPDATED, ROLE_ACCEPTED, ACCESS_DENIED,
 * ACCOUNT_DISABLED.
 *
 * <p><b>Audit v4.7 §6.2 Finding #5 — FIX CRITIQUE</b> : la v4.7 n'auditait QUE les mutations
 * métier (28 événements REGISTER/CREATE/POST/REVERSE/ISSUE/APPROVE via {@link AuditableAction}).
 * Aucun événement de sécurité n'était tracé — en cas d'incident (compromission, brute-force,
 * élévation de privilège), la forensique était impossible.
 *
 * <p><b>N'implémente PAS {@link AuditableAction}</b> — volontairement. Le listener générique
 * {@code AuditEventListener.onAuditableAction} utilise
 * {@code @TransactionalEventListener(AFTER_COMMIT)} qui ne se déclenche qu'après commit.
 * Or beaucoup d'événements de sécurité (LOGIN_FAILED, REFRESH_TOKEN_REUSED, ACCESS_DENIED)
 * surviennent dans des transactions qui <b>roll back</b> → l'audit ne serait jamais persisté.
 * Le listener dédié {@code SecurityAuditEventListener} utilise
 * {@code @TransactionalEventListener(AFTER_COMPLETION)} qui se déclenche après commit OU
 * rollback — c'est la différence cruciale.
 *
 * <p>{@code entityType} est positionné à {@code "SecurityEvent"} dans le listener dédié pour
 * distinguer ces événements des mutations métier et permettre des requêtes d'investigation
 * ciblées :
 * <pre>SELECT * FROM audit_log WHERE entity_type = 'SecurityEvent' AND occurred_at &gt;= NOW() - INTERVAL '7 days';</pre>
 *
 * <p>{@code entityId} est positionné à l'ID utilisateur quand applicable, {@code null} sinon
 * (ex : LOGIN_FAILED sur email inconnu — pas d'userId). {@code newValueJson} contient les
 * métadonnées additionnelles (IP, user-agent, reason, etc.) sérialisées en JSON par le listener.
 *
 * @param eventType     type normalisé (ex : "LOGIN_SUCCESS") — sert de {@code action} dans audit_log
 * @param actorUserId   ID utilisateur (peut être {@code null} si inconnu — ex : login sur email inexistant)
 * @param companyId     ID company (souvent {@code null} pour les événements pré-auth)
 * @param metadata      métadonnées additionnelles (IP, user-agent, etc.) — sérialisées en JSON
 * @param occurredAt    instant de l'événement
 * @param correlationId ID de corrélation MDC (pour croiser avec les logs)
 */
public record SecurityAuditEvent(
    String eventType,
    UUID actorUserId,
    UUID companyId,
    Map<String, Object> metadata,
    Instant occurredAt,
    String correlationId
) {

    /**
     * Fabrique statique — {@code occurredAt} et {@code correlationId} sont auto-remplis si
     * non fournis.
     */
    public static SecurityAuditEvent of(String eventType, UUID actorUserId, UUID companyId,
                                         Map<String, Object> metadata, String correlationId) {
        return new SecurityAuditEvent(
            eventType,
            actorUserId,
            companyId,
            metadata != null ? metadata : Map.of(),
            Instant.now(),
            correlationId != null ? correlationId : UUID.randomUUID().toString()
        );
    }

    /**
     * Type d'événement de sécurité — constantes publiques pour usage type-safe côté services
     * publiant ces événements. La sérialisation dans {@code audit_log.action} est le nom string
     * exact (ex : "LOGIN_SUCCESS").
     */
    public static final class Types {
        public static final String LOGIN_SUCCESS            = "LOGIN_SUCCESS";
        public static final String LOGIN_FAILED             = "LOGIN_FAILED";
        public static final String LOGOUT                   = "LOGOUT";
        public static final String REFRESH_TOKEN_ROTATED    = "REFRESH_TOKEN_ROTATED";
        public static final String REFRESH_TOKEN_REUSED     = "REFRESH_TOKEN_REUSED";
        public static final String REFRESH_TOKEN_EXPIRED    = "REFRESH_TOKEN_EXPIRED";
        public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
        public static final String PASSWORD_RESET_CONSUMED  = "PASSWORD_RESET_CONSUMED";
        public static final String ROLE_INVITED             = "ROLE_INVITED";
        public static final String ROLE_UPDATED             = "ROLE_UPDATED";
        public static final String ROLE_ACCEPTED            = "ROLE_ACCEPTED";
        public static final String ACCESS_DENIED            = "ACCESS_DENIED";
        public static final String ACCOUNT_DISABLED         = "ACCOUNT_DISABLED";

        private Types() {}
    }
}

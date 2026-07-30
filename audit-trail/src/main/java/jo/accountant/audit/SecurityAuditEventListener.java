package jo.accountant.audit;

import java.util.UUID;
import jo.accountant.core.audit.SecurityAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listener dédié aux événements de {@link SecurityAuditEvent}.
 *
 * <p><b>Audit v4.7 §6.2 Finding #5 — FIX CRITIQUE</b> : la v4.7 n'auditait QUE les mutations
 * métier via {@link AuditEventListener} (qui écoute {@link jo.accountant.core.audit.AuditableAction}
 * → {@code @TransactionalEventListener(AFTER_COMMIT)}). Or les événements de sécurité comme
 * LOGIN_FAILED, REFRESH_TOKEN_REUSED, ACCESS_DENIED surviennent dans des transactions qui
 * <b>roll back</b> (la méthode lance une exception) → l'audit n'était JAMAIS persisté pour
 * ces cas critiques — qui sont précisément ceux qu'on veut tracer en forensique.
 *
 * <p>Ce listener utilise {@code @TransactionalEventListener(phase = AFTER_COMPLETION)} qui se
 * déclenche <b>que la transaction commit ou roll back</b>. C'est la différence cruciale :
 * <ul>
 *   <li>{@code AFTER_COMMIT} (par défaut) : uniquement après commit → rate les failed logins.</li>
 *   <li>{@code AFTER_COMPLETION} (ici) : après commit OU rollback → trace tout.</li>
 * </ul>
 *
 * <p>{@code @Async} pour ne pas bloquer le thread métier — l'écriture en base se fait dans un
 * pool dédié (configuré via {@code @EnableAsync} au niveau de l'application).
 *
 * <p><b>Note sur la cohérence transactionnelle</b> : comme l'audit est écrit dans une nouvelle
 * transaction (le {@code @TransactionalEventListener} s'exécute après la fin de la transaction
 * métier, et {@code repository.save} ouvre sa propre transaction), l'audit est <b>toujours</b>
 * persisté, même si la transaction métier roll back. C'est exactement le contrat voulu pour
 * les événements de sécurité : on veut tracer les tentatives échouées.
 *
 * <p><b>Différence avec {@link AuditEventListener}</b> : ce dernier écoute
 * {@link jo.accountant.core.audit.AuditableAction} (mutations métier) en AFTER_COMMIT — correct
 * pour les mutations (sinon on tracerait des créations d'entités qui n'existent plus après
 * rollback). {@code SecurityAuditEventListener} écoute {@link SecurityAuditEvent} directement
 * (pas via AuditableAction) en AFTER_COMPLETION — pour tracer les tentatives.
 */
@Component
public class SecurityAuditEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditEventListener.class);

    private final AuditLogRepository repository;

    public SecurityAuditEventListener(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Persiste l'événement de sécurité dans {@code audit_log} avec
     * {@code entity_type = 'SecurityEvent'}.
     *
     * <p>Se déclenche après COMPLETION (commit OU rollback) — nécessaire car beaucoup
     * d'événements de sécurité (LOGIN_FAILED, REFRESH_TOKEN_REUSED, ACCESS_DENIED) surviennent
     * dans des transactions qui roll back.
     *
     * <p>Best-effort : si la persistance échoue (DB down, contrainte violée), on log ERROR
     * avec le correlationId pour replay — on ne propage JAMAIS l'exception à l'appelant
     * (l'audit ne doit pas casser l'auth).
     */
    @Async("audit-async-executor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void onSecurityAuditEvent(SecurityAuditEvent event) {
        try {
            AuditLog row = new AuditLog();
            row.setId(UUID.randomUUID());
            row.setCompanyId(event.companyId());
            row.setActorUserId(event.actorUserId());
            // entityType normalisé pour requêtes forensiques ciblées :
            //   SELECT * FROM audit_log WHERE entity_type = 'SecurityEvent' AND occurred_at >= NOW() - INTERVAL '7 days';
            row.setEntityType("SecurityEvent");
            // entityId = userId quand applicable, sinon null (ex: LOGIN_FAILED sur email inexistant)
            row.setEntityId(event.actorUserId());
            // action = eventType (LOGIN_SUCCESS, LOGIN_FAILED, REFRESH_TOKEN_REUSED, etc.)
            row.setAction(event.eventType());
            // Pas d'oldValue pour les événements de sécurité
            row.setOldValueJson(null);
            // Audit v4.7 §6.3 — masquer les PII (email, fullName) avant persistance
            row.setNewValueJson(
                event.metadata() != null && !event.metadata().isEmpty()
                    ? jo.accountant.core.audit.PiiMasker.maskPiiInJson(
                        jo.accountant.core.json.JsonUtil.toJson(event.metadata()))
                    : null
            );
            row.setOccurredAt(event.occurredAt());
            row.setCorrelationId(event.correlationId());
            repository.save(row);
        } catch (Exception ex) {
            LOG.error("Failed to persist security audit event [type={}, correlationId={}, userId={}]",
                event.eventType(), event.correlationId(), event.actorUserId(), ex);
        }
    }
}

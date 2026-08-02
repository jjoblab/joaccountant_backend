package jo.accountant.audit;

import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listener asynchrone des {@link AuditEvent} (§3.6). Même pattern architectural réutilisé par
 * :notifications (§9) : publication asynchrone, consommation dans un module dédié qui ne dépend
 * de rien.
 *
 * <p>Écoute APRÈS commit pour qu'une transaction échouée NE pollue PAS le journal d'audit avec
 * des lignes fantômes. Si la persistance de l'audit échoue elle-même, la transaction métier
 * d'origine n'est PAS rollbackée (l'audit est de l'observabilité, pas de la cohérence) —
 * l'échec est loggé au niveau ERROR avec l'id de corrélation pour replay.
 *
 * <p><b>Audit v4.7 §5.1 FIX CRITIQUE</b> : la version originale appelait
 * {@code this.onAuditEvent(...)} directement depuis {@link #onAuditableAction(AuditableAction)},
 * ce qui court-circuitait le proxy Spring. Les annotations {@code @Async} et
 * {@code @TransactionalEventListener(AFTER_COMMIT)} étaient décoratives — l'audit était écrit
 * synchroniquement dans la même transaction que l'opération métier, et rollbackait avec elle
 * (l'inverse du contrat documenté).
 *
 * <p>Le fix consiste à <b>re-publier</b> l'événement via {@link ApplicationEventPublisher} au
 * lieu d'appeler la méthode directement. Le multicaster Spring invoquera alors
 * {@link #onAuditEvent(AuditEvent)} via le proxy, ce qui réactivera
 * {@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)}.
 */
@Component
public class AuditEventListener {

 private static final Logger LOG = LoggerFactory.getLogger(AuditEventListener.class);

 private final AuditLogRepository repository;
 private final ApplicationEventPublisher eventPublisher;

 public AuditEventListener(AuditLogRepository repository, ApplicationEventPublisher eventPublisher) {
 this.repository = repository;
 this.eventPublisher = eventPublisher;
 }

 @Async("audit-async-executor")
 @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
 public void onAuditEvent(AuditEvent event) {
 try {
 AuditLog row = new AuditLog();
 row.setId(UUID.randomUUID());
 row.setCompanyId(event.companyId());
 row.setActorUserId(event.actorUserId());
 row.setEntityType(event.entityType());
 row.setEntityId(event.entityId());
 row.setAction(event.action());
 // Audit v4.7 §6.3 — masquer les PII (email, fullName) dans oldValue/newValue JSON
 row.setOldValueJson(jo.accountant.core.audit.PiiMasker.maskPiiInJson(event.oldValueJson()));
 row.setNewValueJson(jo.accountant.core.audit.PiiMasker.maskPiiInJson(event.newValueJson()));
 row.setOccurredAt(event.occurredAt());
 row.setCorrelationId(event.correlationId());
 repository.save(row);
 } catch (Exception ex) {
 // Audit v4.7 §9 — échec silencieux devrait déclencher un compteur Micrometer
 // (à ajouter quand micrometer-registry-prometheus sera intégré).
 LOG.error("Failed to persist audit event [correlationId={}, entityId={}]",
 event.correlationId(), event.entityId(), ex);
 }
 }

 /**
 * Réception des markers {@link AuditableAction} émis par les services métier.
 *
 * <p><b>Audit v4.7 §5.1 Fix</b> : au lieu d'appeler {@code this.onAuditEvent(...)} (ce qui
 * bypassait le proxy Spring), on <b>re-publie</b> un {@link AuditEvent} via
 * {@link ApplicationEventPublisher}. Le multicaster Spring invoquera alors
 * {@link #onAuditEvent(AuditEvent)} via le proxy, réactivant ainsi
 * {@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)}.
 */
 @EventListener
 public void onAuditableAction(AuditableAction action) {
 AuditEvent event = action.toAuditEvent();
 eventPublisher.publishEvent(event);
 }
}

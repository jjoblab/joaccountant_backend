package jo.accountant.chartofaccounts.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque modification d'un compte (renommage, activation, désactivation,
 * changement de mapping fiscal, etc.).
 *
 * <p>Stocke l'ancienne et la nouvelle valeur au format JSON pour audit complet. La
 * modification d'un compte verrouillé est impossible (409 côté service) — cet événement
 * n'est donc publié QUE pour des comptes non verrouillés.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record AccountUpdatedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID accountId,
 String oldValueJson,
 String newValueJson,
 Instant occurredAt
) implements AuditableAction {

 public AccountUpdatedEvent(UUID companyId, UUID actorUserId, UUID accountId,
 String oldValueJson, String newValueJson) {
 this(companyId, actorUserId, accountId, oldValueJson, newValueJson, Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId,
 actorUserId,
 "Account",
 accountId,
 "UPDATE",
 oldValueJson,
 newValueJson,
 null
 );
 }
}

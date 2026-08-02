package jo.accountant.chartofaccounts.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.chartofaccounts.entity.Account;

/**
 * Événement publié à chaque création de compte dans le plan comptable.
 *
 * <p>Consommé asynchronement par {@code :audit-trail} (même pattern que les autres modules,
 * §3.6). Trace QUI a créé quel compte, QUAND, et avec quels paramètres — important pour
 * l'audit fiscal (la création d'un compte en cours d'exercice peut être un signal d'alerte).
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record AccountCreatedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID accountId,
 String code,
 String label,
 int level,
 Instant occurredAt
) implements AuditableAction {

 public AccountCreatedEvent(Account account, UUID actorUserId) {
 this(
 account.getCompanyId(),
 actorUserId,
 account.getId(),
 account.getCode(),
 account.getLabel(),
 account.getLevel(),
 Instant.now()
 );
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId,
 actorUserId,
 "Account",
 accountId,
 "CREATE",
 null,
 "{\"code\":\"" + code + "\",\"label\":\"" + label + "\",\"level\":" + level + "}",
 null
 );
 }
}

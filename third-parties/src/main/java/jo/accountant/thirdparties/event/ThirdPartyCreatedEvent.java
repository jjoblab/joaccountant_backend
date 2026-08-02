package jo.accountant.thirdparties.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.thirdparties.entity.ThirdParty;

/**
 * Événement publié à chaque création de tiers.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 
 *
 * @author jo@Dev


*/
public record ThirdPartyCreatedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID thirdPartyId,
 String type,
 String name,
 UUID dedicatedAccountId,
 Instant occurredAt
) implements AuditableAction {

 public ThirdPartyCreatedEvent(ThirdParty tp, UUID actorUserId) {
 this(
 tp.getCompanyId(),
 actorUserId,
 tp.getId(),
 tp.getType().name(),
 tp.getName(),
 tp.getDedicatedAccountId(),
 Instant.now()
 );
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId, actorUserId, "ThirdParty", thirdPartyId, "CREATE",
 null,
 "{\"type\":\"" + type + "\",\"name\":\"" + name
 + "\",\"dedicatedAccountId\":\"" + dedicatedAccountId + "\"}",
 null);
 }
}

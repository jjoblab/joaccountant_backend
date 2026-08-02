package jo.accountant.timebilling.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.timebilling.entity.Project;

/**
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */

public record ProjectCreatedEvent(
 UUID companyId, UUID actorUserId, UUID projectId, String code, String label,
 Instant occurredAt
) implements AuditableAction {
 public ProjectCreatedEvent(Project p, UUID actorUserId) {
 this(p.getCompanyId(), actorUserId, p.getId(), p.getCode(), p.getLabel(), Instant.now());
 }
 @Override public AuditEvent toAuditEvent() {
 return AuditEvent.of(companyId, actorUserId, "Project", projectId, "CREATE",
 null, "{\"code\":\"" + code + "\",\"label\":\"" + label + "\"}", null);
 }
}

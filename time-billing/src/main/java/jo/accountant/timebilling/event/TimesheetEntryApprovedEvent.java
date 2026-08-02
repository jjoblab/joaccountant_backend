package jo.accountant.timebilling.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.timebilling.entity.TimesheetEntry;

/**
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */

public record TimesheetEntryApprovedEvent(
 UUID companyId, UUID actorUserId, UUID entryId, UUID projectId, UUID resourceUserId,
 BigDecimal hours, Instant occurredAt
) implements AuditableAction {
 public TimesheetEntryApprovedEvent(TimesheetEntry e, UUID actorUserId) {
 this(e.getCompanyId(), actorUserId, e.getId(), e.getProjectId(), e.getResourceUserId(),
 e.getHours(), Instant.now());
 }
 @Override public AuditEvent toAuditEvent() {
 return AuditEvent.of(companyId, actorUserId, "TimesheetEntry", entryId, "APPROVE",
 null, "{\"projectId\":\"" + projectId + "\",\"hours\":" + hours + "}", null);
 }
}

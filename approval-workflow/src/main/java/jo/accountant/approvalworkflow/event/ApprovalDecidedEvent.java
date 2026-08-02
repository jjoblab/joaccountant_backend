package jo.accountant.approvalworkflow.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque décision sur une demande d'approbation
 * (APPROVED, REJECTED, CANCELLED).
 *
 * <p>Consommé asynchronement par {@code :audit-trail}. Le consommateur métier, 12, 14)
 * peut aussi s'abonner à cet événement pour réagir à la décision (par exemple, finaliser le
 * postage d'une écriture quand sa demande est APPROVED, ou la remettre à DRAFT si REJECTED).
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
public record ApprovalDecidedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID requestId,
 ApprovalStatus newStatus,
 UUID decidedBy,
 String comment,
 Instant occurredAt
) implements AuditableAction {

 public ApprovalDecidedEvent(ApprovalRequest request, UUID decidedBy) {
 this(
 request.getCompanyId(),
 decidedBy,
 request.getId(),
 request.getStatus(),
 decidedBy,
 request.getComment(),
 Instant.now()
 );
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId,
 actorUserId,
 "ApprovalRequest",
 requestId,
 "DECIDE_" + newStatus.name(),
 null,
 "{\"newStatus\":\"" + newStatus + "\",\"decidedBy\":\"" + decidedBy
 + "\",\"comment\":\"" + (comment == null ? "" : comment) + "\"}",
 null
 );
 }
}

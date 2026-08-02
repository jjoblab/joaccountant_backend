package jo.accountant.approvalworkflow.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque création de demande d'approbation.
 *
 * <p>Consommé asynchronement par {@code :audit-trail} (§3.6). Trace QUI a demandé QUOI,
 * POURQUOI (actionType + montant), et QUAND.
 *
 * <p>C'est aussi cet événement qui pourrait être consommé par {@code :notifications} (Phase 15)
 * pour notifier les approbateurs éligibles — en Phase 4, la notification est faite directement
 * par le service via {@link jo.accountant.core.port.NotificationChannelPort}.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record ApprovalRequestedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID requestId,
 ApprovalActionType actionType,
 String resourceType,
 UUID resourceId,
 java.math.BigDecimal amount,
 Instant occurredAt
) implements AuditableAction {

 public ApprovalRequestedEvent(ApprovalRequest request, UUID actorUserId) {
 this(
 request.getCompanyId(),
 actorUserId,
 request.getId(),
 request.getActionType(),
 request.getResourceType(),
 request.getResourceId(),
 request.getAmount(),
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
 "REQUEST",
 null,
 "{\"actionType\":\"" + actionType + "\",\"resourceType\":\"" + resourceType
 + "\",\"resourceId\":\"" + resourceId + "\",\"amount\":" + amount + "}",
 null
 );
 }
}

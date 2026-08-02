package jo.accountant.financialstatements.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.financialstatements.entity.FinancialStatementSnapshot;

/**
 * Événement publié à chaque création de snapshot figé.
 *
 * <p>Trace quel état a été figé, pour quelle période, par qui — important pour l'audit
 * fiscal (les snapshots figés sont la base des états financiers officiels transmis aux
 * administrations).
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record FinancialStatementSnapshotCreatedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID snapshotId,
 String type,
 UUID periodId,
 Instant occurredAt
) implements AuditableAction {

 public FinancialStatementSnapshotCreatedEvent(FinancialStatementSnapshot snapshot, UUID actorUserId) {
 this(
 snapshot.getCompanyId(),
 actorUserId,
 snapshot.getId(),
 snapshot.getType().name(),
 snapshot.getPeriodId(),
 Instant.now()
 );
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId, actorUserId, "FinancialStatementSnapshot", snapshotId, "CREATE",
 null,
 "{\"type\":\"" + type + "\",\"periodId\":\"" + periodId + "\"}",
 null);
 }
}

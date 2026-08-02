package jo.accountant.accountingengine.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque contre-passation d'écriture.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record JournalEntryReversedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID originalEntryId,
 UUID reversalEntryId,
 Instant occurredAt
) implements AuditableAction {

 public JournalEntryReversedEvent(JournalEntry original, JournalEntry reversal, UUID actorUserId) {
 this(original.getCompanyId(), actorUserId, original.getId(), reversal.getId(), Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId, actorUserId, "JournalEntry", originalEntryId, "REVERSE",
 "{\"reference\":\"" + originalEntryId + "\"}",
 "{\"reversalEntryId\":\"" + reversalEntryId + "\"}",
 null);
 }
}

package jo.accountant.accountingengine.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque postage effectif d'une écriture (transition vers POSTED).
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>consommé</b>
 * par {@code jo.accountant.notifications.service.ForensicEventListener} qui logge une trace
 * forensique à des fins d'investigation post-incident. Il reste <b>prêt pour consommation
 * future</b> par d'autres abonnés (clôture automatique, rapprochements, alertes anomalie)
 * — ces consommateurs seront ajoutés quand le besoin métier se matérialisera.
 */
public record JournalEntryPostedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID entryId,
 String reference,
 BigDecimal amount,
 Instant occurredAt
) implements AuditableAction {

 public JournalEntryPostedEvent(JournalEntry entry, BigDecimal amount) {
 this(entry.getCompanyId(),
 jo.accountant.core.tenant.TenantContext.getUserId(),
 entry.getId(),
 entry.getReference(),
 amount,
 Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId, actorUserId, "JournalEntry", entryId, "POST",
 null,
 "{\"reference\":\"" + reference + "\",\"amount\":" + amount + "}",
 null);
 }
}

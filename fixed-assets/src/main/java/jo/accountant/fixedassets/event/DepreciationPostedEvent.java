package jo.accountant.fixedassets.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.fixedassets.entity.DepreciationScheduleLine;

/**
 * Événement publié à chaque postage d'une ligne d'amortissement.
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */
public record DepreciationPostedEvent(
    UUID companyId,
    UUID actorUserId,
    UUID assetId,
    UUID scheduleLineId,
    UUID journalEntryId,
    java.math.BigDecimal amount,
    Instant occurredAt
) implements AuditableAction {

    public DepreciationPostedEvent(DepreciationScheduleLine line, UUID actorUserId) {
        this(line.getCompanyId(), actorUserId, line.getAssetId(), line.getId(),
             line.getJournalEntryId(), line.getAmount(), Instant.now());
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId, actorUserId, "DepreciationScheduleLine", scheduleLineId, "POST",
            null,
            "{\"assetId\":\"" + assetId + "\",\"journalEntryId\":\"" + journalEntryId
                + "\",\"amount\":" + amount + "}",
            null);
    }
}

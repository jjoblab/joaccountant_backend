package jo.accountant.thirdparties.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.thirdparties.entity.LettrageMatch;

/**
 * Événement publié à chaque lettrage (manuel ou automatique).
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */
public record LettrageCreatedEvent(
    UUID companyId,
    UUID actorUserId,
    UUID lettrageId,
    UUID thirdPartyId,
    String matchCode,
    String status,
    Instant occurredAt
) implements AuditableAction {

    public LettrageCreatedEvent(LettrageMatch lm, UUID actorUserId) {
        this(
            lm.getCompanyId(),
            actorUserId,
            lm.getId(),
            lm.getThirdPartyId(),
            lm.getMatchCode(),
            lm.getStatus().name(),
            Instant.now()
        );
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId, actorUserId, "LettrageMatch", lettrageId, "CREATE",
            null,
            "{\"thirdPartyId\":\"" + thirdPartyId + "\",\"matchCode\":\"" + matchCode
                + "\",\"status\":\"" + status + "\"}",
            null);
    }
}

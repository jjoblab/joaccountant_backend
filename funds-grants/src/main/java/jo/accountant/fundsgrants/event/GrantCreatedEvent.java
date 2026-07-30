package jo.accountant.fundsgrants.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.fundsgrants.entity.Grant;

/**
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */

public record GrantCreatedEvent(
    UUID companyId, UUID actorUserId, UUID grantId, String code, String label,
    Instant occurredAt
) implements AuditableAction {
    public GrantCreatedEvent(Grant g, UUID actorUserId) {
        this(g.getCompanyId(), actorUserId, g.getId(), g.getCode(), g.getLabel(), Instant.now());
    }
    @Override public AuditEvent toAuditEvent() {
        return AuditEvent.of(companyId, actorUserId, "Grant", grantId, "CREATE",
            null, "{\"code\":\"" + code + "\",\"label\":\"" + label + "\"}", null);
    }
}

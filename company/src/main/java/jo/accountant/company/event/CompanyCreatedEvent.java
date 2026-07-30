package jo.accountant.company.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */

public record CompanyCreatedEvent(UUID companyId, UUID creatorUserId, Instant occurredAt)
    implements AuditableAction {

    public CompanyCreatedEvent(jo.accountant.company.entity.Company company, UUID creatorUserId) {
        this(company.getId(), creatorUserId, Instant.now());
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId,
            creatorUserId,
            "Company",
            companyId,
            "CREATE",
            null,
            "{\"createdBy\":\"" + creatorUserId + "\"}",
            null
        );
    }
}

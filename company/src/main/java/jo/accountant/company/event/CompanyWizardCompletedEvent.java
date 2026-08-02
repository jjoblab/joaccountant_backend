package jo.accountant.company.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */

public record CompanyWizardCompletedEvent(UUID companyId, UUID userId, Instant occurredAt)
 implements AuditableAction {

 public CompanyWizardCompletedEvent(jo.accountant.company.entity.Company company, UUID userId) {
 this(company.getId(), userId, Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId,
 userId,
 "Company",
 companyId,
 "WIZARD_COMPLETED",
 null,
 "{\"wizardCompleted\":true}",
 null
 );
 }
}

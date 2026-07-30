package jo.accountant.approvalworkflow.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalRule;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque création de règle d'approbation.
 *
 * <p>Trace le seuil configuré — un changement de seuil en cours d'exercice peut être un
 * signal d'alerte (tentative de contournement du contrôle interne).
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */
public record ApprovalRuleCreatedEvent(
    UUID companyId,
    UUID actorUserId,
    UUID ruleId,
    String actionType,
    java.math.BigDecimal thresholdAmount,
    Instant occurredAt
) implements AuditableAction {

    public ApprovalRuleCreatedEvent(ApprovalRule rule, UUID actorUserId) {
        this(
            rule.getCompanyId(),
            actorUserId,
            rule.getId(),
            rule.getActionType().name(),
            rule.getThresholdAmount(),
            Instant.now()
        );
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId,
            actorUserId,
            "ApprovalRule",
            ruleId,
            "CREATE",
            null,
            "{\"actionType\":\"" + actionType + "\",\"thresholdAmount\":" + thresholdAmount + "}",
            null
        );
    }
}

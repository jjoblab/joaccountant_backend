package jo.accountant.chartofaccounts.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié quand le plan comptable d'une entreprise est initialisé
 * (génération des niveaux 1 et 2 verrouillés à partir du référentiel).
 *
 * <p>Trace le référentiel utilisé et le nombre de comptes générés. Une réinitialisation
 * serait suspecte en production — cet événement permet de la détecter.
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */
public record ChartOfAccountsInitializedEvent(
    UUID companyId,
    UUID actorUserId,
    UUID accountingFrameworkId,
    int accountsCreated,
    Instant occurredAt
) implements AuditableAction {

    public ChartOfAccountsInitializedEvent(UUID companyId, UUID actorUserId,
                                           UUID accountingFrameworkId, int accountsCreated) {
        this(companyId, actorUserId, accountingFrameworkId, accountsCreated, Instant.now());
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId,
            actorUserId,
            "ChartOfAccounts",
            null,
            "INITIALIZE",
            null,
            "{\"accountingFrameworkId\":\"" + accountingFrameworkId
                + "\",\"accountsCreated\":" + accountsCreated + "}",
            null
        );
    }
}

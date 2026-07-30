package jo.accountant.company.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié à chaque mise à jour des champs légaux d'une Company
 * (siret, vatNumber, nif, address) via {@code PATCH /api/v1/companies/{companyId}/legal}.
 *
 * <p>Phase D — Audit v4.7 §4.2 : ces champs sont requis pour les mentions légales des factures
 * (CGI art. 289) et le Factur-X. Toute modification doit être tracée dans l'audit-trail pour
 * conformité réglementaire.
 *
 * <p>Stocke l'ancienne et la nouvelle valeur au format JSON pour audit complet. La PII est
 * masquée par {@link jo.accountant.core.audit.PiiMasker} au moment de la persistance
 * (cf. {@code AuditEventListener}).
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@code AuditEventListener} qui écoute l'interface
 * {@code AuditableAction}). Les consommateurs métier (notifications, workflows, exports
 * réglementaires, KPI temps-réel) seront câblés quand le besoin se matérialisera.
 */
public record CompanyLegalFieldsUpdatedEvent(
    UUID companyId,
    UUID actorUserId,
    String oldValueJson,
    String newValueJson,
    Instant occurredAt
) implements AuditableAction {

    public CompanyLegalFieldsUpdatedEvent(UUID companyId, UUID actorUserId,
                                          String oldValueJson, String newValueJson) {
        this(companyId, actorUserId, oldValueJson, newValueJson, Instant.now());
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId,
            actorUserId,
            "Company",
            companyId,
            "LEGAL_FIELDS_UPDATED",
            oldValueJson,
            newValueJson,
            null
        );
    }
}

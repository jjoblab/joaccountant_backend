package jo.accountant.fixedassets.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.fixedassets.entity.Asset;

/**
 * Événement publié à chaque création d'immobilisation.
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */
public record AssetCreatedEvent(
    UUID companyId,
    UUID actorUserId,
    UUID assetId,
    String label,
    java.math.BigDecimal acquisitionCost,
    Instant occurredAt
) implements AuditableAction {

    public AssetCreatedEvent(Asset asset, UUID actorUserId) {
        this(asset.getCompanyId(), actorUserId, asset.getId(), asset.getLabel(),
             asset.getAcquisitionCost(), Instant.now());
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId, actorUserId, "Asset", assetId, "CREATE",
            null,
            "{\"label\":\"" + label + "\",\"acquisitionCost\":" + acquisitionCost + "}",
            null);
    }
}

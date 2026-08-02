package jo.accountant.fixedassets.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.fixedassets.entity.Asset;

/**
 * Événement publié à chaque cession d'immobilisation.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>consommé</b>
 * par {@code jo.accountant.notifications.service.ForensicEventListener} qui logge une trace
 * forensique à des fins d'investigation post-incident. Il reste <b>prêt pour consommation
 * future</b> par d'autres abonnés (mise à jour du registre des immobilisations, calcul IS,
 * alertes plus-value anormale) — ces consommateurs seront ajoutés quand le besoin métier se
 * matérialisera.
 */
public record AssetDisposedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID assetId,
 BigDecimal disposalAmount,
 BigDecimal gainOrLoss,
 Instant occurredAt
) implements AuditableAction {

 public AssetDisposedEvent(Asset asset, UUID actorUserId) {
 this(asset.getCompanyId(), actorUserId, asset.getId(),
 asset.getDisposalAmount(), asset.getGainOrLoss(), Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId, actorUserId, "Asset", assetId, "DISPOSE",
 null,
 "{\"disposalAmount\":" + disposalAmount + ",\"gainOrLoss\":" + gainOrLoss + "}",
 null);
 }
}

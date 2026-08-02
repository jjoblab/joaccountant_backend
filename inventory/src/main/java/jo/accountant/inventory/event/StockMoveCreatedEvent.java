package jo.accountant.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.inventory.entity.StockMove;

/**
 * Événement publié à chaque mouvement de stock.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 
 *
 * @author jo@Dev


*/
public record StockMoveCreatedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID stockMoveId,
 UUID itemId,
 String direction,
 BigDecimal quantity,
 BigDecimal totalCost,
 Instant occurredAt
) implements AuditableAction {

 public StockMoveCreatedEvent(StockMove move, UUID actorUserId) {
 this(move.getCompanyId(), actorUserId, move.getId(), move.getItemId(),
 move.getDirection().name(), move.getQuantity(), move.getTotalCost(),
 Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId, actorUserId, "StockMove", stockMoveId, "CREATE",
 null,
 "{\"itemId\":\"" + itemId + "\",\"direction\":\"" + direction
 + "\",\"quantity\":" + quantity + ",\"totalCost\":" + totalCost + "}",
 null);
 }
}

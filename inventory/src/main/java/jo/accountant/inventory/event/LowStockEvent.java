package jo.accountant.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Événement publié quand le stock d'un article passe sous son seuil de réapprovisionnement
 * (§13 Phase 9, §9 notifications).
 *
 * <p>Cet événement sera consommé par :notifications (Phase 15) pour émettre une alerte.
 * En Phase 9, :inventory ne dépend pas de :notifications — il se contente de publier
 * l'événement via {@link org.springframework.context.ApplicationEventPublisher}.
 *
 * <p><b>Finding #1 (audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. Finding #1 audit batch 1.
 */
public record LowStockEvent(
    UUID companyId,
    UUID itemId,
    String sku,
    String label,
    BigDecimal currentStock,
    BigDecimal reorderThreshold,
    Instant occurredAt
) implements AuditableAction {

    public LowStockEvent(UUID companyId, UUID itemId, String sku, String label,
                         BigDecimal currentStock, BigDecimal reorderThreshold) {
        this(companyId, itemId, sku, label, currentStock, reorderThreshold, Instant.now());
    }

    @Override
    public AuditEvent toAuditEvent() {
        return AuditEvent.of(
            companyId, null, "Item", itemId, "LOW_STOCK",
            null,
            "{\"sku\":\"" + sku + "\",\"currentStock\":" + currentStock
                + ",\"reorderThreshold\":" + reorderThreshold + "}",
            null);
    }
}

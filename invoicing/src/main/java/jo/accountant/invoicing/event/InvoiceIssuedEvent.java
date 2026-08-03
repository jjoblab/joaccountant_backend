package jo.accountant.invoicing.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.invoicing.entity.Invoice;

/**
 * Événement publié à chaque émission effective d'une facture (transition vers ISSUED).
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>consommé</b>
 * par {@code jo.accountant.notifications.service.ForensicEventListener} qui logge une trace
 * forensique à des fins d'investigation post-incident. Il reste <b>prêt pour consommation
 * future</b> par d'autres abonnés (workflow de recouvrement, exports réglementaires, KPI
 * temps-réel) — ces consommateurs seront ajoutés quand le besoin métier se matérialisera.
 
 *
 * @author jo@Dev


*/
public record InvoiceIssuedEvent(
 UUID companyId, UUID actorUserId, UUID invoiceId, String invoiceNumber,
 BigDecimal totalAmount, Instant occurredAt
) implements AuditableAction {
 public InvoiceIssuedEvent(Invoice inv, UUID actorUserId) {
 this(inv.getCompanyId(), actorUserId, inv.getId(), inv.getInvoiceNumber(),
 inv.getTotalAmount(), Instant.now());
 }
 @Override public AuditEvent toAuditEvent() {
 return AuditEvent.of(companyId, actorUserId, "Invoice", invoiceId, "ISSUE",
 null, "{\"invoiceNumber\":\"" + invoiceNumber + "\",\"totalAmount\":" + totalAmount + "}", null);
 }
}

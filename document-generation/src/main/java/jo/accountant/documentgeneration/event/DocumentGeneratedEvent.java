package jo.accountant.documentgeneration.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.documentgeneration.entity.GeneratedDocument;

/**
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 
 *
 * @author jo@Dev


*/

public record DocumentGeneratedEvent(
 UUID companyId, UUID actorUserId, UUID documentId, UUID resourceId,
 String documentType, String storageKey, Instant occurredAt
) implements AuditableAction {
 public DocumentGeneratedEvent(GeneratedDocument doc, UUID actorUserId) {
 this(doc.getCompanyId(), actorUserId, doc.getId(), doc.getResourceId(),
 doc.getDocumentType().name(), doc.getStorageKey(), Instant.now());
 }
 @Override public AuditEvent toAuditEvent() {
 return AuditEvent.of(companyId, actorUserId, "GeneratedDocument", documentId, "GENERATE",
 null, "{\"resourceId\":\"" + resourceId + "\",\"type\":\"" + documentType
 + "\",\"storageKey\":\"" + storageKey + "\"}", null);
 }
}

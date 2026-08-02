package jo.accountant.documentnumbering.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.documentnumbering.entity.DocumentSequenceConfig;

/**
 * Événement publié à chaque création ou mise à jour d'une configuration de séquence.
 *
 * <p>Consommé asynchronement par {@code :audit-trail} (même pattern que {@code UserRegisteredEvent}
 * en. Permet de tracer QUI a modifié une séquence et QUAND — important pour l'audit
 * fiscal (un changement de prefix en cours d'année pourrait être suspect).
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
public record SequenceConfigCreatedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID configId,
 String documentType,
 String scopeKey,
 String prefix,
 Instant occurredAt
) implements AuditableAction {

 public SequenceConfigCreatedEvent(DocumentSequenceConfig config, UUID actorUserId) {
 this(
 config.getCompanyId(),
 actorUserId,
 config.getId(),
 config.getDocumentType().name(),
 config.getScopeKey(),
 config.getPrefix(),
 Instant.now()
 );
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId,
 actorUserId,
 "DocumentSequenceConfig",
 configId,
 "CREATE",
 null,
 "{\"documentType\":\"" + documentType + "\",\"scopeKey\":\"" + scopeKey
 + "\",\"prefix\":\"" + prefix + "\"}",
 null
 );
 }
}

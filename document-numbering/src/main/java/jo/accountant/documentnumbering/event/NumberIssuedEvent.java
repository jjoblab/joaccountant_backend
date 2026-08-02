package jo.accountant.documentnumbering.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.documentnumbering.entity.DocumentType;

/**
 * Événement publié à chaque émission effective d'un numéro documentaire.
 *
 * <p>Contrairement à {@link SequenceConfigCreatedEvent} (qui trace la configuration), cet
 * événement trace chaque numéro consommé — il sera très volumineux en production (un par
 * écriture, facture, reçu), mais c'est précisément ce que les administrations fiscales
 * attendent d'un journal d'audit de numérotation.
 *
 * <p>Le {@code issuedAt} correspond au moment exact de l'incrémentation du compteur (pas au
 * moment de la publication de l'événement, qui est asynchrone).
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via <code>AuditEventListener</code> qui écoute
 * l'interface <code>AuditableAction</code>). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record NumberIssuedEvent(
 UUID companyId,
 UUID actorUserId,
 UUID configId,
 DocumentType documentType,
 String scopeKey,
 String periodKey,
 String number,
 long value,
 Instant issuedAt
) implements AuditableAction {

 public NumberIssuedEvent(UUID companyId, UUID actorUserId, UUID configId,
 DocumentType documentType, String scopeKey, String periodKey,
 String number, long value, Instant issuedAt) {
 this.companyId = companyId;
 this.actorUserId = actorUserId;
 this.configId = configId;
 this.documentType = documentType;
 this.scopeKey = scopeKey;
 this.periodKey = periodKey;
 this.number = number;
 this.value = value;
 this.issuedAt = issuedAt;
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 companyId,
 actorUserId,
 "DocumentNumber",
 null, // pas d'entité persistante dédiée au numéro émis
 "ISSUE",
 null,
 "{\"documentType\":\"" + documentType + "\",\"scopeKey\":\"" + scopeKey
 + "\",\"number\":\"" + number + "\",\"value\":" + value
 + ",\"periodKey\":\"" + periodKey + "\"}",
 null
 );
 }
}

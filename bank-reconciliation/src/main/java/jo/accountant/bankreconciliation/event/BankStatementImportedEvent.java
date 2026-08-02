package jo.accountant.bankreconciliation.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;
import jo.accountant.bankreconciliation.entity.BankStatementImport;

/**
 * Événement publié à chaque import d'un relevé bancaire.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>consommé</b>
 * par {@code jo.accountant.notifications.service.ForensicEventListener} qui logge une trace
 * forensique à des fins d'investigation post-incident. Il reste <b>prêt pour consommation
 * future</b> par d'autres abonnés (auto-rapprochement, détection de fraude, alertes solde)
 * — ces consommateurs seront ajoutés quand le besoin métier se matérialisera.
 */
public record BankStatementImportedEvent(
 UUID companyId, UUID actorUserId, UUID importId, UUID bankAccountId,
 int lineCount, Instant occurredAt
) implements AuditableAction {
 public BankStatementImportedEvent(BankStatementImport imp, UUID actorUserId) {
 this(imp.getCompanyId(), actorUserId, imp.getId(), imp.getBankAccountId(),
 imp.getLineCount(), Instant.now());
 }
 @Override public AuditEvent toAuditEvent() {
 return AuditEvent.of(companyId, actorUserId, "BankStatementImport", importId, "IMPORT",
 null, "{\"bankAccountId\":\"" + bankAccountId + "\",\"lineCount\":" + lineCount + "}", null);
 }
}

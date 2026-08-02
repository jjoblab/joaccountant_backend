package jo.accountant.auth.event;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.audit.AuditEvent;
import jo.accountant.core.audit.AuditableAction;

/**
 * Publié quand un nouvel utilisateur s'enregistre. Consommé par :audit-trail.
 *
 * <p><b>(audit batch 1) — Events de domaine</b> : cet événement est <b>prêt pour
 * consommation future</b> — il est publié mais n'a pas encore d'abonné métier explicite. La
 * trace est conservée dans l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}
 * qui écoute l'interface {@code AuditableAction}). Les consommateurs métier (notifications,
 * workflows, exports réglementaires, KPI temps-réel) seront câblés quand le besoin se
 * matérialisera — cf. audit batch 1.
 */
public record UserRegisteredEvent(UUID userId, String email, String fullName, Instant occurredAt)
 implements AuditableAction {

 public UserRegisteredEvent(jo.accountant.auth.entity.User user) {
 this(user.getId(), user.getEmail(), user.getFullName(), Instant.now());
 }

 @Override
 public AuditEvent toAuditEvent() {
 return AuditEvent.of(
 null, // événement système — pas de tenant
 userId, // acteur = self
 "User",
 userId,
 "REGISTER",
 null,
 "{\"email\":\"" + email + "\",\"fullName\":\"" + fullName + "\"}",
 null
 );
 }
}

package jo.accountant.core.audit;

/**
 * Interface marqueur implémentée par tout événement de module qui doit être audité. Les modules
 * publient leurs propres classes d'événement concrètes (par ex. {@code CompanyCreatedEvent}) qui
 * implémentent cette interface ; le listener d'audit dans :audit-trail les intercepte
 * génériquement.
 *
 * <p>§3.6 : cette interface est consommée par TOUS les modules sans duplication.
 */
public interface AuditableAction {
    AuditEvent toAuditEvent();
}

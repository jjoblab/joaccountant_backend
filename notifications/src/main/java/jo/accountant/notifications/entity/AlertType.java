package jo.accountant.notifications.entity;

/**
 * Type d'alerte configurable par entreprise (§9).
 
 *
 * @author jo@Dev


*/
public enum AlertType {
    INVOICE_OVERDUE,
    FISCAL_PERIOD_PAST_DUE,
    GRANT_THRESHOLD_REACHED,
    LOW_STOCK,
    APPROVAL_PENDING
}

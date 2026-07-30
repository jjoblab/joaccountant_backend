package jo.accountant.accountingengine.entity;

/**
 * Statut d'une période fiscale (§13 Phase 5).
 *
 * <ul>
 *   <li>{@link #OPEN} — écritures possibles</li>
 *   <li>{@link #LOCKED} — période verrouillée, aucune nouvelle écriture ni modification</li>
 * </ul>
 *
 * <p>Contrairement à {@link FiscalYearStatus} qui a 3 états (OPEN/LOCKED/CLOSED), la période
 * n'a pas d'état CLOSED distinct — quand l'exercice est CLOSED, toutes ses périodes sont
 * implicitement fermées.
 */
public enum FiscalPeriodStatus {
    OPEN,
    LOCKED
}

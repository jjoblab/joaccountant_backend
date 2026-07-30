package jo.accountant.timebilling.entity;

/**
 * Type de facturation d'un projet (§13 Phase 10).
 *
 * <ul>
 *   <li>{@link #FIXED_FEE} — forfait. Le prix est convenu à l'avance, indépendamment du
 *       temps passé. Le WIP est suivi pour information mais ne déclenche pas de facturation
 *       automatique.</li>
 *   <li>{@link #TIME_AND_MATERIALS} — régie. Le client est facturé sur la base du temps
 *       passé × taux horaire. Le WIP est directement facturable.</li>
 * </ul>
 */
public enum BillingType {
    FIXED_FEE,
    TIME_AND_MATERIALS
}

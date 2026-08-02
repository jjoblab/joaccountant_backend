package jo.accountant.timebilling.entity;

/**
 * Statut d'un projet (§13.
 *
 * <ul>
 * <li>{@link #ACTIVE} — projet en cours, saisie de temps possible</li>
 * <li>{@link #CLOSED} — projet clôturé, plus de saisie possible</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum ProjectStatus {
    ACTIVE,
    CLOSED
}

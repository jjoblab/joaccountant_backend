package jo.accountant.accountingengine.entity;

/**
 * Statut d'un exercice fiscal (§13.
 *
 * <ul>
 * <li>{@link #OPEN} — exercice en cours, écritures possibles sur ses périodes</li>
 * <li>{@link #LOCKED} — exercice verrouillé (en cours de clôture), aucune nouvelle écriture</li>
 * <li>{@link #CLOSED} — exercice clôturé, définitivement figé</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum FiscalYearStatus {
    OPEN,
    LOCKED,
    CLOSED
}

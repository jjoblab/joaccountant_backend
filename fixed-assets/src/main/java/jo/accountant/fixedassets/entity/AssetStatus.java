package jo.accountant.fixedassets.entity;

/**
 * Statut d'une immobilisation (§13.
 *
 * <ul>
 * <li>{@link #ACTIVE} — actif en service, amortissable période par période</li>
 * <li>{@link #DISPOSED} — cédé. Immuable — ne peut plus être amortie ni cédée à nouveau.</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum AssetStatus {
    ACTIVE,
    DISPOSED
}

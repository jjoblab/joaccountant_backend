package jo.accountant.thirdparties.entity;

/**
 * Statut d'un lettrage (§13.
 *
 * <ul>
 * <li>{@link #PARTIAL} — lettrage partiel : les lignes lettrées ne s'équilibrent pas
 * totalement (ex. facture de 1000 lettrée avec un règlement de 800 — il reste 200
 * non lettrés sur la facture, ou inversement).</li>
 * <li>{@link #FULL} — lettrage complet : les lignes lettrées s'équilibrent exactement
 * (somme des débits = somme des crédits). Le solde lettré est nul.</li>
 * <li>{@link #DELETED} — lettrage supprimé (dé-lettrage).Finding MOYENNE —
 * soft delete pour préserver la piste d'audit. Les lettrages DELETED sont exclus des
 * requêtes de relevé/balance âgée mais restent consultables pour forensique.</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum LettrageStatus {
    PARTIAL,
    FULL,
    DELETED //— soft delete pour audit trail
}

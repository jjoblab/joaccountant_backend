package jo.accountant.thirdparties.entity;

/**
 * Statut d'un lettrage (§13 Phase 7).
 *
 * <ul>
 *   <li>{@link #PARTIAL} — lettrage partiel : les lignes lettrées ne s'équilibrent pas
 *       totalement (ex. facture de 1000 lettrée avec un règlement de 800 — il reste 200
 *       non lettrés sur la facture, ou inversement).</li>
 *   <li>{@link #FULL} — lettrage complet : les lignes lettrées s'équilibrent exactement
 *       (somme des débits = somme des crédits). Le solde lettré est nul.</li>
 *   <li>{@link #DELETED} — lettrage supprimé (dé-lettrage). Audit v4.7 §3.2 Finding MOYENNE —
 *       soft delete pour préserver la piste d'audit. Les lettrages DELETED sont exclus des
 *       requêtes de relevé/balance âgée mais restent consultables pour forensique.</li>
 * </ul>
 */
public enum LettrageStatus {
    PARTIAL,
    FULL,
    DELETED  // Audit v4.7 §3.2 — soft delete pour audit trail
}

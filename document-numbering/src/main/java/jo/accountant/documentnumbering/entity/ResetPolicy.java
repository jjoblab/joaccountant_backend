package jo.accountant.documentnumbering.entity;

/**
 * Politique de réinitialisation du compteur d'une séquence documentaire (§6).
 *
 * <ul>
 *   <li>{@link #NEVER} — le compteur n'est jamais réinitialisé. La séquence est monotone
 *       sur toute la durée de vie de l'entreprise. {@code periodKey} reste vide.</li>
 *   <li>{@link #YEARLY} — le compteur repart à 1 au 1er janvier de chaque année civile.
 *       {@code periodKey} = année sur 4 chiffres (ex. {@code "2026"}).</li>
 *   <li>{@link #MONTHLY} — le compteur repart à 1 au 1er de chaque mois.
 *       {@code periodKey} = {@code "YYYY-MM"} (ex. {@code "2026-07"}).</li>
 * </ul>
 *
 * <p>Le choix de la politique dépend du type de document et du référentiel fiscal applicable :
 * la numérotation des factures est généralement annuelle en zone OHADA et Haïti, tandis que
 * certaines numérotations internes (écritures de journal) peuvent être non réinitialisables
 * pour faciliter les audits pluriannuels.
 */
public enum ResetPolicy {
    NEVER,
    YEARLY,
    MONTHLY
}

package jo.accountant.approvalworkflow.entity;

/**
 * Types d'actions financières soumises au workflow d'approbation (§7).
 *
 * <p>Cet enum est volontairement extensible : chaque nouveau module métier qui expose une
 * action financière soumise à seuil ajoute une valeur ici. Les valeurs présentes
 * correspondent aux consommateurs identifiés dans le prompt maître v2.1 :
 *
 * <ul>
 *   <li>{@link #JOURNAL_ENTRY_POST} — consommé par {@code accounting-engine} (Phase 5)
 *       au postage d'une écriture dont le montant total dépasse le seuil configuré</li>
 *   <li>{@link #INVOICE_ISSUE} — consommé par {@code invoicing} (Phase 12) à l'émission
 *       d'une facture dont le montant HT dépasse le seuil</li>
 *   <li>{@link #GRANT_DISBURSEMENT_PROPOSAL} — consommé par {@code funds-grants} (Phase 14)
 *       à la proposition d'écriture de fonds dédiés</li>
 * </ul>
 *
 * <p>Rappel (§7) : l'absence de règle {@link ApprovalRule} active pour un actionType donné
 * signifie qu'<strong>aucune approbation n'est requise</strong> — pas de blocage surprise
 * pour une petite structure.
 */
public enum ApprovalActionType {
    JOURNAL_ENTRY_POST,
    INVOICE_ISSUE,
    GRANT_DISBURSEMENT_PROPOSAL
}

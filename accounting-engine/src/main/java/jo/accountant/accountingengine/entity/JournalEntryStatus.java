package jo.accountant.accountingengine.entity;

/**
 * Statut d'une écriture comptable (§13 Phase 5).
 *
 * <ul>
 *   <li>{@link #DRAFT} — brouillon, modifiable. N'a pas encore de {@code reference} (numéro).</li>
 *   <li>{@link #PENDING_APPROVAL} — en attente d'approbation (règle des quatre yeux via
 *       {@code :approval-workflow}). Le {@code reference} n'est pas encore attribué — il le
 *       sera au moment de la transition vers {@link #POSTED}.</li>
 *   <li>{@link #POSTED} — définitivement postée. {@code reference} attribué via
 *       {@code :document-numbering}. <strong>Immuable</strong> — correction uniquement par
 *       contre-passation (voir {@link #VOIDED}).</li>
 *   <li>{@link #VOIDED} — écriture annulée par contre-passation. Conserve son numéro
 *       d'origine (règle de numérotation sans trou, §6). L'écriture de contre-passation
 *       est une nouvelle écriture POSTED liée via {@code reversalOfEntryId}.</li>
 * </ul>
 */
public enum JournalEntryStatus {
    DRAFT,
    PENDING_APPROVAL,
    POSTED,
    VOIDED
}

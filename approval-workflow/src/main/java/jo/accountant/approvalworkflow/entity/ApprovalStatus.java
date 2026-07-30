package jo.accountant.approvalworkflow.entity;

/**
 * Statut d'une demande d'approbation (§7).
 *
 * <ul>
 *   <li>{@link #PENDING} — demande créée, en attente de décision d'un approbateur éligible</li>
 *   <li>{@link #APPROVED} — demande approuvée par un approbateur (différent du demandeur,
 *       règle des quatre yeux). L'action cible peut alors être finalisée par le consommateur.</li>
 *   <li>{@link #REJECTED} — demande rejetée. L'action cible doit revenir à l'état
 *       {@code DRAFT} côté consommateur, avec motif horodaté et visible.</li>
 *   <li>{@link #CANCELLED} — demande annulée (typiquement par le demandeur lui-même avant
 *       décision). N'est ni une approbation ni un rejet — l'action cible revient aussi à
 *       {@code DRAFT} côté consommateur.</li>
 * </ul>
 *
 * <p>Une fois dans un état terminal (APPROVED, REJECTED, CANCELLED), une demande ne peut
 * plus changer de statut. Toute tentative de re-décision → 409.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}

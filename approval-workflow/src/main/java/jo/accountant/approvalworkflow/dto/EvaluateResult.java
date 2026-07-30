package jo.accountant.approvalworkflow.dto;

import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;

/**
 * Résultat de
 * {@link jo.accountant.approvalworkflow.service.ApprovalWorkflowService#evaluate}.
 *
 * <p>Deux cas possibles :
 * <ul>
 *   <li>{@code autoApproved = true} : aucune approbation requise (pas de règle active pour
 *       ce actionType, ou montant ≤ seuil). Le consommateur peut finaliser l'action
 *       directement. {@code requestId = null}.</li>
 *   <li>{@code autoApproved = false} : une {@code ApprovalRequest} PENDING a été créée.
 *       Le consommateur doit mettre l'action cible à l'état intermédiaire
 *       {@code PENDING_APPROVAL} et attendre la décision. {@code requestId} est l'ID de
 *       la demande — le consommateur doit le stocker pour pouvoir vérifier le statut plus
 *       tard.</li>
 * </ul>
 *
 * @param autoApproved true si l'action peut être finalisée sans approbation
 * @param requestId ID de la demande créée (null si autoApproved = true)
 * @param actionType type d'action évalué (pour traçabilité)
 */
public record EvaluateResult(boolean autoApproved, UUID requestId, ApprovalActionType actionType) {

    /** Factory pour le cas auto-approved. */
    public static EvaluateResult autoApproved(ApprovalActionType actionType) {
        return new EvaluateResult(true, null, actionType);
    }

    /** Factory pour le cas demande créée. */
    public static EvaluateResult pending(UUID requestId, ApprovalActionType actionType) {
        return new EvaluateResult(false, requestId, actionType);
    }
}

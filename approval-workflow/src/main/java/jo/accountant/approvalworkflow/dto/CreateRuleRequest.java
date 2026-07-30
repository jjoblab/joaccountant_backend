package jo.accountant.approvalworkflow.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;

/**
 * Corps de requête pour {@code POST .../approval-workflow/rules}.
 *
 * <p>Crée une règle active pour un actionType. 409 s'il existe déjà une règle active pour
 * le même actionType dans l'entreprise. Pour modifier une règle, désactiver l'ancienne
 * puis créer la nouvelle (avec audit).
 *
 * @param actionType type d'action soumise au seuil
 * @param thresholdAmount montant seuil en devise fonctionnelle (strictement supérieur déclenche)
 * @param requiredApproverRoles liste des rôles éligibles à l'approbation — au moins un
 * @param minApprovals nombre minimum d'approbations (Phase 4 : forcé à 1)
 */
public record CreateRuleRequest(
    @NotNull ApprovalActionType actionType,
    @NotNull @PositiveOrZero BigDecimal thresholdAmount,
    @NotEmpty List<String> requiredApproverRoles,
    Integer minApprovals
) {
    /** Constructeur canonique : minApprovals forcé à 1 en Phase 4 si &gt; 1 fourni. */
    public CreateRuleRequest {
        if (minApprovals == null || minApprovals < 1) {
            minApprovals = 1;
        }
    }
}

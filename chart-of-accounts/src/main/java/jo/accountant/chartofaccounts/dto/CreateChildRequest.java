package jo.accountant.chartofaccounts.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.core.framework.ReportingClass;

/**
 * Corps de requête pour {@code POST .../chart-of-accounts/{parentId}/children}.
 *
 * <p>Crée un compte enfant sous le parent donné. Le niveau est calculé automatiquement
 * (niveau du parent + 1) — impossible de créer un enfant qui violerait la règle "pas de
 * niveau &gt; 4".
 *
 * <p>Si {@code code} est omis, il est généré automatiquement (prochain code disponible dans
 * la séquence des enfants du parent). Si fourni, il doit respecter la longueur attendue pour
 * le niveau cible et être unique dans l'entreprise.
 *
 * @param code code du compte (optionnel — auto-généré si null)
 * @param label libellé du compte
 * @param reportingClass classification universelle (requis)
 * @param reportingSubcategory sous-catégorie universelle (nullable pour les comptes de regroupement)
 * @param normalBalance sens normal du solde (requis)
 * @param isCollective true si compte collectif (compte de regroupement de tiers)
 * @param taxMappingCode code de règle fiscale (Phase 16, optionnel)
 * @param requiresAnalyticalTagPlanIds liste d'IDs de plans analytiques obligatoires (Phase 5, optionnel)
 */
public record CreateChildRequest(
    @Size(max = 30) String code,
    @NotBlank @Size(max = 200) String label,
    @NotNull ReportingClass reportingClass,
    ReportingSubcategory reportingSubcategory,
    @NotNull NormalBalance normalBalance,
    Boolean isCollective,
    @Size(max = 30) String taxMappingCode,
    List<UUID> requiresAnalyticalTagPlanIds
) {
    /** Constructeur canonique avec valeurs par défaut. */
    public CreateChildRequest {
        if (isCollective == null) isCollective = false;
        if (requiresAnalyticalTagPlanIds == null) requiresAnalyticalTagPlanIds = List.of();
    }
}

package jo.accountant.chartofaccounts.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.core.framework.ReportingClass;

/**
 * Corps de requête pour {@code PATCH .../chart-of-accounts/{accountId}}.
 *
 * <p>Tous les champs sont optionnels : seuls les champs présents dans le corps sont mis à jour
 * (sémantique PATCH standard). Les champs non modifiables ({@code code}, {@code level},
 * {@code parentId}, {@code reportingClass}) ne sont jamais éditables via cet endpoint —
 * créer un nouveau compte plutôt que de muter la structure du plan.
 *
 * <p>Règles appliquées :
 * <ul>
 * <li>Compte {@code locked = true} → 409 sur toute modification.</li>
 * <li>Activation ({@code active = true}) → toujours permise.</li>
 * <li>Désactivation ({@code active = false}) → refusée si le compte a un solde non nul
 * (vérifié via {@link jo.accountant.chartofaccounts.guard.AccountBalanceGuard},
 * implémenté en.</li>
 * </ul>
 *
 * @param label nouveau libellé (optionnel)
 * @param reportingSubcategory nouvelle sous-catégorie (optionnel, nullable)
 * @param taxMappingCode nouveau code fiscal (optionnel, nullable)
 * @param active nouveau statut actif (optionnel)
 * @param requiresAnalyticalTagPlanIds nouvelle liste de plans analytiques obligatoires (optionnel)
 
 *
 * @author jo@Dev


*/
public record UpdateAccountRequest(
    @Size(max = 200) String label,
    ReportingSubcategory reportingSubcategory,
    @Size(max = 30) String taxMappingCode,
    Boolean active,
    List<UUID> requiresAnalyticalTagPlanIds
) {}

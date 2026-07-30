package jo.accountant.chartofaccounts.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.core.framework.ReportingClass;

/**
 * Corps de requête pour {@code POST .../chart-of-accounts/initialize}.
 *
 * <p>Initialise (ou réinitialise si demandé) le plan comptable d'une entreprise en générant
 * les niveaux 1 (classes) et 2 (rubriques) verrouillés à partir du référentiel choisi à la
 * création de l'entreprise (§13 Phase 3).
 *
 * <p>Pour les référentiels {@code MANDATED} (SYSCOHADA, PCG, PCN, PCGR) : les classes sont
 * issues du {@code mandatedClassSeed} du référentiel.
 * Pour les référentiels {@code FREE} (IFRS full, IFRS SMEs) : un gabarit
 * {@link jo.accountant.chartofaccounts.entity.AccountNumberingTemplate} doit être fourni
 * explicitement.
 *
 * <p>Restructuration 2026-07-24 (suite — plan comptable context-aware) : le paramètre
 * optionnel {@code businessTypeCode} déclenche la génération automatique de comptes
 * niveau 2+ typiques du secteur (ex. RETAIL_COMMERCE → 401 Fournisseurs, 411 Clients,
 * 521 Banque, 601 Achats de marchandises, 701 Ventes, etc. — voir
 * {@link jo.accountant.chartofaccounts.template.SectorAccountTemplate}). Si non fourni,
 * seul le niveau 1 est généré (comportement historique).
 *
 * @param accountingFrameworkId identifiant du référentiel (déjà choisi à la création de
 *        l'entreprise, mais passé explicitement pour valider la cohérence)
 * @param template gabarit de numérotation — requis uniquement pour {@code FREE},
 *        ignoré sinon (peut être {@code null})
 * @param businessTypeCode code du type métier (ex. RETAIL_COMMERCE) — optionnel, déclenche
 *        la génération de comptes niveau 2+ contextuels. Si null, seul le niveau 1 est créé.
 */
public record InitializeRequest(
    @NotNull UUID accountingFrameworkId,
    AccountNumberingTemplateDto template,
    String businessTypeCode
) {

    /**
     * Gabarit de numérotation pour référentiels {@code FREE}.
     */
    public record AccountNumberingTemplateDto(
        Integer codeLengthLevel1,
        Integer codeLengthLevel2,
        Integer codeLengthLevel3,
        Integer codeLengthLevel4,
        Integer spacingStep
    ) {}
}

package jo.accountant.company.dto;

import java.util.List;
import java.util.UUID;

/**
 * V8.2 — Résultat de l'activation atomique du wizard.
 *
 * <p>Retourné par POST /wizard/complete. Contient la company + résumé de tout ce qui a été créé
 * atomiquement (une seule transaction) :
 * <ul>
 *   <li>{@code activatedModules} — modules activés (always-on + sectoriels + customModules si CUSTOM)</li>
 *   <li>{@code chartOfAccountsCreated} — nombre de comptes créés (classes SYSCOHADA/PCG + seed sectoriel)</li>
 *   <li>{@code fiscalYearId} — id de l'exercice fiscal créé (12 périodes mensuelles auto-générées)</li>
 *   <li>{@code journalCodesCreated} — codes des journaux créés (VT, AC, BQ, CA, OD, PA, DP, FX)</li>
 *   <li>{@code sequencesCreated} — nombre de séquences de numérotation créées</li>
 *   <li>{@code taxRulesCreated} — nombre de règles TVA créées (0 si seeds globaux suffisent, ex: Haïti)</li>
 * </ul>
 *
 * <p><b>Idempotence</b> : si completeWizard est rappelé (suite à un retry), les compteurs
 * retournent les valeurs déjà existantes (pas de doublons créés).
 */
public record CompanyWizardResult(
    CompanyResponse company,
    List<String> activatedModules,
    int chartOfAccountsCreated,
    UUID fiscalYearId,
    List<String> journalCodesCreated,
    int sequencesCreated,
    int taxRulesCreated
) {}

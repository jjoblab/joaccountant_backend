package jo.accountant.company.dto;

import java.util.List;
import java.util.UUID;

/**
 * V8.2 — Résultat de l'activation atomique du wizard.
 *
 * <p>Retourné par POST /wizard/complete. Contient la company + résumé de tout ce qui a été créé.
 */
public record CompanyWizardResult(
    CompanyResponse company,
    List<String> activatedModules,
    int chartOfAccountsCreated,
    UUID fiscalYearId,
    List<String> journalCodesCreated,
    int sequencesCreated
) {}

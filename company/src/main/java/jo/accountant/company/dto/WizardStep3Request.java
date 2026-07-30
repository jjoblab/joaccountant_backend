package jo.accountant.company.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * V8.2 — Wizard étape 3 : Comptabilité & fiscalité (refondu).
 *
 * <p>Fusionne les anciennes étapes 6 (framework+fiscal), 9 (VAT mode), 10 (numbering).
 */
public record WizardStep3Request(
    @NotNull UUID accountingFrameworkId,
    @Min(1) @Max(12) int fiscalYearStartMonth,
    int fiscalYearStartYear,
    String fiscalYearLabel,
    @NotNull String vatMode,  // "DEBIT" ou "ENCAISSEMENT"
    Map<String, String> numberingPrefixes  // optionnel — défauts sectoriels
) {}

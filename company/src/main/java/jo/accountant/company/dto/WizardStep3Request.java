package jo.accountant.company.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.tax.VatMode;

/**
 * WizardComptabilité & fiscalité (refondu).
 *
 * <p>Fusionne les anciennes étapes 6 (framework+fiscal), 9 (VAT mode), 10 (numbering).
 *
 * <p>{@code vatMode} est typé {@link VatMode} (enum) plutôt que String pour la type-safety.
 * Les valeurs acceptées sont {@link VatMode#DEBIT} (régime des débits, défaut) et
 * {@link VatMode#ENCAISSEMENT} (régime des encaissements, art. 289 II CGI).
 
 *
 * @author jo@Dev


*/
public record WizardStep3Request(
    @NotNull UUID accountingFrameworkId,
    @Min(1) @Max(12) int fiscalYearStartMonth,
    int fiscalYearStartYear,
    String fiscalYearLabel,
    VatMode vatMode,
    Map<String, String> numberingPrefixes // optionnel — défauts sectoriels
) {
    public WizardStep3Request {
        if (vatMode == null) vatMode = VatMode.DEBIT;
    }
}

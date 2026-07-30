package jo.accountant.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Création d'une société (wizard étape 1 — restructuration 2026-07-24).
 *
 * <p><strong>Changement cassant</strong> (documenté dans {@code ENDPOINTS_CHANGELOG.md} et
 * {@code MOBILE_SYNC_2026-07-24_business-type-restructuring.md}) : les champs
 * {@code legalForm}, {@code sector}, {@code accountingFrameworkId} et
 * {@code fiscalYearStartMonth} sont <strong>retirés</strong> du payload de création. Ils
 * sont désormais saisis via les étapes correspondantes du wizard (étapes 2, 3 et 6
 * respectivement).
 *
 * <p>Seuls les champs strictement nécessaires pour instancier le tenant restent :
 * {@code name}, {@code country}, {@code functionalCurrency}.
 */
public record CreateCompanyRequest(
    @NotBlank String name,
    @NotBlank @Size(min = 2, max = 2) String country,
    @NotBlank @Size(min = 3, max = 3) String functionalCurrency
) {}

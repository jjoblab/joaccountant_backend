package jo.accountant.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Création d'une société (wizard étape 1 — ).
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
 *
 * <p><b>V2.6.0 (wizard refonte) — Extension optionnelle</b> : les champs
 * {@code organizationNature} et {@code legalForm} sont ré-introduits sous forme de
 * <strong>Strings nullables</strong> pour permettre au wizard step 1 de saisir ces
 * informations dès la création (au lieu de les laisser aux defaults provisoires
 * {@code FOR_PROFIT} / {@code OTHER}). Backward-compatible : si null, les defaults
 * historiques sont appliqués dans {@code CompanyService.createCompany}.
 *
 * <p>Typage en {@code String} (et non enum) pour tolérer les valeurs vides/nullables
 * et pour découpler le DTO wire du {@code Java enum} (les values possibles sont
 * documentées ci-dessous et validées côté service). Format wire identique au format
 * enum Jackson (le nom de l'enum) — donc rétro-compatible avec un client qui enverrait
 * un enum sérialisé.
 *
 * <ul>
 * <li>{@code organizationNature} ∈ {"FOR_PROFIT", "NON_PROFIT"} (V101 — domaine réduit).</li>
 * <li>{@code legalForm} ∈ {"SOLE_PROPRIETORSHIP","SARL","SA","SAS","NGO","ASSOCIATION","OTHER"}.</li>
 * </ul>
 */
public record CreateCompanyRequest(
 @NotBlank String name,
 @NotBlank @Size(min = 2, max = 2) String country,
 @NotBlank @Size(min = 3, max = 3) String functionalCurrency,
 /** Nullable — "FOR_PROFIT" ou "NON_PROFIT". Default "FOR_PROFIT" si null. */
 String organizationNature,
 /** Nullable — ex. "SARL", "SA", "NGO", "ASSOCIATION", "OTHER". Default "OTHER" si null. */
 String legalForm
) {}

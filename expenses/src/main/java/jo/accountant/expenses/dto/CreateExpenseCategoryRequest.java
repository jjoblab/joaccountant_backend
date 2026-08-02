package jo.accountant.expenses.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Corps de requête pour {@code POST /api/v1/companies/{companyId}/expenses/categories}
 * (CRUD endpoints).
 *
 * <p>Crée une nouvelle catégorie de note de frais pour l'entreprise, avec plafonds
 * journaliers/mensuels optionnels.
 *
 * @param code code court unique par entreprise (1-20 caractères alphanumériques
 * ou underscore). Les codes standards TRAVEL/MEALS/SUPPLIES/OTHER
 * sont déjà seedés par V54 ; cet endpoint sert à créer des codes
 * personnalisés (ex: HOTEL, PARKING, TELEPHONE).
 * @param label libellé long optionnel (ex: "Hébergement")
 * @param dailyLimit plafond journalier par employé/tiers. Null = pas de plafond.
 * Doit être ≥ 0 si fourni.
 * @param monthlyLimit plafond mensuel par employé/tiers. Null = pas de plafond.
 * Doit être ≥ 0 si fourni.
 */
public record CreateExpenseCategoryRequest(
 @NotBlank
 @Size(max = 20)
 @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,19}$",
 message = "Le code doit commencer par une lettre majuscule et ne contenir que des "
 + "majuscules, chiffres ou underscores (max 20 caractères)")
 String code,
 @Size(max = 100) String label,
 @PositiveOrZero BigDecimal dailyLimit,
 @PositiveOrZero BigDecimal monthlyLimit
) {}

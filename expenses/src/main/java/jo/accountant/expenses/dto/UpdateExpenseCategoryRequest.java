package jo.accountant.expenses.dto;

import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Corps de requête pour {@code PUT /api/v1/companies/{companyId}/expenses/categories/{categoryId}}
 * (Finding #12 — CRUD endpoints).
 *
 * <p>Modifie les plafonds journaliers/mensuels d'une catégorie existante. Le code n'est
 * PAS modifiable (il est référencé par les lignes de notes de frais existantes via la
 * contrainte CHECK {@code chk_el_category} — un changement de code casserait l'intégrité
 * référentielle).
 *
 * <p>Pour désactiver un plafond, passer explicitement {@code null} dans le JSON. Pour
 * conserver la valeur existante, omettre le champ (mais avec Jackson, omission = null ;
 * on distingue donc "null explicite = désactiver" de "null implicite = pas de changement"
 * via {@code nullable} côté service — voir {@code ExpenseCategoryService.update}).
 *
 * <p>Astuce pratique : passer {@code 0} pour désactiver un plafond équivaut à l'autoriser
 * sans limite (interprété comme "pas de plafond" car le validateur
 * {@code validateCategoryLimits} considère {@code dailyLimit=0} comme un plafond de 0,
 * ce qui bloquerait toute dépense — à éviter). Pour désactiver, préférez {@code null}.
 *
 * @param label         nouveau libellé (null = inchangé)
 * @param dailyLimit    nouveau plafond journalier (null = désactivé / inchangé selon
 *                      la stratégie ci-dessus). Doit être ≥ 0 si fourni.
 * @param monthlyLimit  nouveau plafond mensuel (null = désactivé / inchangé). ≥ 0 si fourni.
 */
public record UpdateExpenseCategoryRequest(
    String label,
    @PositiveOrZero BigDecimal dailyLimit,
    @PositiveOrZero BigDecimal monthlyLimit
) {}

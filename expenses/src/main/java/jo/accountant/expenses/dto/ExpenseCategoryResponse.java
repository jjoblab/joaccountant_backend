package jo.accountant.expenses.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Réponse d'une catégorie de note de frais (Finding #12 — CRUD endpoints).
 *
 * <p>Exposée par {@code GET /api/v1/companies/{companyId}/expenses/categories} et
 * {@code POST /api/v1/companies/{companyId}/expenses/categories}.
 *
 * @param id            UUID de la catégorie
 * @param companyId     UUID de l'entreprise propriétaire
 * @param code          code court (TRAVEL, MEALS, SUPPLIES, OTHER ou code personnalisé)
 * @param label         libellé long optionnel
 * @param dailyLimit    plafond journalier par employé/tiers (null = pas de plafond)
 * @param monthlyLimit  plafond mensuel par employé/tiers (null = pas de plafond)
 */
public record ExpenseCategoryResponse(
    UUID id,
    UUID companyId,
    String code,
    String label,
    BigDecimal dailyLimit,
    BigDecimal monthlyLimit
) {}

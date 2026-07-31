package jo.accountant.reporting.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Top tiers par volume (Task v2.5.0-task18 — Analytics Dashboard).
 *
 * <p>Utilisé pour les listes « Top 5 clients » (basées sur les factures de
 * ventes) et « Top 5 fournisseurs » (basées sur les factures d'achat). Le
 * montant agrégé correspond au total TTC des factures sur la période
 * (exercice fiscal actif) pour le tiers considéré.
 *
 * @param id     identifiant du tiers (ThirdParty.id)
 * @param name   nom du tiers (ThirdParty.name)
 * @param amount somme des montants TTC des factures du tiers
 * @param rank   rang (1-based, 1 = plus gros volume)
 */
public record AnalyticsTopEntity(
    UUID id,
    String name,
    BigDecimal amount,
    int rank
) {}

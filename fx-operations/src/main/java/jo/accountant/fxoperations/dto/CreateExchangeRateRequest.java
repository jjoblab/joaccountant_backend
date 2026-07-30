package jo.accountant.fxoperations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Corps de requête pour {@code POST .../fx-operations/rates}.
 *
 * <p>Crée ou met à jour un taux de change pour une date donnée. Le taux est direct
 * (fromCurrency → toCurrency), pas inversé. Si on a EUR→USD mais qu'on a besoin de
 * USD→EUR, le service calcule l'inverse automatiquement.
 *
 * @param fromCurrency code ISO 4217 (ex. "USD")
 * @param toCurrency code ISO 4217 (ex. "HTG")
 * @param rate taux : 1 unité fromCurrency = rate unités toCurrency
 * @param asOfDate date d'effet (optionnel — défaut : aujourd'hui)
 * @param source source du taux (ex. "Banque Nationale", "BCEAO", "manuel")
 */
public record CreateExchangeRateRequest(
    @NotBlank String fromCurrency,
    @NotBlank String toCurrency,
    @NotNull @Positive BigDecimal rate,
    LocalDate asOfDate,
    String source
) {}

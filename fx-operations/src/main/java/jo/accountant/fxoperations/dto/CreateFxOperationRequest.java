package jo.accountant.fxoperations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.fxoperations.entity.FxOperationType;

/**
 * Corps de requête pour {@code POST .../fx-operations}.
 *
 * <p>Convention : pour une opération {@code BUY}, on vend {@code fromAmount} de
 * {@code fromCurrency} pour acheter {@code toAmount} de {@code toCurrency}. Pour une
 * opération {@code SELL}, c'est l'inverse. Pour {@code REVALUATION}, on fournit le solde
 * courant et le taux de clôture ; le gain/perte est calculé automatiquement.
 *
 * @param type BUY / SELL / REVALUATION
 * @param fromCurrency code ISO 4217 de la devise source (ex. "HTG")
 * @param toCurrency code ISO 4217 de la devise cible (ex. "USD")
 * @param fromAmount montant vendu (en fromCurrency)
 * @param toAmount montant acheté (en toCurrency). Pour REVALUATION, c'est le solde converti au taux de clôture
 * @param rate taux appliqué (1 fromCurrency = rate toCurrency)
 * @param operationDate date de l'opération
 * @param description description libre (optionnel)
 * @param bankAccountId compte de trésorerie à débiter/créditer (optionnel — fallback sur compte CASH)
 
 *
 * @author jo@Dev


*/
public record CreateFxOperationRequest(
    @NotNull FxOperationType type,
    @NotBlank String fromCurrency,
    @NotBlank String toCurrency,
    @NotNull @Positive BigDecimal fromAmount,
    @NotNull @Positive BigDecimal toAmount,
    @NotNull @Positive BigDecimal rate,
    @NotNull LocalDate operationDate,
    String description,
    UUID bankAccountId
) {}

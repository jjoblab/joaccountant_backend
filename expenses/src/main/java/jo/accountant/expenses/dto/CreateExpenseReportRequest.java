package jo.accountant.expenses.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Corps de requête pour {@code POST .../expense-reports}.
 *
 * @param thirdPartyId ID du tiers employé (nullable — dépense d'exploitation générale)
 * @param expenseDate date de la dépense
 * @param currency code ISO 4217 (défaut : HTG)
 * @param description description libre
 * @param paidDirectly false (défaut) = à rembourser à l'employé ; true = payé par trésorerie
 * @param lines lignes de la note de frais
 
 *
 * @author jo@Dev


*/
public record CreateExpenseReportRequest(
    UUID thirdPartyId,
    @NotNull LocalDate expenseDate,
    String currency,
    String description,
    boolean paidDirectly,
    @NotEmpty List<LineDto> lines
) {
    public record LineDto(
        String category,
        @NotNull String description,
        @NotNull @jakarta.validation.constraints.Positive BigDecimal amount,
        UUID expenseAccountId
    ) {}
}

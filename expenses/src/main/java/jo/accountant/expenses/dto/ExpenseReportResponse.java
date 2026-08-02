package jo.accountant.expenses.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.expenses.entity.ExpenseReportStatus;

/**
 * ExpenseReportResponse.
 *
 * @author jo@Dev


 */

public record ExpenseReportResponse(
    UUID id,
    UUID companyId,
    UUID thirdPartyId,
    String thirdPartyName,
    ExpenseReportStatus status,
    LocalDate expenseDate,
    String currency,
    String description,
    BigDecimal totalAmount,
    boolean paidDirectly,
    UUID journalEntryId,
    List<LineResponse> lines,
    Instant createdAt,
    Instant updatedAt
) {
    public record LineResponse(
        UUID id,
        String category,
        String description,
        BigDecimal amount,
        UUID expenseAccountId
    ) {}
}

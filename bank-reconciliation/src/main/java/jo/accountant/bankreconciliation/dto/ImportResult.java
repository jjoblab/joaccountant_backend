package jo.accountant.bankreconciliation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.bankreconciliation.entity.BankStatementFormat;

/**
 * ImportResult.
 *
 * @author jo@Dev


 */

public record ImportResult(
    UUID importId,
    UUID bankAccountId,
    BankStatementFormat format,
    int lineCount,
    int autoMatchedCount,
    Instant importedAt,
    List<BankStatementLineDto> lines
) {
    public record BankStatementLineDto(
        UUID id, LocalDate date, java.math.BigDecimal amount, String description, boolean matched
    ) {}
}

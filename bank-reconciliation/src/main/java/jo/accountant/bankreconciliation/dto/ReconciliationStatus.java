package jo.accountant.bankreconciliation.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ReconciliationStatus.
 *
 * @author jo@Dev


 */

public record ReconciliationStatus(
    UUID bankAccountId,
    String label,
    int totalLines,
    int matchedLines,
    int unmatchedLines,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    List<UnmatchedLine> unmatched
) {
    public record UnmatchedLine(
        UUID id, java.time.LocalDate date, BigDecimal amount, String description
    ) {}
}

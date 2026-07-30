package jo.accountant.financialstatements.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.financialstatements.entity.FinancialStatementType;

/**
 * Réponse d'un snapshot figé.
 */
public record SnapshotResponse(
    UUID id,
    UUID companyId,
    FinancialStatementType type,
    UUID periodId,
    Instant generatedAt,
    boolean frozen,
    LocalDate asOfDate,
    LocalDate fromDate,
    LocalDate toDate,
    String contentJson
) {}

package jo.accountant.accountingengine.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;

/**
 * Réponse pour une écriture comptable.
 
 *
 * @author jo@Dev


*/
public record JournalEntryResponse(
    UUID id,
    UUID companyId,
    UUID journalId,
    String journalCode,
    UUID fiscalPeriodId,
    LocalDate entryDate,
    String reference,
    String description,
    JournalEntryStatus status,
    Instant postedAt,
    UUID postedBy,
    UUID reversalOfEntryId,
    JournalEntrySourceModule sourceModule,
    String idempotencyKey,
    List<LineResponse> lines,
    BigDecimal totalDebit,
    BigDecimal totalCredit
) {

    /** Ligne d'écriture avec son code compte et ses tags analytiques. */
    public record LineResponse(
        UUID id,
        UUID accountId,
        String accountCode,
        UUID thirdPartyId,
        BigDecimal debit,
        BigDecimal credit,
        int lineNumber,
        String description,
        List<AnalyticalTagResponse> analyticalTags
    ) {}

    /** Tag analytique d'une ligne. */
    public record AnalyticalTagResponse(
        UUID id,
        UUID planId,
        UUID valueId,
        BigDecimal allocationPercentage
    ) {}
}

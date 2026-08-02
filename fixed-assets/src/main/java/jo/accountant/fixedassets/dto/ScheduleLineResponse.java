package jo.accountant.fixedassets.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ligne d'échéancier d'amortissement (réponse).
 
 *
 * @author jo@Dev


*/
public record ScheduleLineResponse(
    UUID id,
    UUID assetId,
    UUID componentId,
    UUID periodId,
    LocalDate periodDate,
    BigDecimal amount,
    BigDecimal cumulativeAmount,
    UUID journalEntryId,
    Instant postedAt,
    UUID postedBy,
    boolean posted
) {}

package jo.accountant.timebilling.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * BillableRateResponse.
 *
 * @author jo@Dev


 */

public record BillableRateResponse(
    UUID id,
    UUID companyId,
    UUID projectId,
    UUID resourceUserId,
    BigDecimal hourlyRate,
    String currency,
    Instant createdAt
) {}

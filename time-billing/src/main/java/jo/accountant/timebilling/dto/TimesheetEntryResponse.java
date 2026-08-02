package jo.accountant.timebilling.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * TimesheetEntryResponse.
 *
 * @author jo@Dev


 */

public record TimesheetEntryResponse(
    UUID id,
    UUID companyId,
    UUID projectId,
    UUID resourceUserId,
    LocalDate entryDate,
    BigDecimal hours,
    boolean billable,
    boolean approved,
    boolean invoiced,
    String description,
    Instant createdAt,
    Instant updatedAt
) {}

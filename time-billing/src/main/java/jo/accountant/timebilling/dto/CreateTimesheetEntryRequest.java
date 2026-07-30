package jo.accountant.timebilling.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateTimesheetEntryRequest(
    @NotNull UUID projectId,
    @NotNull UUID resourceUserId,
    @NotNull LocalDate entryDate,
    @NotNull @Positive BigDecimal hours,
    Boolean billable,
    String description
) {}

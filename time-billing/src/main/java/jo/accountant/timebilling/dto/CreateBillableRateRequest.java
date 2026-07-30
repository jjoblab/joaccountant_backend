package jo.accountant.timebilling.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateBillableRateRequest(
    UUID projectId,
    UUID resourceUserId,
    @NotNull @Positive BigDecimal hourlyRate,
    @NotNull String currency
) {}

package jo.accountant.invoicing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record RecordPaymentRequest(
    @NotNull @Positive BigDecimal amount
) {}

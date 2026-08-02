package jo.accountant.invoicing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * RecordPaymentRequest.
 *
 * @author jo@Dev


 */

public record RecordPaymentRequest(
    @NotNull @Positive BigDecimal amount
) {}

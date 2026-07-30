package jo.accountant.purchasing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Corps de requête pour {@code POST .../purchase-invoices/{id}/payments}.
 *
 * <p>Symétrique de {@code RecordPaymentRequest} du module :invoicing.
 */
public record RecordPurchasePaymentRequest(
    @NotNull @Positive BigDecimal amount
) {}

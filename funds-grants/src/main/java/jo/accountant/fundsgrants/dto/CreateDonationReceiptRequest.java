package jo.accountant.fundsgrants.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * CreateDonationReceiptRequest.
 *
 * @author jo@Dev


 */

public record CreateDonationReceiptRequest(
    UUID grantId,
    @NotNull UUID donorThirdPartyId,
    @NotNull @Positive BigDecimal amount,
    LocalDate receiptDate,
    String description
) {}

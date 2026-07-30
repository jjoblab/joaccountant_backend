package jo.accountant.fundsgrants.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.RestrictionType;

public record CreateGrantRequest(
    @NotNull UUID donorThirdPartyId,
    @NotBlank String code,
    @NotBlank String label,
    @NotNull BigDecimal totalAmount,
    String currency,
    @NotNull LocalDate startDate,
    LocalDate endDate,
    RestrictionType restrictionType,
    UUID analyticalValueId
) {
    public CreateGrantRequest {
        if (currency == null) currency = "HTG";
        if (restrictionType == null) restrictionType = RestrictionType.RESTRICTED;
    }
}

package jo.accountant.timebilling.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import jo.accountant.timebilling.entity.BillingType;

/**
 * CreateProjectRequest.
 *
 * @author jo@Dev


 */

public record CreateProjectRequest(
    @NotBlank String code,
    @NotBlank String label,
    UUID clientThirdPartyId,
    BillingType billingType
) {
    public CreateProjectRequest {
        if (billingType == null) billingType = BillingType.TIME_AND_MATERIALS;
    }
}

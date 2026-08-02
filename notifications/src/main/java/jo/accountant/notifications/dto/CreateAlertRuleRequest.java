package jo.accountant.notifications.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import jo.accountant.notifications.entity.AlertType;
import java.math.BigDecimal;

/**
 * CreateAlertRuleRequest.
 *
 * @author jo@Dev


 */

public record CreateAlertRuleRequest(
    @NotNull AlertType type,
    BigDecimal thresholdValue,
    Boolean active
) {
    public CreateAlertRuleRequest {
        if (active == null) active = true;
    }
}

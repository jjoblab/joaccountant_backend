package jo.accountant.notifications.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * UpdatePreferencesRequest.
 *
 * @author jo@Dev


 */

public record UpdatePreferencesRequest(
    @NotNull String type,
    Boolean emailEnabled,
    Boolean inAppEnabled
) {}

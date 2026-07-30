package jo.accountant.notifications.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdatePreferencesRequest(
    @NotNull String type,
    Boolean emailEnabled,
    Boolean inAppEnabled
) {}

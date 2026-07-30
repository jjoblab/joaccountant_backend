package jo.accountant.bankreconciliation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MatchRequest(
    @NotNull UUID journalLineId
) {}

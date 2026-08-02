package jo.accountant.bankreconciliation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * MatchRequest.
 *
 * @author jo@Dev


 */

public record MatchRequest(
    @NotNull UUID journalLineId
) {}

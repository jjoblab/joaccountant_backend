package jo.accountant.accountingengine.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de requête pour {@code POST .../journals}.
 
 *
 * @author jo@Dev


*/
public record CreateJournalRequest(
    @NotBlank String code,
    @NotBlank String label
) {}

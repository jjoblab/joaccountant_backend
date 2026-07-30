package jo.accountant.bankreconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateBankAccountRequest(
    @NotNull UUID treasuryAccountId,
    @NotBlank String label,
    String accountNumber
) {}

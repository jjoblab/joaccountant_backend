package jo.accountant.bankreconciliation.dto;

import jakarta.validation.constraints.NotNull;
import jo.accountant.bankreconciliation.entity.BankStatementFormat;

public record ImportBankStatementRequest(
    @NotNull BankStatementFormat format,
    @NotNull String fileContent
) {}

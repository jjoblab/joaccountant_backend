package jo.accountant.bankreconciliation.dto;

import jakarta.validation.constraints.NotNull;
import jo.accountant.bankreconciliation.entity.BankStatementFormat;

/**
 * ImportBankStatementRequest.
 *
 * @author jo@Dev


 */

public record ImportBankStatementRequest(
    @NotNull BankStatementFormat format,
    @NotNull String fileContent
) {}

package jo.accountant.payroll.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PayslipResponse(
    UUID id,
    UUID companyId,
    UUID runId,
    UUID employeeId,
    String employeeName,
    String employeeNumber,
    BigDecimal grossSalary,
    List<DeductionLine> deductions,
    List<DeductionLine> employerContributions,
    BigDecimal netPay,
    String payslipNumber,
    Instant createdAt,
    Instant updatedAt
) {
    public record DeductionLine(
        String code,
        String label,
        BigDecimal rate,
        BigDecimal amount
    ) {}
}

package jo.accountant.payroll.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jo.accountant.payroll.entity.PayrollRunStatus;
import jo.accountant.payroll.entity.PayrollRunType;

/**
 * PayrollRunResponse.
 *
 * @author jo@Dev


 */

public record PayrollRunResponse(
    UUID id,
    UUID companyId,
    int periodMonth,
    int periodYear,
    PayrollRunStatus status,
    BigDecimal totalGross,
    BigDecimal totalNet,
    BigDecimal totalEmployerContributions,
    UUID journalEntryId,
    int payslipCount,
    Instant createdAt,
    Instant updatedAt,
    PayrollRunType runType
) {

    /**
     * Constructeur backward-compat — sans runType. Délègue au canonique
     * avec runType = REGULAR (défaut pour les campagnes existantes).
     */
    public PayrollRunResponse(
        UUID id, UUID companyId, int periodMonth, int periodYear,
        PayrollRunStatus status, BigDecimal totalGross, BigDecimal totalNet,
        BigDecimal totalEmployerContributions, UUID journalEntryId,
        int payslipCount, Instant createdAt, Instant updatedAt
    ) {
        this(id, companyId, periodMonth, periodYear, status, totalGross, totalNet,
             totalEmployerContributions, journalEntryId, payslipCount,
             createdAt, updatedAt, PayrollRunType.REGULAR);
    }
}

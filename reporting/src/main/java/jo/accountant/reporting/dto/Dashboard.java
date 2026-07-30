package jo.accountant.reporting.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Tableau de bord de synthèse (§13 Phase 17).
 *
 * <p>Position de trésorerie, balance âgée clients/fournisseurs, principales charges.
 */
public record Dashboard(
    UUID companyId,
    BigDecimal cashPosition,
    BigDecimal totalReceivables,
    BigDecimal totalPayables,
    List<CategoryAmount> topExpenses,
    List<CategoryAmount> topRevenues,
    int pendingApprovals,
    int overdueInvoices
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
}

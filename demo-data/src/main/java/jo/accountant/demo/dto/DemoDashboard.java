package jo.accountant.demo.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * V8.1 — Dashboard d'une entreprise démo pour GET /api/v1/demos/{demoCode}/dashboard.
 */
public record DemoDashboard(
    UUID companyId,
    String demoCode,
    String name,
    String fiscalYear,
    Kpi kpi,
    List<Alert> alerts,
    List<TransactionSummary> recentTransactions
) {
    public record Kpi(
        BigDecimal totalRevenue,
        BigDecimal totalExpenses,
        BigDecimal netResult,
        BigDecimal incomeTax,
        BigDecimal cashPosition,
        List<MonthlyAmount> monthlyRevenue,
        List<MonthlyAmount> monthlyExpenses
    ) {}

    public record MonthlyAmount(String month, BigDecimal amount) {}

    public record Alert(String type, String date, String label, BigDecimal amount) {}

    public record TransactionSummary(
        String date,
        String type,
        String label,
        BigDecimal amount,
        String currency
    ) {}
}

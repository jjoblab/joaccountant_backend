package jo.accountant.payroll.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Corps de requête pour {@code POST .../payroll-runs}.
 *
 * @param periodMonth mois (1-12)
 * @param periodYear  année (>= 2000)
 * @param employerContributionRate taux des charges patronales appliqué uniformément (ex. 14 = 14 %).
 *        Au MVP, pas de détail par cotisation — c'est un taux global simple. 0 = pas de
 *        charges patronales (ex. consultant).
 */
public record CreatePayrollRunRequest(
    @NotNull @Min(1) @Max(12) Integer periodMonth,
    @NotNull @Min(2000) Integer periodYear,
    @NotNull @PositiveOrZero java.math.BigDecimal employerContributionRate
) {}

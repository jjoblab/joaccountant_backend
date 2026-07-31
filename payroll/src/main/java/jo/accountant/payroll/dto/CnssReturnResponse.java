package jo.accountant.payroll.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bordereau CNSS/OFATMA/AST agrégé par employé sur une période
 * (step7-backend — Reports Hub v2.5.0).
 *
 * <p>DTO retourné par {@code GET /api/v1/companies/{companyId}/payroll/cnss-return}.
 * Utilisé par le rapport mobile "CNSS_RETURN" qui bascule de "Bientôt disponible" à fonctionnel
 * — portant le Reports Hub à 19/19 rapports implémentés.
 *
 * <p>Champs de synthèse :
 * <ul>
 *   <li>{@code companyName} — nom de l'entreprise (résolu via {@code CompanyRepository}).</li>
 *   <li>{@code period} — libellé de la période : "YYYY-MM" si 1 mois, "YYYY-MM_YYYY-MM" sinon.</li>
 *   <li>{@code fiscalYearLabel} — libellé de l'exercice fiscale contenant la période (ex: "Exercice 2026"),
 *       ou chaîne vide si non résolu.</li>
 *   <li>{@code currency} — devise fonctionnelle de l'entreprise (HTG, EUR, USD, ...).</li>
 *   <li>{@code totalGross} — somme des salaires bruts sur la période.</li>
 *   <li>{@code totalTaxableBase} — somme des assiettes imposables.</li>
 *   <li>{@code totalEmployeeContribution} — somme des cotisations salariales CNSS/OFATMA/AST.</li>
 *   <li>{@code totalEmployerContribution} — somme des cotisations patronales CNSS/OFATMA/AST.</li>
 * </ul>
 */
public record CnssReturnResponse(
    String companyName,
    String period,
    String fiscalYearLabel,
    String currency,
    BigDecimal totalGross,
    BigDecimal totalTaxableBase,
    BigDecimal totalEmployeeContribution,
    BigDecimal totalEmployerContribution,
    int payslipCount,
    int runCount,
    List<CnssReturnLine> lines
) {}

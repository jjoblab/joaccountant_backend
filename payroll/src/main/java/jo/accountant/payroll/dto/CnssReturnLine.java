package jo.accountant.payroll.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Ligne d'un bordereau CNSS/OFATMA/AST agrégée par employé (step7-backend — Reports Hub v2.5.0).
 *
 * <p>Une ligne agrège tous les bulletins de paie d'un employé sur la période [from, to] :
 * <ul>
 *   <li>{@code grossSalary} — somme des {@code Payslip.grossSalary} de l'employé sur la période.</li>
 *   <li>{@code taxableBase} — assiette imposable = {@code grossSalary - somme des cotisations
 *       sociales salariales (CNSS + OFATMA + AST)}. Calcul aligné sur
 *       {@code PayrollCalculator.computeTaxableBase}.</li>
 *   <li>{@code employeeContribution} — somme des cotisations salariales CNSS/OFATMA/AST.</li>
 *   <li>{@code employerContribution} — somme des cotisations patronales CNSS/OFATMA/AST.</li>
 *   <li>{@code details} — map code de cotisation → montant (cumul sur la période, tous
 *       bulletins confondus) pour le détail du bordereau.</li>
 * </ul>
 *
 * <p>Codes filtrés (préfixes) :
 * <ul>
 *   <li>{@code CNSS_HT_*} — Caisse Nationale d'Assurances Sociales d'Haïti (employeur + salarié).</li>
 *   <li>{@code OFATMA_HT_*} — Office d'Accident du Travail, Maladie et Assistance (Haïti).</li>
 *   <li>{@code AST_HT*} — Ajustement Social Temporaire (employé uniquement).</li>
 * </ul>
 */
public record CnssReturnLine(
    UUID employeeId,
    String employeeName,
    String employeeNumber,
    String cnssNumber,
    String ofatmaSectorCode,
    BigDecimal grossSalary,
    BigDecimal taxableBase,
    BigDecimal employeeContribution,
    BigDecimal employerContribution,
    int payslipCount,
    Map<String, BigDecimal> details
) {}

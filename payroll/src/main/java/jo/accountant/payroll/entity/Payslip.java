package jo.accountant.payroll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Bulletin de paie (restructuration 2026-07-24 — module :payroll).
 *
 * <p>Un bulletin par employé {@code ACTIVE} à la date de la campagne. Généré par
 * {@code PayrollService.calculate()} — immuable après calcul (correction = clôturer la
 * campagne et en créer une nouvelle).
 *
 * <p>Les champs {@code deductions} et {@code employerContributions} sont des JSONB
 * arbitraires — structure typique : {@code [{"code": "INCOME_TAX", "label": "Impôt sur
 * le revenu", "rate": 15.0, "amount": 15000}, ...]}. Le calcul se fait via les
 * {@code WithholdingRule} de {@code :tax} dont {@code applicableThirdPartyTypes} contient
 * {@code EMPLOYEE}.
 *
 * <p>{@code netPay = grossSalary - sum(deductions.amount)}. Les charges patronales ne
 * impactent pas le net — elles sont supportées par l'employeur.
 */
@Entity
@Table(name = "payslip")
public class Payslip extends TenantAwareEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "gross_salary", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossSalary = BigDecimal.ZERO;

    /** JSONB : liste des retenues salariales (calculées via :tax WithholdingRule). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deductions", columnDefinition = "jsonb")
    private String deductions;

    /** JSONB : liste des charges patronales. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "employer_contributions", columnDefinition = "jsonb")
    private String employerContributions;

    @Column(name = "net_pay", nullable = false, precision = 19, scale = 4)
    private BigDecimal netPay = BigDecimal.ZERO;

    /** Numéro de bulletin attribué via :document-numbering (DocumentType.PAYSLIP, scopeKey="PA"). */
    @Column(name = "payslip_number", length = 50)
    private String payslipNumber;

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }

    public BigDecimal getGrossSalary() { return grossSalary; }
    public void setGrossSalary(BigDecimal grossSalary) { this.grossSalary = grossSalary; }

    public String getDeductions() { return deductions; }
    public void setDeductions(String deductions) { this.deductions = deductions; }

    public String getEmployerContributions() { return employerContributions; }
    public void setEmployerContributions(String employerContributions) {
        this.employerContributions = employerContributions;
    }

    public BigDecimal getNetPay() { return netPay; }
    public void setNetPay(BigDecimal netPay) { this.netPay = netPay; }

    public String getPayslipNumber() { return payslipNumber; }
    public void setPayslipNumber(String payslipNumber) { this.payslipNumber = payslipNumber; }
}

package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.payroll.entity.Payslip;

/**
 * Builder fluent pour créer des bulletins de paie démo.
 
 *
 * @author jo@Dev


*/
public class PayslipBuilder {

    private final Payslip payslip = new Payslip();

    public PayslipBuilder() {
        payslip.setGrossSalary(BigDecimal.ZERO);
        payslip.setNetPay(BigDecimal.ZERO);
        payslip.setDeductions("[]");
        payslip.setEmployerContributions("[]");
    }

    public PayslipBuilder runId(UUID id) { payslip.setRunId(id); return this; }
    public PayslipBuilder employeeId(UUID id) { payslip.setEmployeeId(id); return this; }
    public PayslipBuilder grossSalary(BigDecimal g) { payslip.setGrossSalary(g); return this; }
    public PayslipBuilder netPay(BigDecimal n) { payslip.setNetPay(n); return this; }
    public PayslipBuilder deductions(String d) { payslip.setDeductions(d); return this; }
    public PayslipBuilder employerContributions(String c) { payslip.setEmployerContributions(c); return this; }
    public PayslipBuilder payslipNumber(String n) { payslip.setPayslipNumber(n); return this; }

    public Payslip build() {
        if (payslip.getRunId() == null) throw new IllegalStateException("runId required");
        if (payslip.getEmployeeId() == null) throw new IllegalStateException("employeeId required");
        return payslip;
    }
}

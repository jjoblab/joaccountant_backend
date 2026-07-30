package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunStatus;
import jo.accountant.payroll.entity.PayrollRunType;

/**
 * V8.1 — Builder fluent pour créer des campagnes de paie démo.
 */
public class PayrollRunBuilder {

    private final PayrollRun run = new PayrollRun();

    public PayrollRunBuilder() {
        run.setStatus(PayrollRunStatus.CALCULATED);
        run.setRunType(PayrollRunType.REGULAR);
        run.setTotalGross(BigDecimal.ZERO);
        run.setTotalNet(BigDecimal.ZERO);
        run.setTotalEmployerContributions(BigDecimal.ZERO);
    }

    public PayrollRunBuilder periodMonth(int m) { run.setPeriodMonth(m); return this; }
    public PayrollRunBuilder periodYear(int y) { run.setPeriodYear(y); return this; }
    public PayrollRunBuilder status(PayrollRunStatus s) { run.setStatus(s); return this; }
    public PayrollRunBuilder runType(PayrollRunType t) { run.setRunType(t); return this; }
    public PayrollRunBuilder totalGross(BigDecimal g) { run.setTotalGross(g); return this; }
    public PayrollRunBuilder totalNet(BigDecimal n) { run.setTotalNet(n); return this; }
    public PayrollRunBuilder totalEmployerContributions(BigDecimal c) { run.setTotalEmployerContributions(c); return this; }

    public PayrollRun build() {
        return run;
    }
}

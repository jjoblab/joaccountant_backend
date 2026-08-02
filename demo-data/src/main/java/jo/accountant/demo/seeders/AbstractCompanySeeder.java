package jo.accountant.demo.seeders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.demo.builders.DemoDataContext;
import jo.accountant.demo.builders.DemoDataContext.ResolvedContext;
import jo.accountant.demo.builders.JournalEntryBuilder;
import jo.accountant.demo.builders.PayslipBuilder;
import jo.accountant.demo.builders.PayrollRunBuilder;
import jo.accountant.employees.entity.Employee;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunType;
import jo.accountant.payroll.entity.Payslip;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.payroll.repository.PayslipRepository;

/**
 * Base abstraite pour les seeders d'entreprises démo.
 *
 * <p>Factorise la création des écritures comptables, campagnes de paie et 13e mois.
 * Les 4 seeders concrets étendent cette classe et implémentent {@link #seedSpecific(Company, ResolvedContext)}.
 
 *
 * @author jo@Dev


*/
public abstract class AbstractCompanySeeder implements CompanySeeder {

    protected final PayrollRunRepository payrollRunRepository;
    protected final PayslipRepository payslipRepository;
    protected final JournalEntryRepository journalEntryRepository;
    protected final JournalLineRepository journalLineRepository;
    protected final DemoDataContext dataContext;

    protected AbstractCompanySeeder(PayrollRunRepository payrollRunRepository,
                                     PayslipRepository payslipRepository,
                                     JournalEntryRepository journalEntryRepository,
                                     JournalLineRepository journalLineRepository,
                                     DemoDataContext dataContext) {
        this.payrollRunRepository = payrollRunRepository;
        this.payslipRepository = payslipRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.dataContext = dataContext;
    }

    /**
     * Crée une écriture comptable POSTED avec ses lignes.
     *
     * @param lines tableau de {Account, debit, credit, thirdPartyId}
     */
    protected int createJournalEntry(UUID companyId, ResolvedContext ctx, UUID journalId,
                                        LocalDate date, String description, Object[][] lines) {
        var fiscalPeriod = dataContext.findFiscalPeriod(ctx, date);
        if (fiscalPeriod.isEmpty()) return 0;

        JournalEntry entry = new JournalEntryBuilder()
            .journalId(journalId)
            .fiscalPeriodId(fiscalPeriod.get().getId())
            .entryDate(date)
            .description(description)
            .status(JournalEntryStatus.POSTED)
            .sourceModule(JournalEntrySourceModule.MANUAL)
            .build();
        entry = journalEntryRepository.save(entry);

        int lineNumber = 1;
        for (Object[] lineSpec : lines) {
            Account account = (Account) lineSpec[0];
            BigDecimal debit = (BigDecimal) lineSpec[1];
            BigDecimal credit = (BigDecimal) lineSpec[2];
            UUID thirdPartyId = (UUID) lineSpec[3];

            JournalLine line = new JournalLine();
            line.setJournalEntryId(entry.getId());
            line.setAccountId(account.getId());
            line.setAccountCode(account.getCode());
            line.setThirdPartyId(thirdPartyId);
            line.setDebit(debit);
            line.setCredit(credit);
            line.setLineNumber(lineNumber++);
            line.setDescription(description);
            journalLineRepository.save(line);
        }
        return 1 + lines.length;
    }

    /**
     * Crée une campagne de paie mensuelle + bulletins + écriture.
     */
    protected int createMonthlyPayroll(UUID companyId, ResolvedContext ctx, List<Employee> employees,
                                          LocalDate month, String prefix, BigDecimal cnssRate, BigDecimal ofatmaRate, BigDecimal itsRate) {
        PayrollRun run = new PayrollRunBuilder()
            .periodMonth(month.getMonthValue())
            .periodYear(month.getYear())
            .build();
        run = payrollRunRepository.save(run);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        for (Employee emp : employees) {
            BigDecimal gross = emp.getBaseSalary();
            BigDecimal cnss = gross.multiply(cnssRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal ofatma = gross.multiply(ofatmaRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal its = gross.multiply(itsRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(cnss).subtract(ofatma).subtract(its);
            Payslip payslip = new PayslipBuilder()
                .runId(run.getId())
                .employeeId(emp.getId())
                .grossSalary(gross)
                .netPay(net)
                .payslipNumber(prefix + "-PAY-" + month.getYear() + "-" + String.format("%02d", month.getMonthValue()) + "-" + emp.getEmployeeNumber())
                .build();
            payslipRepository.save(payslip);
            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
        }
        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        payrollRunRepository.save(run);

        if (ctx.journalOD != null && ctx.payrollAccount != null && ctx.employeeCollectiveAccount != null) {
            createJournalEntry(companyId, ctx, ctx.journalOD.getId(), month.withDayOfMonth(month.lengthOfMonth()),
                "Paie " + month.getMonthValue() + "/" + month.getYear(),
                new Object[][]{
                    {ctx.payrollAccount, totalGross, BigDecimal.ZERO, null},
                    {ctx.employeeCollectiveAccount, BigDecimal.ZERO, totalNet, null}
                });
        }

        return employees.size() + 2;
    }

    /**
     * Crée le 13e mois (Code Travail art. 153) pour tous les employés éligibles.
     */
    protected int createThirteenthMonth(UUID companyId, ResolvedContext ctx, List<Employee> employees,
                                           int year, String prefix, BigDecimal itsRate) {
        PayrollRun run = new PayrollRunBuilder()
            .periodMonth(12)
            .periodYear(year)
            .runType(PayrollRunType.THIRTEENTH_MONTH)
            .build();
        run = payrollRunRepository.save(run);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        for (Employee emp : employees) {
            if (Boolean.FALSE.equals(emp.getThirteenthMonthEligible())) continue;
            BigDecimal gross = emp.getBaseSalary();
            BigDecimal its = gross.multiply(itsRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(its);
            Payslip payslip = new PayslipBuilder()
                .runId(run.getId())
                .employeeId(emp.getId())
                .grossSalary(gross)
                .netPay(net)
                .payslipNumber(prefix + "-13M-" + year + "-" + emp.getEmployeeNumber())
                .build();
            payslipRepository.save(payslip);
            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
        }
        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        payrollRunRepository.save(run);

        if (ctx.journalOD != null && ctx.payrollAccount != null && ctx.employeeCollectiveAccount != null) {
            createJournalEntry(companyId, ctx, ctx.journalOD.getId(), LocalDate.of(year, 12, 31),
                "13e mois " + year,
                new Object[][]{
                    {ctx.payrollAccount, totalGross, BigDecimal.ZERO, null},
                    {ctx.employeeCollectiveAccount, BigDecimal.ZERO, totalNet, null}
                });
        }

        return employees.size() + 2;
    }
}

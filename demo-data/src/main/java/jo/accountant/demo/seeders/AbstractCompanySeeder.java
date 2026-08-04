package jo.accountant.demo.seeders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManager;
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

    /**
     * FIX v9.4.1 (audit T3.7) — Seuil de flush + clear du PersistenceContext.
     *
     * <p>Au-delà de ce nombre d'entités persistées dans la transaction courante, on
     * appelle {@code entityManager.flush()} (pour vider le buffer SQL vers la DB) puis
     * {@code entityManager.clear()} (pour détacher toutes les entités du PersistenceContext
     * et libérer la mémoire heap).
     *
     * <p>Sans ce mécanisme, le seed des 4 entreprises démo × 2 exercices fiscaux × données
     * business complètes (COA + journaux + factures + paie + écritures) génère ~50 000
     * objets JPA persistés que Hibernate garde en session. La heap grossit à ~800 MB,
     * ce qui provoque un OOM kill sur Render free tier (512 MB RAM).
     *
     * <p>Avec flush+clear tous les 1000 inserts, la heap reste sous 200 MB pendant tout
     * le seed. La contrepartie est que les entités détachées ne sont plus accessibles
     * via {@code entityManager.find()} après le clear — il faut donc re-fetch si besoin.
     */
    private static final int FLUSH_CLEAR_BATCH_SIZE = 1000;

    /**
     * FIX v9.4.1 (audit T3.7) — Compteur d'inserts dans la transaction courante.
     * Incrémenté à chaque {@code .save()} puis reset après flush+clear.
     */
    private final AtomicInteger insertCounter = new AtomicInteger(0);

    /**
     * FIX v9.4.1 (audit T3.7) — EntityManager injecté pour le flush+clear périodique.
     *
     * <p>Nullable car certains tests peuvent construire un AbstractCompanySeeder sans
     * EntityManager (ex: tests unitaires qui mockent les repositories). Dans ce cas,
     * le compteur est incrémenté mais le flush+clear est skip (best-effort).
     */
    protected final EntityManager entityManager;

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
        this(null, payrollRunRepository, payslipRepository, journalEntryRepository,
             journalLineRepository, dataContext);
    }

    /**
     * FIX v9.4.1 (audit T3.7) — Constructeur étendu avec EntityManager.
     *
     * <p>Les 4 seeders concrets (RetailCommerceSeeder, ProfessionalServicesSeeder,
     * NgoHumanitarianSeeder, FreeZoneIndustrySeeder) doivent appeler ce constructeur
     * pour bénéficier du flush+clear périodique. Le constructeur 5-args ci-dessus
     * est conservé pour rétro-compatibilité (tests unitaires).
     */
    protected AbstractCompanySeeder(EntityManager entityManager,
                                     PayrollRunRepository payrollRunRepository,
                                     PayslipRepository payslipRepository,
                                     JournalEntryRepository journalEntryRepository,
                                     JournalLineRepository journalLineRepository,
                                     DemoDataContext dataContext) {
        this.entityManager = entityManager;
        this.payrollRunRepository = payrollRunRepository;
        this.payslipRepository = payslipRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.dataContext = dataContext;
    }

    /**
     * FIX v9.4.1 (audit T3.7) — Incrémente le compteur d'inserts et déclenche flush+clear
     * si le seuil est atteint.
     *
     * <p>À appeler après chaque {@code repository.save(entity)} dans les seeders concrets.
     * Le nom "track" rappelle qu'il s'agit d'un suivi mémoire, pas d'un vrai persist.
     *
     * <p>Si {@link #entityManager} est null (test unitaire), la méthode est no-op
     * (le compteur est incrémenté mais aucun flush+clear n'est effectué).
     */
    protected void trackInsert() {
        int count = insertCounter.incrementAndGet();
        if (count >= FLUSH_CLEAR_BATCH_SIZE && entityManager != null) {
            entityManager.flush();
            entityManager.clear();
            insertCounter.set(0);
        }
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
        trackInsert();

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
            trackInsert();
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
            trackInsert();
            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
        }
        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        payrollRunRepository.save(run);
        trackInsert();

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
            trackInsert();
            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
        }
        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        payrollRunRepository.save(run);
        trackInsert();

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

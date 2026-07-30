package jo.accountant.demo.seeders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.entity.TaxExemptionStatus;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.demo.builders.DemoDataContext;
import jo.accountant.demo.builders.DemoDataContext.ResolvedContext;
import jo.accountant.demo.builders.EmployeeBuilder;
import jo.accountant.demo.builders.InvoiceBuilder;
import jo.accountant.demo.builders.JournalEntryBuilder;
import jo.accountant.demo.builders.PayslipBuilder;
import jo.accountant.demo.builders.PayrollRunBuilder;
import jo.accountant.demo.builders.ThirdPartyBuilder;
import jo.accountant.demo.fixtures.HaitianNames;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunType;
import jo.accountant.payroll.entity.Payslip;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.payroll.repository.PayslipRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.1 — PME 1 : Boutik Lakay S.A. (commerce retail Pétion-Ville).
 *
 * <p>Seed complet sur 2 exercices fiscaux (FY2024-2025 + FY2025-2026) :
 * <ul>
 *   <li>4 employés (Marie-Carmel Joseph gérante, Jean-Robert Pierre vendeur, Nadège Charles vendeuse, Frantz Moïse livreur)</li>
 *   <li>50 clients particuliers (noms haïtiens réalistes)</li>
 *   <li>10 fournisseurs (importateurs + grossistes locaux)</li>
 *   <li>24 mois de données : ~50 factures ventes/mois, ~10 factures achats/mois, 4 bulletins paie/mois</li>
 *   <li>Écritures comptables POSTED pour chaque opération</li>
 *   <li>13e mois en décembre (Code Travail art. 153)</li>
 * </ul>
 */
@Component
public class RetailCommerceSeeder implements CompanySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(RetailCommerceSeeder.class);
    private static final UUID PCN_HAITI_FRAMEWORK_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000004");

    private final CompanyRepository companyRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final EmployeeRepository employeeRepository;
    private final SalesInvoiceRepository invoiceRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final DemoDataContext dataContext;

    public RetailCommerceSeeder(CompanyRepository companyRepository,
                                   ThirdPartyRepository thirdPartyRepository,
                                   EmployeeRepository employeeRepository,
                                   SalesInvoiceRepository invoiceRepository,
                                   JournalEntryRepository journalEntryRepository,
                                   JournalLineRepository journalLineRepository,
                                   PayrollRunRepository payrollRunRepository,
                                   PayslipRepository payslipRepository,
                                   DemoDataContext dataContext) {
        this.companyRepository = companyRepository;
        this.thirdPartyRepository = thirdPartyRepository;
        this.employeeRepository = employeeRepository;
        this.invoiceRepository = invoiceRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.payslipRepository = payslipRepository;
        this.dataContext = dataContext;
    }

    @Override
    public String demoCode() { return "BOUTIK_LAKAY"; }

    @Override
    public String companyName() { return "Boutik Lakay S.A."; }

    @Override
    public String segment() { return "RETAIL_COMMERCE"; }

    @Override
    @Transactional
    public int seed() {
        Optional<Company> existing = companyRepository.findAll().stream()
            .filter(c -> companyName().equals(c.getName()) && Boolean.TRUE.equals(c.getIsDemo()))
            .findFirst();
        if (existing.isPresent()) {
            LOG.info("V8.1 — {} déjà seedée (id={})", companyName(), existing.get().getId());
            return 0;
        }

        Company company = new Company();
        company.setId(UUID.randomUUID());
        Instant now = Instant.now();
        company.setCreatedAt(now);
        company.setUpdatedAt(now);
        company.setName(companyName());
        company.setLegalForm(LegalForm.SARL);
        company.setCountry("HT");
        company.setFunctionalCurrency("HTG");
        company.setNif("1010101010BL");
        company.setAddress("Rue Lamarre, Pétion-Ville, Port-au-Prince");
        company.setSector(Sector.COMMERCE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setBusinessTypeCode("RETAIL_COMMERCE");
        company.setPrimaryActivityLabel("Commerce de détail — alimentation, ménagers, cosmétiques");
        company.setAccountingFrameworkId(PCN_HAITI_FRAMEWORK_ID);
        company.setFiscalYearStartMonth(10);
        company.setFreeZone(false);
        company.setTaxExemptionStatus(TaxExemptionStatus.STANDARD);
        company.setMonthlyLegalHours(new BigDecimal("208"));
        company.setWizardStep(9);
        company.setWizardCompleted(true); company.setWizardStep(4);
        company.setIsDemo(true);
        company = companyRepository.save(company);

        // Positionner le TenantContext pour que les entités TenantAwareEntity soient stampées
        TenantContext.setCompanyId(company.getId());
        int count = 0;
        try {
        TenantContext.setUserId(null);

        ResolvedContext ctx = dataContext.forCompany(company);
        count = 1;  // company

        // Créer employés + tiers
        List<ThirdParty> employeeTps = createEmployeeThirdParties(company, ctx);
        List<Employee> employees = createEmployees(company, employeeTps);
        List<ThirdParty> clients = createClients(company, ctx);
        List<ThirdParty> suppliers = createSuppliers(company, ctx);
        count += employeeTps.size() + employees.size() + clients.size() + suppliers.size();

        // Seed 24 mois de données (oct 2024 → sept 2026)
        LocalDate start = LocalDate.of(2024, 10, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        LocalDate month = start;
        int invoiceCounter = 1;
        while (!month.isAfter(end)) {
            // Achats (5 factures/mois)
            for (int i = 0; i < 5; i++) {
                ThirdParty supplier = suppliers.get((int) (Math.random() * suppliers.size()));
                BigDecimal subtotal = new BigDecimal(20000 + (int) (Math.random() * 80000))
                    .setScale(2, RoundingMode.HALF_UP);
                BigDecimal tax = subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
                createPurchaseInvoice(company, ctx, supplier, month.plusDays((int) (Math.random() * 28)),
                    subtotal, tax, invoiceCounter++);
                count++;
            }
            // Ventes (20 factures/mois)
            for (int i = 0; i < 20; i++) {
                ThirdParty client = clients.get((int) (Math.random() * clients.size()));
                BigDecimal subtotal = new BigDecimal(2000 + (int) (Math.random() * 18000))
                    .setScale(2, RoundingMode.HALF_UP);
                BigDecimal tax = subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
                createSalesInvoice(company, ctx, client, month.plusDays((int) (Math.random() * 28)),
                    subtotal, tax, invoiceCounter++);
                count++;
            }
            // Paie mensuelle (4 bulletins)
            count += createMonthlyPayroll(company, ctx, employees, month);
            // 13e mois en décembre
            if (month.getMonthValue() == 12) {
                count += createThirteenthMonth(company, ctx, employees, month.getYear());
            }
            month = month.plusMonths(1);
        }

        } catch (Exception e) {
            LOG.warn("V8.1 — Génération données échouée pour " + companyName() + " : {}", e.getMessage());
        }
        TenantContext.clear();
        LOG.info("V8.1 — {} seedée : {} enregistrements (data generation may have been partial)", companyName(), count);
        return count;
    }

    private List<ThirdParty> createEmployeeThirdParties(Company company, ResolvedContext ctx) {
        List<ThirdParty> tps = new ArrayList<>();
        if (ctx.employeeCollectiveAccount == null) return tps;
        String[] names = {"Marie-Carmel Joseph", "Jean-Robert Pierre", "Nadège Charles", "Frantz Moïse"};
        for (int i = 0; i < names.length; i++) {
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.EMPLOYEE)
                .name(names[i])
                .collectiveAccountId(ctx.employeeCollectiveAccount.getId())
                .address("Pétion-Ville")
                .build();
            tps.add(thirdPartyRepository.save(tp));
        }
        return tps;
    }

    private List<Employee> createEmployees(Company company, List<ThirdParty> employeeTps) {
        List<Employee> employees = new ArrayList<>();
        Object[][] specs = {
            {"Marie-Carmel Joseph", "Gérante", "Direction", new BigDecimal("75000"), "1234567890", "TRADE"},
            {"Jean-Robert Pierre", "Vendeur", "Ventes", new BigDecimal("25000"), "2345678901", "TRADE"},
            {"Nadège Charles", "Vendeuse", "Ventes", new BigDecimal("22000"), "3456789012", "TRADE"},
            {"Frantz Moïse", "Livreur", "Logistique", new BigDecimal("20000"), "4567890123", "TRANSP"}
        };
        LocalDate[] hireDates = {
            LocalDate.of(2015, 1, 1), LocalDate.of(2020, 3, 15),
            LocalDate.of(2022, 9, 1), LocalDate.of(2023, 6, 1)
        };
        for (int i = 0; i < specs.length; i++) {
            Employee emp = new EmployeeBuilder()
                .thirdPartyId(employeeTps.get(i).getId())
                .employeeNumber("BL-" + String.format("%03d", i + 1))
                .position((String) specs[i][1])
                .department((String) specs[i][2])
                .hireDate(hireDates[i])
                .baseSalary((BigDecimal) specs[i][3])
                .cnssNumber((String) specs[i][4])
                .ofatmaSectorCode((String) specs[i][5])
                .build();
            employees.add(employeeRepository.save(emp));
        }
        return employees;
    }

    private List<ThirdParty> createClients(Company company, ResolvedContext ctx) {
        List<ThirdParty> clients = new ArrayList<>();
        if (ctx.clientCollectiveAccount == null) return clients;
        for (int i = 0; i < 50; i++) {
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.CLIENT)
                .name(HaitianNames.randomFullName())
                .collectiveAccountId(ctx.clientCollectiveAccount.getId())
                .address("Pétion-Ville, Port-au-Prince")
                .build();
            clients.add(thirdPartyRepository.save(tp));
        }
        return clients;
    }

    private List<ThirdParty> createSuppliers(Company company, ResolvedContext ctx) {
        List<ThirdParty> suppliers = new ArrayList<>();
        if (ctx.supplierCollectiveAccount == null) return suppliers;
        String[] supplierNames = {
            "Big Maison Import", "Caribbean Foods SA", "Epi D'Or Gros", "Distributeur National",
            "Importex Haïti", "Gros Marché PAP", "Alimentation Plus", "Ménagers Express",
            "Cosmétiques Caraïbes", "Distribution Nationale"
        };
        for (int i = 0; i < supplierNames.length; i++) {
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.SUPPLIER)
                .name(supplierNames[i])
                .collectiveAccountId(ctx.supplierCollectiveAccount.getId())
                .nif(String.format("%010d", 1000000000 + i) + "AB")
                .address("Port-au-Prince")
                .build();
            suppliers.add(thirdPartyRepository.save(tp));
        }
        return suppliers;
    }

    private void createSalesInvoice(Company company, ResolvedContext ctx, ThirdParty client,
                                       LocalDate date, BigDecimal subtotal, BigDecimal tax, int counter) {
        SalesInvoice invoice = new InvoiceBuilder()
            .thirdPartyId(client.getId())
            .status(InvoiceStatus.ISSUED)
            .invoiceNumber("BL-VT-" + String.format("%06d", counter))
            .issueDate(date)
            .dueDate(date.plusDays(30))
            .currency("HTG")
            .subtotal(subtotal)
            .taxAmount(tax)
            .totalAmount(subtotal.add(tax))
            .build();
        invoiceRepository.save(invoice);

        // Écriture comptable D 411 Client / C 707 Ventes / C 443 TVA collectée
        if (ctx.journalVT != null && ctx.clientCollectiveAccount != null && ctx.salesAccount != null) {
            createJournalEntry(company, ctx, ctx.journalVT.getId(), date,
                "Vente " + invoice.getInvoiceNumber(),
                new Object[][]{
                    {ctx.clientCollectiveAccount, subtotal.add(tax), BigDecimal.ZERO, client.getId()},
                    {ctx.salesAccount, BigDecimal.ZERO, subtotal, null},
                    {ctx.vatCollectedAccount, BigDecimal.ZERO, tax, null}
                });
        }
    }

    private void createPurchaseInvoice(Company company, ResolvedContext ctx, ThirdParty supplier,
                                          LocalDate date, BigDecimal subtotal, BigDecimal tax, int counter) {
        // Pour les achats, on crée juste une écriture (pas de PurchaseInvoice entity pour simplifier)
        if (ctx.journalAC != null && ctx.supplierCollectiveAccount != null && ctx.purchaseAccount != null) {
            createJournalEntry(company, ctx, ctx.journalAC.getId(), date,
                "Achat BL-AC-" + String.format("%06d", counter),
                new Object[][]{
                    {ctx.purchaseAccount, subtotal, BigDecimal.ZERO, null},
                    {ctx.vatDeductibleAccount, tax, BigDecimal.ZERO, null},
                    {ctx.supplierCollectiveAccount, BigDecimal.ZERO, subtotal.add(tax), supplier.getId()}
                });
        }
    }

    private int createMonthlyPayroll(Company company, ResolvedContext ctx, List<Employee> employees, LocalDate month) {
        if (ctx.journalOD == null || ctx.payrollAccount == null || ctx.employeeCollectiveAccount == null) {
            return 0;
        }
        PayrollRun run = new PayrollRunBuilder()
            .periodMonth(month.getMonthValue())
            .periodYear(month.getYear())
            .build();
        run = payrollRunRepository.save(run);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        for (Employee emp : employees) {
            BigDecimal gross = emp.getBaseSalary();
            BigDecimal cnss = gross.multiply(new BigDecimal("0.06")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal ofatma = gross.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal its = gross.multiply(new BigDecimal("0.02")).setScale(2, RoundingMode.HALF_UP);  // simplifié
            BigDecimal net = gross.subtract(cnss).subtract(ofatma).subtract(its);
            Payslip payslip = new PayslipBuilder()
                .runId(run.getId())
                .employeeId(emp.getId())
                .grossSalary(gross)
                .netPay(net)
                .payslipNumber("BL-PAY-" + month.getYear() + "-" + String.format("%02d", month.getMonthValue()) + "-" + emp.getEmployeeNumber())
                .build();
            payslipRepository.save(payslip);
            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
        }
        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        payrollRunRepository.save(run);

        // Écriture paie D 631 Rémunérations / C 421 Personnel
        createJournalEntry(company, ctx, ctx.journalOD.getId(), month.withDayOfMonth(month.lengthOfMonth()),
            "Paie " + month.getMonthValue() + "/" + month.getYear(),
            new Object[][]{
                {ctx.payrollAccount, totalGross, BigDecimal.ZERO, null},
                {ctx.employeeCollectiveAccount, BigDecimal.ZERO, totalNet, null}
            });

        return employees.size() + 2;  // payslips + run + écriture
    }

    private int createThirteenthMonth(Company company, ResolvedContext ctx, List<Employee> employees, int year) {
        PayrollRun run = new PayrollRunBuilder()
            .periodMonth(12)
            .periodYear(year)
            .runType(PayrollRunType.THIRTEENTH_MONTH)
            .build();
        run = payrollRunRepository.save(run);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        for (Employee emp : employees) {
            BigDecimal gross = emp.getBaseSalary();  // 13e mois plein (tous ≥ 12 mois)
            BigDecimal its = gross.multiply(new BigDecimal("0.02")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(its);
            Payslip payslip = new PayslipBuilder()
                .runId(run.getId())
                .employeeId(emp.getId())
                .grossSalary(gross)
                .netPay(net)
                .payslipNumber("BL-13M-" + year + "-" + emp.getEmployeeNumber())
                .build();
            payslipRepository.save(payslip);
            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
        }
        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        payrollRunRepository.save(run);

        if (ctx.journalOD != null && ctx.payrollAccount != null && ctx.employeeCollectiveAccount != null) {
            createJournalEntry(company, ctx, ctx.journalOD.getId(), LocalDate.of(year, 12, 31),
                "13e mois " + year,
                new Object[][]{
                    {ctx.payrollAccount, totalGross, BigDecimal.ZERO, null},
                    {ctx.employeeCollectiveAccount, BigDecimal.ZERO, totalNet, null}
                });
        }

        return employees.size() + 2;
    }

    private void createJournalEntry(Company company, ResolvedContext ctx, UUID journalId,
                                       LocalDate date, String description, Object[][] lines) {
        var fiscalPeriod = dataContext.findFiscalPeriod(ctx, date);
        if (fiscalPeriod.isEmpty()) return;

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
    }

    // (Account imported from jo.accountant.chartofaccounts.entity)
}

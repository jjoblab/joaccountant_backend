package jo.accountant.demo.seeders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import jo.accountant.demo.builders.ThirdPartyBuilder;
import jo.accountant.demo.fixtures.HaitianNames;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
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
 * V8.1 — PME 4 : Caribbean Textiles S.A. (zone franche CODEVI, Ouanaminthe).
 *
 * <p>1200 employés (échantillonnés à 120 pour la démo), ~144M HTG/an, 100% export USA,
 * USD, IFRS_FULL, IS 15% zone franche (Code Fiscal art. 195).
 */
@Component
public class FreeZoneIndustrySeeder extends AbstractCompanySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(FreeZoneIndustrySeeder.class);
    private static final UUID IFRS_FULL_FRAMEWORK_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int SAMPLE_EMPLOYEES = 120;  // échantillon des 1200 réels

    private final CompanyRepository companyRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final EmployeeRepository employeeRepository;
    private final SalesInvoiceRepository invoiceRepository;

    public FreeZoneIndustrySeeder(CompanyRepository companyRepository,
                                    ThirdPartyRepository thirdPartyRepository,
                                    EmployeeRepository employeeRepository,
                                    SalesInvoiceRepository invoiceRepository,
                                    PayrollRunRepository payrollRunRepository,
                                    PayslipRepository payslipRepository,
                                    JournalEntryRepository journalEntryRepository,
                                    JournalLineRepository journalLineRepository,
                                    DemoDataContext dataContext) {
        super(payrollRunRepository, payslipRepository, journalEntryRepository, journalLineRepository, dataContext);
        this.companyRepository = companyRepository;
        this.thirdPartyRepository = thirdPartyRepository;
        this.employeeRepository = employeeRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Override
    public String demoCode() { return "CARIBBEAN_TEXTILES"; }

    @Override
    public String companyName() { return "Caribbean Textiles S.A."; }

    @Override
    public String segment() { return "WHOLESALE_COMMERCE"; }

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
        company.setLegalForm(LegalForm.SA);
        company.setCountry("HT");
        company.setFunctionalCurrency("USD");
        company.setNif("4040404040CT");
        company.setAddress("CODEVI Zone Franche, Ouanaminthe");
        company.setSector(Sector.INDUSTRIE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setBusinessTypeCode("WHOLESALE_COMMERCE");
        company.setPrimaryActivityLabel("Industrie textile — confection pour export USA");
        company.setAccountingFrameworkId(IFRS_FULL_FRAMEWORK_ID);
        company.setFiscalYearStartMonth(10);
        company.setFreeZone(true);
        company.setTaxExemptionStatus(TaxExemptionStatus.FREE_ZONE);
        company.setMonthlyLegalHours(new BigDecimal("208"));
        company.setWizardStep(9);
        company.setWizardCompleted(true); company.setWizardStep(4);
        company.setIsDemo(true);
        company = companyRepository.save(company);

        TenantContext.setCompanyId(company.getId());
        int count = 1;
        try {
        ResolvedContext ctx = dataContext.forCompany(company);

        // 5 clients américains
        List<ThirdParty> clients = new ArrayList<>();
        String[] clientNames = {"Hanes Brands Inc.", "Gap Inc.", "Gildan Activewear", "VF Corporation", "Under Armour"};
        for (int i = 0; i < clientNames.length; i++) {
            if (ctx.clientCollectiveAccount == null) break;
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.CLIENT)
                .name(clientNames[i])
                .collectiveAccountId(ctx.clientCollectiveAccount.getId())
                .address("USA")
                .build();
            clients.add(thirdPartyRepository.save(tp));
        }
        count += clients.size();

        // 120 employés (échantillon des 1200)
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < SAMPLE_EMPLOYEES; i++) {
            if (ctx.employeeCollectiveAccount == null) break;
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.EMPLOYEE)
                .name(HaitianNames.randomFullName())
                .collectiveAccountId(ctx.employeeCollectiveAccount.getId())
                .build();
            thirdPartyRepository.save(tp);

            BigDecimal salary;
            String position;
            String sector;
            if (i < 100) {
                salary = new BigDecimal("900");  // opérateur couture
                position = "Opérateur couture";
                sector = "TEXT";
            } else if (i < 110) {
                salary = new BigDecimal("2000");  // contremaître
                position = "Contremaître QA";
                sector = "TEXT";
            } else if (i < 118) {
                salary = new BigDecimal("1500");  // logistique
                position = "Logistique";
                sector = "TEXT";
            } else {
                salary = new BigDecimal("4000");  // support/cadres
                position = "Support admin";
                sector = "BANK";
            }

            Employee emp = new EmployeeBuilder()
                .thirdPartyId(tp.getId())
                .employeeNumber("CT-" + String.format("%04d", i + 1))
                .position(position)
                .department("Production")
                .hireDate(LocalDate.of(2018 + (i % 7), (i % 12) + 1, 15))
                .baseSalary(salary)
                .salaryCurrency("USD")
                .ofatmaSectorCode(sector)
                .overtimeHours50(new BigDecimal((int)(Math.random() * 20)))  // HS massives
                .build();
            employees.add(employeeRepository.save(emp));
        }
        count += employees.size() * 2;

        // 24 mois de données
        LocalDate start = LocalDate.of(2024, 10, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        LocalDate month = start;
        int invCounter = 1;
        while (!month.isAfter(end)) {
            // 30 factures export/mois (TVA 0%, USD)
            for (int i = 0; i < 30; i++) {
                ThirdParty client = clients.get((int) (Math.random() * clients.size()));
                BigDecimal subtotal = new BigDecimal(30000 + (int) (Math.random() * 120000))
                    .setScale(2, RoundingMode.HALF_UP);
                SalesInvoice invoice = new InvoiceBuilder()
                    .thirdPartyId(client.getId())
                    .status(InvoiceStatus.ISSUED)
                    .invoiceNumber("CT-VT-" + String.format("%06d", invCounter++))
                    .issueDate(month.plusDays((int) (Math.random() * 28)))
                    .dueDate(month.plusDays(60))
                    .currency("USD")
                    .subtotal(subtotal)
                    .taxAmount(BigDecimal.ZERO)  // TVA 0% export
                    .totalAmount(subtotal)
                    .build();
                invoiceRepository.save(invoice);
                count++;

                // Écriture D 411 / C 701 (TVA 0% pas de C 443)
                if (ctx.journalVT != null && ctx.clientCollectiveAccount != null && ctx.salesAccount != null) {
                    createJournalEntry(company.getId(), ctx, ctx.journalVT.getId(),
                        invoice.getIssueDate(), "Export " + invoice.getInvoiceNumber(),
                        new Object[][]{
                            {ctx.clientCollectiveAccount, subtotal, BigDecimal.ZERO, client.getId()},
                            {ctx.salesAccount, BigDecimal.ZERO, subtotal, null}
                        });
                }
            }
            // Paie mensuelle (120 employés, OFATMA TEXT 2.5%)
            count += createMonthlyPayroll(company.getId(), ctx, employees, month, "CT",
                new BigDecimal("0.06"), new BigDecimal("0.025"), new BigDecimal("0.02"));
            // 13e mois en décembre
            if (month.getMonthValue() == 12) {
                count += createThirteenthMonth(company.getId(), ctx, employees, month.getYear(), "CT",
                    new BigDecimal("0.02"));
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
}

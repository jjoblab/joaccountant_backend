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
 * V8.1 — PME 2 : Moïse & Associés Conseil S.A. (services pro Port-au-Prince).
 *
 * <p>8 consultants, ~18M HTG/an, TVA 10% + TCA 10% cumulatives, RS 2% retenue par clients.
 */
@Component
public class ProfessionalServicesSeeder extends AbstractCompanySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ProfessionalServicesSeeder.class);
    private static final UUID PCN_HAITI_FRAMEWORK_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000005");

    private final CompanyRepository companyRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final EmployeeRepository employeeRepository;
    private final SalesInvoiceRepository invoiceRepository;

    public ProfessionalServicesSeeder(CompanyRepository companyRepository,
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
    public String demoCode() { return "MOISE_ASSOCIES"; }

    @Override
    public String companyName() { return "Moïse & Associés Conseil S.A."; }

    @Override
    public String segment() { return "PROFESSIONAL_SERVICES"; }

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
        company.setFunctionalCurrency("HTG");
        company.setNif("2020202020MA");
        company.setAddress("Rue Capois, Port-au-Prince");
        company.setSector(Sector.CABINET_COMPTABLE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setBusinessTypeCode("PROFESSIONAL_SERVICES");
        company.setPrimaryActivityLabel("Cabinet de conseil en management et comptabilité");
        company.setAccountingFrameworkId(PCN_HAITI_FRAMEWORK_ID);
        company.setFiscalYearStartMonth(10);
        company.setFreeZone(false);
        company.setTaxExemptionStatus(TaxExemptionStatus.STANDARD);
        company.setMonthlyLegalHours(new BigDecimal("208"));
        company.setWizardStep(9);
        company.setWizardCompleted(true); company.setWizardStep(4);
        company.setIsDemo(true);
        company = companyRepository.save(company);

        TenantContext.setCompanyId(company.getId());
        int count = 1;
        try {
        ResolvedContext ctx = dataContext.forCompany(company);

        // 8 consultants
        List<ThirdParty> empTps = new ArrayList<>();
        List<Employee> employees = new ArrayList<>();
        Object[][] specs = {
            {"Frantz Moïse", "Associé gérant", "Direction", new BigDecimal("200000"), "BANK"},
            {"Nadège Saintilus", "Manager", "Conseil", new BigDecimal("120000"), "BANK"},
            {"Junior Consultant 1", "Consultant", "Conseil", new BigDecimal("60000"), "BANK"},
            {"Junior Consultant 2", "Consultant", "Conseil", new BigDecimal("55000"), "BANK"},
            {"Junior Consultant 3", "Consultant", "Audit", new BigDecimal("70000"), "BANK"},
            {"Consultant Senior", "Senior", "Conseil", new BigDecimal("90000"), "BANK"},
            {"Consultant Non-Résident US", "Consultant", "International", new BigDecimal("8000"), "BANK"},
            {"Consultant Non-Résident CA", "Consultant", "International", new BigDecimal("7500"), "BANK"}
        };
        for (int i = 0; i < specs.length; i++) {
            if (ctx.employeeCollectiveAccount == null) break;
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.EMPLOYEE)
                .name((String) specs[i][0])
                .collectiveAccountId(ctx.employeeCollectiveAccount.getId())
                .build();
            empTps.add(thirdPartyRepository.save(tp));
            Employee emp = new EmployeeBuilder()
                .thirdPartyId(tp.getId())
                .employeeNumber("MA-" + String.format("%03d", i + 1))
                .position((String) specs[i][1])
                .department((String) specs[i][2])
                .hireDate(LocalDate.of(2015 + i, 3, 1))
                .baseSalary((BigDecimal) specs[i][3])
                .salaryCurrency(i >= 6 ? "USD" : "HTG")
                .ofatmaSectorCode((String) specs[i][4])
                .build();
            employees.add(employeeRepository.save(emp));
        }
        count += employees.size() * 2;

        // 20 clients entreprises
        List<ThirdParty> clients = new ArrayList<>();
        String[] clientNames = {
            "Banque Nationale de Crédit (BNC)", "Sogebank", "Capital Bank", "Unibank",
            "BRH", "Ministère du Commerce", "USAID Haïti", "DIGICEL", "NATCOM",
            "Ayibobo SA", "Total Haïti", "SOGASUR", "Ciment d'Haïti", "J. Raymond SA",
            "BRH Foundation", "ANAPH", "Mairie de PAP", "EDH", "SNEP", "DGI"
        };
        for (int i = 0; i < clientNames.length; i++) {
            if (ctx.clientCollectiveAccount == null) break;
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.CLIENT)
                .name(clientNames[i])
                .collectiveAccountId(ctx.clientCollectiveAccount.getId())
                .nif(String.format("%010d", 2000000000 + i) + "MA")
                .address("Port-au-Prince")
                .build();
            clients.add(thirdPartyRepository.save(tp));
        }
        count += clients.size();

        // 24 mois de données
        LocalDate start = LocalDate.of(2024, 10, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        LocalDate month = start;
        int invCounter = 1;
        while (!month.isAfter(end)) {
            // 10 factures/mois (services pro)
            for (int i = 0; i < 10; i++) {
                ThirdParty client = clients.get((int) (Math.random() * clients.size()));
                BigDecimal subtotal = new BigDecimal(80000 + (int) (Math.random() * 200000))
                    .setScale(2, RoundingMode.HALF_UP);
                BigDecimal tva = subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
                BigDecimal tca = subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
                BigDecimal total = subtotal.add(tva).add(tca);
                SalesInvoice invoice = new InvoiceBuilder()
                    .thirdPartyId(client.getId())
                    .status(InvoiceStatus.ISSUED)
                    .invoiceNumber("MA-VT-" + String.format("%06d", invCounter++))
                    .issueDate(month.plusDays((int) (Math.random() * 28)))
                    .dueDate(month.plusDays(60))
                    .currency("HTG")
                    .subtotal(subtotal)
                    .taxAmount(tva.add(tca))
                    .totalAmount(total)
                    .build();
                invoiceRepository.save(invoice);
                count++;

                // Écriture D 411 / C 706 / C 443 TVA / C 4458 TCA
                if (ctx.journalVT != null && ctx.clientCollectiveAccount != null && ctx.salesAccount != null) {
                    createJournalEntry(company.getId(), ctx, ctx.journalVT.getId(),
                        invoice.getIssueDate(), "Prestation " + invoice.getInvoiceNumber(),
                        new Object[][]{
                            {ctx.clientCollectiveAccount, total, BigDecimal.ZERO, client.getId()},
                            {ctx.salesAccount, BigDecimal.ZERO, subtotal, null},
                            {ctx.vatCollectedAccount, BigDecimal.ZERO, tva, null},
                            {ctx.vatCollectedAccount, BigDecimal.ZERO, tca, null}  // TCA simplifié
                        });
                }
            }
            // Paie mensuelle
            count += createMonthlyPayroll(company.getId(), ctx, employees, month, "MA",
                new BigDecimal("0.06"), new BigDecimal("0.01"), new BigDecimal("0.02"));
            // 13e mois en décembre
            if (month.getMonthValue() == 12) {
                count += createThirteenthMonth(company.getId(), ctx, employees, month.getYear(), "MA",
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

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
import jo.accountant.demo.builders.GrantBuilder;
import jo.accountant.demo.builders.ThirdPartyBuilder;
import jo.accountant.demo.fixtures.HaitianNames;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.fundsgrants.entity.Grant;
import jo.accountant.fundsgrants.entity.RestrictionType;
import jo.accountant.fundsgrants.repository.GrantRepository;
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
 * V8.1 — PME 3 : Espwa pou Ayiti ONG (humanitaire Port-au-Prince).
 *
 * <p>35 employés, ~60M HTG/an (~5M USD), USD, IS 0% NGO_EXEMPT (Code Fiscal art. 195).
 * 4 bailleurs : USAID, EU, BM, CRS.
 */
@Component
public class NgoHumanitarianSeeder extends AbstractCompanySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(NgoHumanitarianSeeder.class);
    private static final UUID PCN_HAITI_FRAMEWORK_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000005");

    private final CompanyRepository companyRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final EmployeeRepository employeeRepository;
    private final GrantRepository grantRepository;

    public NgoHumanitarianSeeder(CompanyRepository companyRepository,
                                   ThirdPartyRepository thirdPartyRepository,
                                   EmployeeRepository employeeRepository,
                                   GrantRepository grantRepository,
                                   PayrollRunRepository payrollRunRepository,
                                   PayslipRepository payslipRepository,
                                   JournalEntryRepository journalEntryRepository,
                                   JournalLineRepository journalLineRepository,
                                   DemoDataContext dataContext) {
        super(payrollRunRepository, payslipRepository, journalEntryRepository, journalLineRepository, dataContext);
        this.companyRepository = companyRepository;
        this.thirdPartyRepository = thirdPartyRepository;
        this.employeeRepository = employeeRepository;
        this.grantRepository = grantRepository;
    }

    @Override
    public String demoCode() { return "ESPWA_POU_AYITI"; }

    @Override
    public String companyName() { return "Espwa pou Ayiti"; }

    @Override
    public String segment() { return "NGO_HUMANITARIAN"; }

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
        company.setLegalForm(LegalForm.NGO);
        company.setCountry("HT");
        company.setFunctionalCurrency("USD");
        company.setNif("3030303030EP");
        company.setAddress("Delmas 33, Port-au-Prince");
        company.setSector(Sector.ONG_HUMANITAIRE);
        company.setOrganizationNature(OrganizationNature.NON_PROFIT);
        company.setBusinessTypeCode("NGO_HUMANITARIAN");
        company.setPrimaryActivityLabel("ONG de développement communautaire — santé, éducation, rural");
        company.setAccountingFrameworkId(PCN_HAITI_FRAMEWORK_ID);
        company.setFiscalYearStartMonth(10);
        company.setFreeZone(false);
        company.setTaxExemptionStatus(TaxExemptionStatus.NGO_EXEMPT);
        company.setMonthlyLegalHours(new BigDecimal("208"));
        company.setWizardStep(9);
        company.setWizardCompleted(true); company.setWizardStep(4);
        company.setIsDemo(true);
        company = companyRepository.save(company);

        TenantContext.setCompanyId(company.getId());
        int count = 1;
        try {
        ResolvedContext ctx = dataContext.forCompany(company);

        // 4 bailleurs (ThirdParty DONOR)
        List<ThirdParty> donors = new ArrayList<>();
        String[] donorNames = {"USAID Haïti", "European Union Delegation", "World Bank Haïti", "Catholic Relief Services"};
        for (int i = 0; i < donorNames.length; i++) {
            if (ctx.clientCollectiveAccount == null) break;
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.DONOR)
                .name(donorNames[i])
                .collectiveAccountId(ctx.clientCollectiveAccount.getId())
                .address("Port-au-Prince")
                .build();
            donors.add(thirdPartyRepository.save(tp));
        }
        count += donors.size();

        // 4 grants
        Object[][] grantSpecs = {
            {"USAID-2024-HEALTH-001", "Health Strengthening Program", new BigDecimal("2500000"), "USD", 0},
            {"EU-2025-EDUC-002", "Education for All", new BigDecimal("1800000"), "USD", 1},
            {"BM-2024-RURAL-003", "Rural Development", new BigDecimal("1200000"), "USD", 2},
            {"CRS-2025-EMERG-004", "Emergency Response", new BigDecimal("500000"), "USD", 3}
        };
        for (Object[] spec : grantSpecs) {
            Grant grant = new GrantBuilder()
                .donorThirdPartyId(donors.get((int) spec[4]).getId())
                .code((String) spec[0])
                .label((String) spec[1])
                .totalAmount((BigDecimal) spec[2])
                .currency((String) spec[3])
                .startDate(LocalDate.of(2024, 10, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .restrictionType((int) spec[4] == 3 ? RestrictionType.UNRESTRICTED : RestrictionType.RESTRICTED)
                .build();
            grantRepository.save(grant);
            count++;
        }

        // 35 employés
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            if (ctx.employeeCollectiveAccount == null) break;
            ThirdParty tp = new ThirdPartyBuilder()
                .type(ThirdPartyType.EMPLOYEE)
                .name(HaitianNames.randomFullName())
                .collectiveAccountId(ctx.employeeCollectiveAccount.getId())
                .build();
            thirdPartyRepository.save(tp);

            BigDecimal salary;
            String position;
            if (i == 0) { salary = new BigDecimal("8000"); position = "Directrice financière"; }
            else if (i < 5) { salary = new BigDecimal("4500"); position = "Coordinateur projet"; }
            else if (i < 15) { salary = new BigDecimal("1000"); position = "Animateur terrain"; }
            else if (i < 25) { salary = new BigDecimal("2000"); position = "Logisticien"; }
            else { salary = new BigDecimal("1500"); position = "Support admin"; }

            Employee emp = new EmployeeBuilder()
                .thirdPartyId(tp.getId())
                .employeeNumber("EP-" + String.format("%03d", i + 1))
                .position(position)
                .department("ONG")
                .hireDate(LocalDate.of(2020 + (i % 5), (i % 12) + 1, 1))
                .baseSalary(salary)
                .salaryCurrency("USD")
                .ofatmaSectorCode("NGO")
                .build();
            employees.add(employeeRepository.save(emp));
        }
        count += employees.size() * 2;

        // 24 mois de données
        LocalDate start = LocalDate.of(2024, 10, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        LocalDate month = start;
        while (!month.isAfter(end)) {
            // Dépenses par grant (20 écritures/mois)
            for (int i = 0; i < 20; i++) {
                if (ctx.journalOD != null && ctx.cashAccount != null && ctx.purchaseAccount != null) {
                    BigDecimal amount = new BigDecimal(5000 + (int) (Math.random() * 45000))
                        .setScale(2, RoundingMode.HALF_UP);
                    createJournalEntry(company.getId(), ctx, ctx.journalOD.getId(),
                        month.plusDays((int) (Math.random() * 28)),
                        "Dépense projet " + month.getMonthValue() + "/" + month.getYear(),
                        new Object[][]{
                            {ctx.purchaseAccount, amount, BigDecimal.ZERO, null},
                            {ctx.cashAccount, BigDecimal.ZERO, amount, null}
                        });
                    count++;
                }
            }
            // Paie mensuelle (ONG : pas de RS, ITS peut être exonéré)
            count += createMonthlyPayroll(company.getId(), ctx, employees, month, "EP",
                new BigDecimal("0.06"), new BigDecimal("0.01"), BigDecimal.ZERO);  // ITS=0 pour ONG
            // 13e mois en décembre
            if (month.getMonthValue() == 12) {
                count += createThirteenthMonth(company.getId(), ctx, employees, month.getYear(), "EP",
                    BigDecimal.ZERO);
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

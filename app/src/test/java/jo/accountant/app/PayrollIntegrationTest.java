package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.employees.dto.CreateEmployeeRequest;
import jo.accountant.employees.dto.EmployeeResponse;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.employees.service.EmployeesService;
import jo.accountant.payroll.dto.CreatePayrollRunRequest;
import jo.accountant.payroll.dto.PayrollRunResponse;
import jo.accountant.payroll.dto.PayslipResponse;
import jo.accountant.payroll.entity.PayrollRunStatus;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.payroll.repository.PayslipRepository;
import jo.accountant.payroll.service.PayrollService;
import jo.accountant.tax.dto.CreateWithholdingRuleRequest;
import jo.accountant.tax.entity.WithholdingRule;
import jo.accountant.tax.repository.TaxRuleRepository;
import jo.accountant.tax.repository.WithholdingRuleRepository;
import jo.accountant.tax.service.TaxService;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.LettrageMatchRepository;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests d'intégration du module {@code payroll} (restructuration 2026-07-24).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, PayrollIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class PayrollIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private PayrollService service;
    @Autowired private EmployeesService employeesService;
    @Autowired private TaxService taxService;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private EmployeeRepository empRepo;
    @Autowired private PayrollRunRepository runRepo;
    @Autowired private PayslipRepository payslipRepo;
    @Autowired private TaxRuleRepository taxRuleRepo;
    @Autowired private WithholdingRuleRepository whRuleRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(UUID.randomUUID());
            payslipRepo.deleteAllInBatch();
            runRepo.deleteAllInBatch();
            empRepo.deleteAllInBatch();
            whRuleRepo.deleteAll();
            taxRuleRepo.deleteAll();
            lmRepo.deleteAllInBatch();
            tpRepo.deleteAllInBatch();
            jlRepo.deleteAllInBatch();
            jeRepo.deleteAllInBatch();
            journalRepo.deleteAllInBatch();
            fpRepo.deleteAllInBatch();
            fyRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
            docSeqCounterRepo.deleteAll();
            docSeqConfigRepo.deleteAllInBatch();
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(UUID.randomUUID());
    }

    /** Initialise le fixture et crée 2 employés + 1 règle de retenue à la source (10%). */
    private void initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveEmployee = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "421000", "Personnel - rémunérations dues", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, true, null, List.of()));
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "422000", "Personnel - avances et acomptes", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "433000", "Sécurité sociale", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "443000", "Etat - impôts et taxes", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "661000", "Rémunérations directes versées au personnel national",
            ReportingClass.CHARGES, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "PA", "Journal de paie");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "PA", "PA", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.PAYSLIP,
            "PA", "BUL", true, 6, ResetPolicy.YEARLY);

        // Créer 2 employés actifs
        ThirdPartyResponse tp1 = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.EMPLOYEE, "Employé 1", collectiveEmployee.id(), null, null));
        ThirdPartyResponse tp2 = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.EMPLOYEE, "Employé 2", collectiveEmployee.id(), null, null));
        employeesService.create(COMPANY_A, new CreateEmployeeRequest(
            tp1.id(), null, null, "EMP-001", "Comptable", "Finance",
            LocalDate.of(2020, 1, 1), new BigDecimal("50000"), "HTG",
            ContractType.PERMANENT, "BANK-001"));
        employeesService.create(COMPANY_A, new CreateEmployeeRequest(
            tp2.id(), null, null, "EMP-002", "Développeur", "IT",
            LocalDate.of(2021, 6, 1), new BigDecimal("60000"), "HTG",
            ContractType.PERMANENT, "BANK-002"));

        // Créer 1 règle de retenue à la source 10% applicable aux EMPLOYEE
        taxService.createWithholdingRule(COMPANY_A, new CreateWithholdingRuleRequest(
            "IMPOT-SAL-10", "Impôt salarial 10%", new BigDecimal("10"),
            List.of("EMPLOYEE")));
    }

    @Nested
    @DisplayName("Règle 1 — Cycle complet de paie")
    class CycleComplet {
        @Test
        @DisplayName("Créer → calculer → approuver → payer → clôturer → écriture équilibrée")
        void fullCycle() {
            initFixture();

            PayrollRunResponse run = service.create(COMPANY_A, new CreatePayrollRunRequest(
                7, 2026, new BigDecimal("14")));  // juillet 2026, 14% charges patronales
            assertThat(run.status()).isEqualTo(PayrollRunStatus.DRAFT);

            PayrollRunResponse calculated = service.calculate(COMPANY_A, run.id(), new BigDecimal("14"));
            assertThat(calculated.status()).isEqualTo(PayrollRunStatus.CALCULATED);
            assertThat(calculated.payslipCount()).isEqualTo(2);
            // Brut = 50000 + 60000 = 110000
            assertThat(calculated.totalGross()).isEqualByComparingTo("110000");
            // Retenues = 5000 (10% de 50000) + 6000 (10% de 60000) = 11000
            // Net = 110000 - 11000 = 99000
            assertThat(calculated.totalNet()).isEqualByComparingTo("99000");
            // Charges patronales = 14% de 110000 = 15400
            assertThat(calculated.totalEmployerContributions()).isEqualByComparingTo("15400");

            // Vérifier les payslips
            List<PayslipResponse> payslips = service.listPayslips(COMPANY_A, run.id());
            assertThat(payslips).hasSize(2);
            PayslipResponse ps1 = payslips.stream()
                .filter(p -> "EMP-001".equals(p.employeeNumber())).findFirst().orElseThrow();
            assertThat(ps1.grossSalary()).isEqualByComparingTo("50000");
            assertThat(ps1.deductions()).hasSize(1);
            assertThat(ps1.deductions().get(0).code()).isEqualTo("IMPOT-SAL-10");
            assertThat(ps1.deductions().get(0).amount()).isEqualByComparingTo("5000");
            assertThat(ps1.netPay()).isEqualByComparingTo("45000");

            PayrollRunResponse approved = service.approve(COMPANY_A, run.id());
            assertThat(approved.status()).isEqualTo(PayrollRunStatus.APPROVED);
            assertThat(approved.journalEntryId()).isNotNull();

            // Vérifier l'équilibre débit/crédit
            var entry = jeRepo.findById(approved.journalEntryId()).orElseThrow();
            var lines = jlRepo.findByJournalEntryIdOrderByLineNumber(entry.getId());
            BigDecimal totalDebit = lines.stream().map(jo.accountant.accountingengine.entity.JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = lines.stream().map(jo.accountant.accountingengine.entity.JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit).isEqualByComparingTo(totalCredit);
            // Total débit = brut (110000) + charges patronales (15400) = 125400
            assertThat(totalDebit).isEqualByComparingTo("125400");

            // Vérifier qu'on a bien les 4 types de lignes : charges (débit), salaires à payer x2 (crédit),
            // organismes sociaux (crédit), état (crédit)
            assertThat(lines).hasSize(5);  // 1 débit + 2 crédit salaires + 1 crédit organisme + 1 crédit état

            PayrollRunResponse paid = service.pay(COMPANY_A, run.id());
            assertThat(paid.status()).isEqualTo(PayrollRunStatus.PAID);

            PayrollRunResponse closed = service.close(COMPANY_A, run.id());
            assertThat(closed.status()).isEqualTo(PayrollRunStatus.CLOSED);

            // Vérifier que les numéros de bulletin ont été attribués
            List<PayslipResponse> finalPayslips = service.listPayslips(COMPANY_A, run.id());
            assertThat(finalPayslips).allSatisfy(p ->
                assertThat(p.payslipNumber()).isNotNull().startsWith("BUL-2026-"));
        }
    }
}

package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ValidationException;
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
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.employees.service.EmployeesService;
import jo.accountant.expenses.dto.CreateExpenseReportRequest;
import jo.accountant.expenses.dto.ExpenseReportResponse;
import jo.accountant.expenses.entity.ExpenseReportStatus;
import jo.accountant.expenses.repository.ExpenseLineRepository;
import jo.accountant.expenses.repository.ExpenseReportRepository;
import jo.accountant.expenses.service.ExpensesService;
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
 * Tests d'intégration du module {@code expenses} (restructuration 2026-07-24).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ExpensesIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ExpensesIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private ExpensesService service;
    @Autowired private EmployeesService employeesService;
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
    @Autowired private ExpenseReportRepository erRepo;
    @Autowired private ExpenseLineRepository elRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cleanupFor(COMPANY_A);
        TenantContext.clear();
    }

    private void cleanupFor(UUID companyId) {
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_X);
            elRepo.deleteAllInBatch();
            erRepo.deleteAllInBatch();
            empRepo.deleteAllInBatch();
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
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    /** Initialise le fixture et retourne l'ID du tiers employé. */
    private UUID initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveEmployee = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "421000", "Personnel - rémunérations dues", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, true, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "601000", "Achats de marchandises", ReportingClass.CHARGES,
            ReportingSubcategory.COURANT, NormalBalance.DEBIT, false, null, List.of()));
        var class5 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "5").orElseThrow();
        coaService.createChild(COMPANY_A, class5.getId(), new CreateChildRequest(
            "570000", "Caisse", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "DP", "Journal des dépenses");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "DP", "DP", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);

        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.EMPLOYEE, "Jean Employee",
            collectiveEmployee.id(), null, null));

        // Créer la fiche employé associée (requise par le module :employees)
        employeesService.create(COMPANY_A, new CreateEmployeeRequest(
            tp.id(), null, null, "EMP-001", "Comptable", "Finance",
            LocalDate.of(2020, 1, 1), new BigDecimal("50000"), "HTG",
            ContractType.PERMANENT, null));

        return tp.id();
    }

    @Nested
    @DisplayName("Règle 1 — Cycle de vie complet (paidDirectly=false)")
    class CycleRemboursement {
        @Test
        @DisplayName("Créer → soumettre → approuver → payer → écriture équilibrée")
        void fullCycle() {
            UUID empTpId = initFixture();
            ExpenseReportResponse report = service.create(COMPANY_A, new CreateExpenseReportRequest(
                empTpId, LocalDate.of(2026, 7, 15), "HTG",
                "Frais de déplacement juillet", false,
                List.of(new CreateExpenseReportRequest.LineDto(
                    "TRAVEL", "Taxi aéroport", new BigDecimal("2500"), null))));

            assertThat(report.status()).isEqualTo(ExpenseReportStatus.DRAFT);
            assertThat(report.totalAmount()).isEqualByComparingTo("2500");

            ExpenseReportResponse submitted = service.submit(COMPANY_A, report.id());
            assertThat(submitted.status()).isEqualTo(ExpenseReportStatus.SUBMITTED);

            ExpenseReportResponse approved = service.approve(COMPANY_A, report.id());
            assertThat(approved.status()).isEqualTo(ExpenseReportStatus.APPROVED);
            assertThat(approved.journalEntryId()).isNotNull();

            // Vérifier l'équilibre débit/crédit
            var entry = jeRepo.findById(approved.journalEntryId()).orElseThrow();
            var lines = jlRepo.findByJournalEntryIdOrderByLineNumber(entry.getId());
            BigDecimal totalDebit = lines.stream().map(jo.accountant.accountingengine.entity.JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = lines.stream().map(jo.accountant.accountingengine.entity.JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit).isEqualByComparingTo(totalCredit);
            assertThat(totalDebit).isEqualByComparingTo("2500");

            ExpenseReportResponse paid = service.pay(COMPANY_A, report.id());
            assertThat(paid.status()).isEqualTo(ExpenseReportStatus.PAID);
        }
    }

    @Nested
    @DisplayName("Règle 2 — paidDirectly=true crédite la trésorerie")
    class PaidDirectly {
        @Test
        @DisplayName("paidDirectly=true → crédit 570000 (Caisse)")
        void paidDirectlyCreditsCashAccount() {
            UUID empTpId = initFixture();
            ExpenseReportResponse report = service.create(COMPANY_A, new CreateExpenseReportRequest(
                null, LocalDate.of(2026, 7, 15), "HTG",
                "Achat fournitures bureau", true,
                List.of(new CreateExpenseReportRequest.LineDto(
                    "SUPPLIES", "Stylos et cahiers", new BigDecimal("1500"), null))));

            ExpenseReportResponse submitted = service.submit(COMPANY_A, report.id());
            ExpenseReportResponse approved = service.approve(COMPANY_A, report.id());
            assertThat(approved.journalEntryId()).isNotNull();

            var entry = jeRepo.findById(approved.journalEntryId()).orElseThrow();
            var lines = jlRepo.findByJournalEntryIdOrderByLineNumber(entry.getId());
            // Vérifier qu'une ligne crédit 570000 existe
            boolean hasCashCredit = lines.stream().anyMatch(l ->
                "570000".equals(l.getAccountCode()) && l.getCredit().compareTo(new BigDecimal("1500")) == 0);
            assertThat(hasCashCredit).as("La trésorerie (570000) doit être créditée de 1500").isTrue();
        }
    }

    @Nested
    @DisplayName("Règle 3 — Soumettre une note à rembourser sans employé → 422")
    class ValidationEmployeRequis {
        @Test
        @DisplayName("paidDirectly=false + thirdPartyId=null → 422 EMPLOYEE_REQUIRED_FOR_REIMBURSEMENT")
        void reimbursementRequiresEmployee() {
            initFixture();
            ExpenseReportResponse report = service.create(COMPANY_A, new CreateExpenseReportRequest(
                null, LocalDate.of(2026, 7, 15), "HTG",
                "Note sans employé", false,
                List.of(new CreateExpenseReportRequest.LineDto(
                    "OTHER", "Test", new BigDecimal("100"), null))));

            assertThatThrownBy(() -> service.submit(COMPANY_A, report.id()))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("EMPLOYEE_REQUIRED_FOR_REIMBURSEMENT");
        }
    }
}

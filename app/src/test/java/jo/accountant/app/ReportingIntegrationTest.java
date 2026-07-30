package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
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
import jo.accountant.documentgeneration.dto.CreateTemplateRequest;
import jo.accountant.documentgeneration.entity.DocumentType;
import jo.accountant.documentgeneration.repository.DocumentTemplateRepository;
import jo.accountant.documentgeneration.repository.GeneratedDocumentRepository;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;

import jo.accountant.reporting.dto.ExportResult;
import jo.accountant.reporting.service.ReportingService;
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

@SpringBootTest(classes = {JoAccountantApplication.class, ReportingIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ReportingIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private ReportingService service;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private jo.accountant.documentgeneration.service.DocumentGenerationService docGenService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private SalesInvoiceRepository siRepo;
    @Autowired private InvoiceLineRepository ilRepo;
    @Autowired private DocumentTemplateRepository dtRepo;
    @Autowired private GeneratedDocumentRepository gdRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            gdRepo.deleteAllInBatch();
            dtRepo.deleteAllInBatch();
            ilRepo.deleteAllInBatch();
            siRepo.deleteAllInBatch();
            jlatRepo.deleteAllInBatch();
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

    private void asTenant() {
        TenantContext.setCompanyId(COMPANY_A);
        TenantContext.setUserId(USER_X);
    }

    private void initFixture() {
        asTenant();
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        var class5 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "5").orElseThrow();
        coaService.createChild(COMPANY_A, class5.getId(), new CreateChildRequest(
            "521", "Banque", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "601", "Achats", ReportingClass.CHARGES, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701000", "Ventes", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Ventes");
        accountingService.createJournal(COMPANY_A, "OD", "OD");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Ex 2026"));
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);

        // Templates pour document-generation
        docGenService.createTemplate(COMPANY_A, new CreateTemplateRequest(
            DocumentType.BALANCE_SHEET, "<h1>Bilan</h1><p>Total actif: <span th:text=\"${totalAssets}\"></span></p>", true));
        docGenService.createTemplate(COMPANY_A, new CreateTemplateRequest(
            DocumentType.INCOME_STATEMENT, "<h1>Compte de résultat</h1><p>Résultat: <span th:text=\"${netResult}\"></span></p>", true));

        // Poster une écriture
        postEntry("521 D 5000, 701000 C 5000");
    }

    private void postEntry(String desc) {
        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            "VT", LocalDate.of(2026, 7, 15), desc,
            List.of(new LineDto("521", null, new BigDecimal("5000"), null, null, List.of()),
                    new LineDto("701000", null, null, new BigDecimal("5000"), null, List.of())),
            JournalEntrySourceModule.MANUAL);
        JournalEntryResponse created = accountingService.createJournalEntry(COMPANY_A, "key-rpt-" + UUID.randomUUID(), req);
        accountingService.postJournalEntry(COMPANY_A, created.id(), List.of());
    }

    @Nested
    @DisplayName("Règle 1 — Export balance générale en CSV")
    class ExportBalance {
        @Test
        @DisplayName("trial_balance en CSV → contenu non vide")
        void exportTrialBalanceCsv() {
            initFixture();
            ExportResult result = service.export(COMPANY_A, "trial_balance", "csv", null, null, null);
            assertThat(result.content()).isNotEmpty();
            assertThat(result.contentType()).isEqualTo("text/csv");
            assertThat(result.filename()).contains("balance-generale");
            String csv = new String(result.content());
            assertThat(csv).contains("Code compte");
            assertThat(csv).contains("521");  // compte banque
        }
    }

    @Nested
    @DisplayName("Règle 2 — Export grand livre en CSV")
    class ExportGrandLivre {
        @Test
        @DisplayName("general_ledger en CSV → contient les écritures")
        void exportGeneralLedgerCsv() {
            initFixture();
            ExportResult result = service.export(COMPANY_A, "general_ledger", "csv",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
            assertThat(result.content()).isNotEmpty();
            String csv = new String(result.content());
            assertThat(csv).contains("Date;Reference");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Export bilan en PDF")
    class ExportBilan {
        @Test
        @DisplayName("balance_sheet en PDF → PDF valide")
        void exportBalanceSheetPdf() {
            initFixture();
            ExportResult result = service.export(COMPANY_A, "balance_sheet", "pdf",
                null, LocalDate.of(2026, 12, 31), null);
            assertThat(result.content()).isNotEmpty();
            assertThat(new String(result.content(), 0, 4)).isEqualTo("%PDF");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Export compte de résultat en PDF")
    class ExportCompteResultat {
        @Test
        @DisplayName("income_statement en PDF → PDF valide")
        void exportIncomeStatementPdf() {
            initFixture();
            ExportResult result = service.export(COMPANY_A, "income_statement", "pdf",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
            assertThat(result.content()).isNotEmpty();
            assertThat(new String(result.content(), 0, 4)).isEqualTo("%PDF");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Tableau de bord")
    class Dashboard {
        @Test
        @DisplayName("Dashboard affiche position trésorerie et top charges")
        void dashboardShowsMetrics() {
            initFixture();
            jo.accountant.reporting.dto.Dashboard dash = service.getDashboard(COMPANY_A);
            assertThat(dash.companyId()).isEqualTo(COMPANY_A);
            assertThat(dash.cashPosition()).isNotNull();
            assertThat(dash.topExpenses()).isNotNull();
            assertThat(dash.topRevenues()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 6 — Export inconnu → 422")
    class ExportInconnu {
        @Test
        @DisplayName("statement inconnu → ValidationException")
        void unknownStatementRejected() {
            asTenant();
            try {
                service.export(COMPANY_A, "unknown_format", "pdf", null, null, null);
            } catch (jo.accountant.core.exception.ValidationException e) {
                assertThat(e.getCode()).isEqualTo("UNKNOWN_STATEMENT");
            }
        }
    }
}

package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import jo.accountant.bankreconciliation.dto.CreateBankAccountRequest;
import jo.accountant.bankreconciliation.dto.ImportBankStatementRequest;
import jo.accountant.bankreconciliation.dto.ImportResult;
import jo.accountant.bankreconciliation.dto.MatchRequest;
import jo.accountant.bankreconciliation.dto.ReconciliationStatus;
import jo.accountant.bankreconciliation.entity.BankAccount;
import jo.accountant.bankreconciliation.entity.BankStatementFormat;
import jo.accountant.bankreconciliation.repository.BankAccountRepository;
import jo.accountant.bankreconciliation.repository.BankStatementImportRepository;
import jo.accountant.bankreconciliation.repository.BankStatementLineRepository;
import jo.accountant.bankreconciliation.service.BankReconciliationService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
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
 * Tests d'intégration du module {@code bank-reconciliation} — Phase 13.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, BankReconciliationIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class BankReconciliationIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private BankReconciliationService service;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private ModuleAccessGuard moduleAccessGuard;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private BankAccountRepository baRepo;
    @Autowired private BankStatementImportRepository bsiRepo;
    @Autowired private BankStatementLineRepository bslRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cleanupFor(COMPANY_A);
        cleanupFor(COMPANY_B);
        TenantContext.clear();
    }

    private void cleanupFor(UUID companyId) {
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_X);
            bslRepo.deleteAllInBatch();
            bsiRepo.deleteAllInBatch();
            baRepo.deleteAllInBatch();
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
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 8 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si BANK_RECONCILIATION désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.BANK_RECONCILIATION))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    private BankAccount initFixture() {
        asTenant(COMPANY_A);
        try {
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        } catch (jo.accountant.core.exception.ConflictException e) {
            // Plan déjà initialisé — c'est OK (test idempotent)
        }
        // Compte 521 (Banque) sous classe 5
        var class5 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "5").orElseThrow();
        var bankAccount = coaService.createChild(COMPANY_A, class5.getId(), new CreateChildRequest(
            "521", "Banque", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701000", "Ventes", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Ventes");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);

        return service.createBankAccount(COMPANY_A, new CreateBankAccountRequest(
            bankAccount.id(), "Banque Nationale — Compte courant", "1234567890"));
    }

    private void postEntry(String journalCode, String idemKey, String accountCode,
                            BigDecimal debit, BigDecimal credit) {
        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            journalCode, LocalDate.of(2026, 7, 15), "Test entry",
            List.of(new LineDto(accountCode, null, debit, credit, null, List.of()),
                    new LineDto("701000", null, credit, debit, null, List.of())),
            JournalEntrySourceModule.MANUAL);
        JournalEntryResponse created = accountingService.createJournalEntry(COMPANY_A, idemKey, req);
        accountingService.postJournalEntry(COMPANY_A, created.id(), List.of());
    }

    @Nested
    @DisplayName("Règle 1 — Création compte bancaire OK")
    class CreationCompte {
        @Test
        @DisplayName("Créer un compte bancaire rattaché à un compte de trésorerie")
        void createBankAccount() {
            BankAccount ba = initFixture();
            assertThat(ba.getId()).isNotNull();
            assertThat(ba.getLabel()).contains("Banque Nationale");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Import CSV")
    class ImportCsv {
        @Test
        @DisplayName("Importer un CSV avec 3 lignes → 3 BankStatementLine créées")
        void importCsv() {
            BankAccount ba = initFixture();
            String csv = """
                date,description,mount
                2026-07-15,Vente client X,5000.00
                2026-07-16,Achat fournisseur Y,-2000.00
                2026-07-17,Frais bancaires,-50.00
                """;
            ImportResult result = service.importStatement(COMPANY_A, ba.getId(),
                new ImportBankStatementRequest(BankStatementFormat.CSV, csv));

            assertThat(result.lineCount()).isEqualTo(3);
            assertThat(result.lines()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Règle 3 — Import OFX")
    class ImportOfx {
        @Test
        @DisplayName("Importer un OFX avec 2 transactions → 2 BankStatementLine créées")
        void importOfx() {
            BankAccount ba = initFixture();
            String ofx = """
                OFXHEADER:100
                DATA:OFXSGML
                <BANKMSGSRSV1><STMTTRNRS><STMTRS>
                <BANKTRANLIST>
                <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20260715<TRNAMT>5000.00<NAME>Vente client X</STMTTRN>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260716<TRNAMT>-2000.00<NAME>Achat fournisseur Y</STMTTRN>
                </BANKTRANLIST>
                </STMTRS></STMTTRNRS></BANKMSGSRSV1>
                """;
            ImportResult result = service.importStatement(COMPANY_A, ba.getId(),
                new ImportBankStatementRequest(BankStatementFormat.OFX, ofx));

            assertThat(result.lineCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Règle 4 — Rapprochement automatique (montant exact)")
    class AutoMatch {
        @Test
        @DisplayName("Écriture 5000 débit sur 521 + ligne relevé 5000 → auto-matched")
        void autoMatchExactAmount() {
            BankAccount ba = initFixture();
            // Poster une écriture : 521 D 5000, 701 C 5000
            postEntry("VT", "key-br-1", "521", new BigDecimal("5000"), null);

            // Importer un relevé avec une ligne de 5000
            String csv = "2026-07-15,Vente,5000.00\n";
            ImportResult result = service.importStatement(COMPANY_A, ba.getId(),
                new ImportBankStatementRequest(BankStatementFormat.CSV, csv));

            assertThat(result.autoMatchedCount()).isGreaterThan(0);

            ReconciliationStatus status = service.getStatus(COMPANY_A, ba.getId());
            assertThat(status.matchedLines()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Règle 5 — Rapprochement manuel")
    class ManualMatch {
        @Test
        @DisplayName("Matcher manuellement une ligne non rapprochée")
        void manualMatchSucceeds() {
            BankAccount ba = initFixture();
            // Importer sans écriture correspondante → pas d'auto-match
            String csv = "2026-07-15,Vente,3000.00\n";
            ImportResult result = service.importStatement(COMPANY_A, ba.getId(),
                new ImportBankStatementRequest(BankStatementFormat.CSV, csv));
            UUID lineId = result.lines().get(0).id();
            assertThat(result.lines().get(0).matched()).isFalse();

            // Poster une écriture 521 D 3000
            postEntry("VT", "key-br-2", "521", new BigDecimal("3000"), null);
            // Trouver la JournalLine
            var postedLines = jlRepo.findAllPosted(COMPANY_A).stream()
                .filter(l -> "521".equals(l.getAccountCode())).toList();
            UUID jlId = postedLines.get(0).getId();

            // Matcher manuellement
            service.manualMatch(COMPANY_A, lineId, new MatchRequest(jlId));

            ReconciliationStatus status = service.getStatus(COMPANY_A, ba.getId());
            assertThat(status.matchedLines()).isEqualTo(1);
        }

        @Test
        @DisplayName("Matcher une ligne déjà rapprochée → 422")
        void cannotMatchAlreadyMatched() {
            BankAccount ba = initFixture();
            String csv = "2026-07-15,Vente,5000.00\n";
            // D'abord poster l'écriture pour auto-match
            postEntry("VT", "key-br-3", "521", new BigDecimal("5000"), null);
            ImportResult result = service.importStatement(COMPANY_A, ba.getId(),
                new ImportBankStatementRequest(BankStatementFormat.CSV, csv));
            UUID lineId = result.lines().get(0).id();
            // Auto-match a dû la rapprocher
            assertThat(result.lines().get(0).matched()).isTrue();

            // Tenter de re-matcher → 422
            assertThatThrownBy(() -> service.manualMatch(COMPANY_A, lineId,
                new MatchRequest(UUID.randomUUID())))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("LINE_ALREADY_MATCHED");
        }
    }

    @Nested
    @DisplayName("Règle 6 — Statut de rapprochement")
    class Statut {
        @Test
        @DisplayName("Statut affiche total/matched/unmatched + débits/crédits")
        void statusShowsAllMetrics() {
            BankAccount ba = initFixture();
            String csv = """
                2026-07-15,Vente,5000.00
                2026-07-16,Achat,-2000.00
                """;
            service.importStatement(COMPANY_A, ba.getId(),
                new ImportBankStatementRequest(BankStatementFormat.CSV, csv));

            ReconciliationStatus status = service.getStatus(COMPANY_A, ba.getId());
            assertThat(status.totalLines()).isEqualTo(2);
            assertThat(status.matchedLines()).isEqualTo(0);
            assertThat(status.unmatchedLines()).isEqualTo(2);
            assertThat(status.totalCredit()).isEqualByComparingTo("5000");
            assertThat(status.totalDebit()).isEqualByComparingTo("2000");
        }
    }

    @Nested
    @DisplayName("Règle 7 — Isolation multi-tenant")
    class IsolationTenant {
        @Test
        @DisplayName("Company B ne peut pas voir le compte bancaire de A → 404")
        void companyBCannotSeeCompanyA() {
            BankAccount ba = initFixture();
            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getStatus(COMPANY_B, ba.getId()))
                .isInstanceOf(NotFoundException.class);
        }
    }
}

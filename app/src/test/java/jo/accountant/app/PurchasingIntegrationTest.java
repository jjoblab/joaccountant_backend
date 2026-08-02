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
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.purchasing.dto.CreatePurchaseInvoiceRequest;
import jo.accountant.purchasing.dto.PurchaseInvoiceResponse;
import jo.accountant.purchasing.dto.RecordPurchasePaymentRequest;
import jo.accountant.purchasing.entity.PurchaseInvoiceStatus;
import jo.accountant.purchasing.repository.PurchaseInvoiceLineRepository;
import jo.accountant.purchasing.repository.PurchaseInvoiceRepository;
import jo.accountant.purchasing.service.PurchasingService;
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
 * Tests d'intégration du module {@code purchasing} (restructuration 2026-07-24).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, PurchasingIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class PurchasingIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private PurchasingService service;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private ModuleAccessGuard moduleAccessGuard;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private PurchaseInvoiceRepository piRepo;
    @Autowired private PurchaseInvoiceLineRepository pilRepo;
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
            pilRepo.deleteAllInBatch();
            piRepo.deleteAllInBatch();
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

    private UUID initFixture() {
        asTenant(COMPANY_A);
        try {
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        } catch (jo.accountant.core.exception.ConflictException ex) {
            // CHART_OF_ACCOUNTS_ALREADY_INITIALIZED — idempotent (cleanup between tests peut laisser des résidus)
        }

        // Compte collectif fournisseurs (401000) + compte de charges (601000) + TVA déductible (445000)
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveSupplier = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "401000", "Fournisseurs", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, true, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "601000", "Achats de marchandises", ReportingClass.CHARGES,
            ReportingSubcategory.COURANT, NormalBalance.DEBIT, false, null, List.of()));
        var class4Again = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4Again.getId(), new CreateChildRequest(
            "445000", "TVA déductible", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        safeCreateJournal("AC", "Journal des achats");
        safeCreateJournal("OD", "Opérations diverses");
        safeCreateFiscalYear();

        safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY, "AC", "AC");
        safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY, "OD", "OD");
        safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType.PURCHASE_INVOICE, "AC", "FAC");

        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.SUPPLIER, "Fournisseur Test SARL",
            collectiveSupplier.id(), "supplier@test.dev", null));
        return tp.id();
    }

    /** Crée un journal en idempotent — ignore le ConflictException si le journal existe déjà. */
    private void safeCreateJournal(String code, String label) {
        try {
            accountingService.createJournal(COMPANY_A, code, label);
        } catch (ConflictException ex) {
            // JOURNAL_CODE_ALREADY_EXISTS — idempotent
        }
    }

    /** Crée un exercice fiscal en idempotent. */
    private void safeCreateFiscalYear() {
        try {
            accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));
        } catch (ConflictException ex) {
            // FISCAL_YEAR_ALREADY_EXISTS — idempotent
        }
    }

    /** Crée une séquence de numérotation en idempotente. */
    private void safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType type,
                                       String scopeKey, String prefix) {
        try {
            docNumberingService.createSequence(COMPANY_A, type, scopeKey, prefix, true, 6, ResetPolicy.YEARLY);
        } catch (ConflictException ex) {
            // SEQUENCE_ALREADY_EXISTS — idempotent
        }
    }

    private CreatePurchaseInvoiceRequest standardInvoice(UUID supplierTpId) {
        return new CreatePurchaseInvoiceRequest(
            supplierTpId, null, "SUP-REF-001",
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
            "HTG",
            List.of(new CreatePurchaseInvoiceRequest.LineDto(
                "Achat de marchandises", new BigDecimal("10"), new BigDecimal("1000"),
                new BigDecimal("15"), null)));
    }

    @Nested
    @DisplayName("Règle 1 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si PURCHASING désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.PURCHASING))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Création et cycle de vie")
    class CreationEtCycle {
        @Test
        @DisplayName("Créer → recevoir → payer → équilibre débit/crédit")
        void fullCycle() {
            UUID supplierTpId = initFixture();

            // Le module PURCHASING n'est pas activé pour COMPANY_A (pas de wizard exécuté
            // dans ce test), mais le service lui-même n'appelle pas moduleAccessGuard —
            // c'est le contrôleur qui le fait. On exerce donc le service directement.

            PurchaseInvoiceResponse inv = service.createPurchaseInvoice(COMPANY_A, standardInvoice(supplierTpId));
            assertThat(inv.status()).isEqualTo(PurchaseInvoiceStatus.DRAFT);
            assertThat(inv.invoiceNumber()).isNull();
            assertThat(inv.subtotal()).isEqualByComparingTo("10000");
            assertThat(inv.taxAmount()).isEqualByComparingTo("1500");
            assertThat(inv.totalAmount()).isEqualByComparingTo("11500");

            PurchaseInvoiceResponse received = service.receive(COMPANY_A, inv.id());
            assertThat(received.status()).isEqualTo(PurchaseInvoiceStatus.RECEIVED);
            assertThat(received.invoiceNumber()).isNotNull().startsWith("FAC-2026-");
            assertThat(received.journalEntryId()).isNotNull();

            // Vérifier l'équilibre débit/crédit de l'écriture générée
            var entry = jeRepo.findById(received.journalEntryId()).orElseThrow();
            var lines = jlRepo.findByJournalEntryIdOrderByLineNumber(entry.getId());
            BigDecimal totalDebit = lines.stream().map(jo.accountant.accountingengine.entity.JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = lines.stream().map(jo.accountant.accountingengine.entity.JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit).isEqualByComparingTo(totalCredit);
            // Total = 11500 (achats 10000 + TVA 1500) = crédit fournisseur 11500
            assertThat(totalDebit).isEqualByComparingTo("11500");

            PurchaseInvoiceResponse paid = service.recordPayment(COMPANY_A, inv.id(),
                new RecordPurchasePaymentRequest(new BigDecimal("11500")));
            assertThat(paid.status()).isEqualTo(PurchaseInvoiceStatus.PAID);
            assertThat(paid.balanceDue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Recevoir une facture non DRAFT → 409 PURCHASE_INVOICE_NOT_DRAFT")
        void cannotReceiveAlreadyReceived() {
            UUID supplierTpId = initFixture();
            PurchaseInvoiceResponse inv = service.createPurchaseInvoice(COMPANY_A, standardInvoice(supplierTpId));
            service.receive(COMPANY_A, inv.id());
            assertThatThrownBy(() -> service.receive(COMPANY_A, inv.id()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("PURCHASE_INVOICE_NOT_DRAFT");
        }
    }
}

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
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
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
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.RecordPaymentRequest;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.invoicing.service.InvoicingService;
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
 * Tests d'intégration du module {@code invoicing} — Phase 12.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, InvoicingIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class InvoicingIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private InvoicingService service;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private jo.accountant.documentgeneration.service.DocumentGenerationService docGenService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
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
        cleanupFor(COMPANY_A);
        cleanupFor(COMPANY_B);
        TenantContext.clear();
    }

    private void cleanupFor(UUID companyId) {
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_X);
            ilRepo.deleteAllInBatch();
            siRepo.deleteAllInBatch();
            gdRepo.deleteAllInBatch();
            dtRepo.deleteAllInBatch();
            lmRepo.deleteAllInBatch();
            tpRepo.deleteAllInBatch();
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

    private UUID initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveClient = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701000", "Ventes de marchandises", ReportingClass.PRODUITS,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        var class4Again = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4Again.getId(), new CreateChildRequest(
            "443000", "TVA collectée", ReportingClass.PASSIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Journal des ventes");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.SALES_INVOICE,
            "VT", "FAC", true, 6, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.CREDIT_NOTE,
            "VT", "AV", true, 6, ResetPolicy.YEARLY);

        // Template de facture
        docGenService.createTemplate(COMPANY_A, new CreateTemplateRequest(
            DocumentType.INVOICE, "<h1>FACTURE</h1><p>Numéro: <span th:text=\"${invoiceNumber}\"></span></p>", true));

        // Créer un tiers client
        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.CLIENT, "Boutique Pétion-Ville",
            collectiveClient.id(), "client@test.dev", null));
        return tp.id();
    }

    private CreateInvoiceRequest standardInvoice(UUID thirdPartyId) {
        return new CreateInvoiceRequest(
            thirdPartyId, InvoiceType.STANDARD,
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
            "HTG",
            List.of(new CreateInvoiceRequest.LineDto(
                "Vente de marchandises", new BigDecimal("10"), new BigDecimal("1000"),
                BigDecimal.ZERO, new BigDecimal("15"), null, null)),
            null);
    }

    @Nested
    @DisplayName("Règle 1 — Création de facture DRAFT")
    class CreationFacture {
        @Test
        @DisplayName("Créer une facture DRAFT avec 1 ligne")
        void createDraftInvoice() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            assertThat(inv.id()).isNotNull();
            assertThat(inv.status()).isEqualTo(InvoiceStatus.DRAFT);
            assertThat(inv.invoiceNumber()).isNull();
            assertThat(inv.subtotal()).isEqualByComparingTo("10000.0000");
            assertThat(inv.taxAmount()).isEqualByComparingTo("1500.0000");
            assertThat(inv.totalAmount()).isEqualByComparingTo("11500.0000");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Émission (DRAFT → ISSUED)")
    class EmissionFacture {
        @Test
        @DisplayName("Émettre → invoiceNumber attribué + écriture comptable générée")
        void issueInvoice() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            InvoiceResponse issued = service.issueInvoice(COMPANY_A, inv.id());

            assertThat(issued.status()).isEqualTo(InvoiceStatus.ISSUED);
            assertThat(issued.invoiceNumber()).isNotNull().startsWith("FAC-2026-");
            assertThat(issued.journalEntryId()).isNotNull();
        }

        @Test
        @DisplayName("Émettre une facture déjà ISSUED → 409")
        void cannotIssueAlreadyIssued() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            service.issueInvoice(COMPANY_A, inv.id());
            assertThatThrownBy(() -> service.issueInvoice(COMPANY_A, inv.id()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("INVOICE_NOT_DRAFT");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Règlement")
    class Reglement {
        @Test
        @DisplayName("Règlement partiel → PARTIALLY_PAID")
        void partialPayment() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            service.issueInvoice(COMPANY_A, inv.id());
            InvoiceResponse paid = service.recordPayment(COMPANY_A, inv.id(),
                new RecordPaymentRequest(new BigDecimal("5000")));
            assertThat(paid.status()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
            assertThat(paid.paidAmount()).isEqualByComparingTo("5000");
            assertThat(paid.balanceDue()).isEqualByComparingTo("6500");
        }

        @Test
        @DisplayName("Règlement total → PAID")
        void fullPayment() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            service.issueInvoice(COMPANY_A, inv.id());
            InvoiceResponse paid = service.recordPayment(COMPANY_A, inv.id(),
                new RecordPaymentRequest(new BigDecimal("11500")));
            assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(paid.balanceDue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Règlement > solde dû → 422")
        void paymentExceedsTotal() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            service.issueInvoice(COMPANY_A, inv.id());
            assertThatThrownBy(() -> service.recordPayment(COMPANY_A, inv.id(),
                new RecordPaymentRequest(new BigDecimal("99999"))))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("PAYMENT_EXCEEDS_TOTAL");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Avoir (CREDIT_NOTE)")
    class Avoir {
        @Test
        @DisplayName("Créer un avoir pour une facture ISSUED → type CREDIT_NOTE")
        void createCreditNote() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            service.issueInvoice(COMPANY_A, inv.id());

            InvoiceResponse cn = service.createCreditNote(COMPANY_A, inv.id(), standardInvoice(tpId));
            assertThat(cn.type()).isEqualTo(InvoiceType.CREDIT_NOTE);
            assertThat(cn.status()).isEqualTo(InvoiceStatus.DRAFT);
            assertThat(cn.creditNoteForInvoiceId()).isEqualTo(inv.id());
            assertThat(cn.totalAmount()).isEqualByComparingTo("11500.0000");  // même montant
        }
    }

    @Nested
    @DisplayName("Règle 5 — PDF")
    class Pdf {
        @Test
        @DisplayName("Générer le PDF d'une facture ISSUED → PDF non vide")
        void generatePdf() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            service.issueInvoice(COMPANY_A, inv.id());

            byte[] pdf = service.getInvoicePdf(COMPANY_A, inv.id());
            assertThat(pdf).isNotEmpty();
            assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        }

        @Test
        @DisplayName("Générer le PDF d'une facture DRAFT → 409")
        void cannotGeneratePdfForDraft() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            assertThatThrownBy(() -> service.getInvoicePdf(COMPANY_A, inv.id()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("INVOICE_NOT_ISSUED");
        }
    }

    @Nested
    @DisplayName("Règle 6 — itemId + timesheetEntryId exclusifs")
    class Exclusivite {
        @Test
        @DisplayName("Ligne avec itemId ET timesheetEntryId → 422")
        void bothItemAndTimesheetRejected() {
            UUID tpId = initFixture();
            CreateInvoiceRequest badReq = new CreateInvoiceRequest(
                tpId, InvoiceType.STANDARD, LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
                "HTG",
                List.of(new CreateInvoiceRequest.LineDto(
                    "Test", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                    UUID.randomUUID(), UUID.randomUUID())),  // les deux !
                null);
            assertThatThrownBy(() -> service.createInvoice(COMPANY_A, badReq))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("ITEM_AND_TIMESHEET_EXCLUSIVE");
        }
    }

    @Nested
    @DisplayName("Règle 7 — Isolation multi-tenant")
    class IsolationTenant {
        @Test
        @DisplayName("Company B ne peut pas voir la facture de Company A → 404")
        void companyBCannotSeeCompanyAInvoice() {
            UUID tpId = initFixture();
            InvoiceResponse inv = service.createInvoice(COMPANY_A, standardInvoice(tpId));
            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.loadInvoiceResponse(COMPANY_B, inv.id()))
                .isInstanceOf(NotFoundException.class);
        }
    }
}

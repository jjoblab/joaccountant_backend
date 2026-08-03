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
import jo.accountant.purchaseorders.dto.CreatePurchaseOrderRequest;
import jo.accountant.purchaseorders.dto.PurchaseOrderResponse;
import jo.accountant.purchaseorders.dto.ThreeWayMatchResult;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;
import jo.accountant.purchaseorders.repository.PurchaseOrderLineRepository;
import jo.accountant.purchaseorders.repository.PurchaseOrderRepository;
import jo.accountant.purchaseorders.service.PurchaseOrdersService;
import jo.accountant.purchaseorders.service.ThreeWayMatchService;
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.InvoiceRepository;
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
 * Tests d'intégration du 3-way match (Finding #10 — module :purchase-orders).
 *
 * <p>Vérifie que {@link ThreeWayMatchService#match} compare correctement une facture fournisseur
 * ({@code PurchaseInvoice}) avec une commande ({@code PurchaseOrder}) sur 3 dimensions :
 * existence de commande, quantités, prix.
 *
 * <p>Scénarios :
 * <ol>
 *   <li>Crée une commande + une facture avec lignes identiques → match OK.</li>
 *   <li>Crée une 2e facture avec quantité supérieure à la commande → QUANTITY_EXCEEDED.</li>
 * </ol>
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ThreeWayMatchIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ThreeWayMatchIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private PurchaseOrdersService poService;
    @Autowired private ThreeWayMatchService matchService;
    @Autowired private InvoicingService invoicingService;
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
    @Autowired private PurchaseOrderRepository poRepo;
    @Autowired private PurchaseOrderLineRepository polRepo;
    @Autowired private InvoiceRepository piRepo;
    @Autowired private InvoiceLineRepository pilRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            pilRepo.deleteAllInBatch();
            piRepo.deleteAllInBatch();
            polRepo.deleteAllInBatch();
            poRepo.deleteAllInBatch();
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
        TenantContext.setUserId(USER_X);
    }

    /** Initialise le fixture : plan comptable + journaux + exercice + séquences + tiers fournisseur. */
    private UUID initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveSupplier = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "401000", "Fournisseurs", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, true, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "601000", "Achats de marchandises", ReportingClass.CHARGES,
            ReportingSubcategory.COURANT, NormalBalance.DEBIT, false, null, List.of()));
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "445000", "TVA déductible", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "AC", "Journal des achats");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "AC", "AC", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.PURCHASE_INVOICE,
            "AC", "FAC", true, 6, ResetPolicy.YEARLY);

        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.SUPPLIER, "Fournisseur Test SARL",
            collectiveSupplier.id(), "supplier@test.dev", null));
        return tp.id();
    }

    /** Crée une commande avec une ligne "Article A" qté=10 prix=100, statut SUBMITTED. */
    private PurchaseOrderResponse createStandardPo(UUID supplierId) {
        return poService.create(COMPANY_A, new CreatePurchaseOrderRequest(
            supplierId, "PO-2026-001", LocalDate.of(2026, 7, 1), "HTG",
            PurchaseOrderStatus.SUBMITTED,
            List.of(new CreatePurchaseOrderRequest.LineDto(
                null, "Article A", new BigDecimal("10"), new BigDecimal("100")))));
    }

    /** Crée une facture fournisseur avec une ligne "Article A" qté=q prix=100. */
    private InvoiceResponse createInvoice(UUID supplierId, BigDecimal quantity) {
        return invoicingService.createInvoice(COMPANY_A, new CreateInvoiceRequest(
            supplierId, null,
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
            "HTG",
            List.of(new CreateInvoiceRequest.LineDto(
                "Article A", quantity, new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, null)),
            null));
    }

    @Nested
    @DisplayName("Règle 1 — 3-way match OK (lignes identiques)")
    class MatchOk {
        @Test
        @DisplayName("PO et facture avec mêmes lignes → matches=true, aucune discrepancy")
        void threeWayMatchOk() {
            UUID supplierId = initFixture();
            createStandardPo(supplierId);
            InvoiceResponse inv = createInvoice(supplierId, new BigDecimal("10"));

            ThreeWayMatchResult result = matchService.match(COMPANY_A, inv.id());

            assertThat(result.matches()).isTrue();
            assertThat(result.purchaseOrderId()).isNotNull();
            assertThat(result.discrepancies()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Règle 2 — QUANTITY_EXCEEDED (quantité facturée > quantité commandée)")
    class QuantityExceeded {
        @Test
        @DisplayName("Facture avec qté > qté commandée → matches=false, discrepancy QUANTITY_EXCEEDED")
        void quantityExceededDiscrepancy() {
            UUID supplierId = initFixture();
            createStandardPo(supplierId);  // PO avec qté=10
            // Facture avec qté=15 (supérieure à la commande)
            InvoiceResponse inv = createInvoice(supplierId, new BigDecimal("15"));

            ThreeWayMatchResult result = matchService.match(COMPANY_A, inv.id());

            assertThat(result.matches()).isFalse();
            assertThat(result.discrepancies()).hasSize(1);
            ThreeWayMatchResult.Discrepancy d = result.discrepancies().get(0);
            assertThat(d.type()).isEqualTo("QUANTITY_EXCEEDED");
            assertThat(d.expected()).isEqualByComparingTo("10");  // qté commandée
            assertThat(d.actual()).isEqualByComparingTo("15");    // qté facturée
        }
    }
}

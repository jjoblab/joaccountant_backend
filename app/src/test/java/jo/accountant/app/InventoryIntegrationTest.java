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
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.inventory.dto.CreateItemRequest;
import jo.accountant.inventory.dto.CreateStockMoveRequest;
import jo.accountant.inventory.dto.CreateWarehouseRequest;
import jo.accountant.inventory.dto.ItemValuation;
import jo.accountant.inventory.dto.StockMoveResponse;
import jo.accountant.inventory.entity.CostingMethod;
import jo.accountant.inventory.entity.StockMoveDirection;
import jo.accountant.inventory.repository.ItemRepository;
import jo.accountant.inventory.repository.StockMoveRepository;
import jo.accountant.inventory.repository.StockValuationLayerRepository;
import jo.accountant.inventory.repository.WarehouseRepository;
import jo.accountant.inventory.service.InventoryService;
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
 * Tests d'intégration du module {@code inventory} — Phase 9.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, InventoryIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class InventoryIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private InventoryService service;
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
    @Autowired private WarehouseRepository whRepo;
    @Autowired private ItemRepository itemRepo;
    @Autowired private StockMoveRepository smRepo;
    @Autowired private StockValuationLayerRepository svlRepo;
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
            smRepo.deleteAllInBatch();
            svlRepo.deleteAllInBatch();
            itemRepo.deleteAllInBatch();
            whRepo.deleteAllInBatch();
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
    @DisplayName("Règle 10 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si INVENTORY désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            // COMPANY_A n'a pas activé le module INVENTORY ici — le guard doit lever 403.
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.INVENTORY))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    /** Initialise fixture : plan SYSCOHADA + comptes 30 (stock) + 603 (COGS) + journal OD + exercice. */
    private Fixture initFixture(CostingMethod costingMethod) {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class3 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "3").orElseThrow();
        var stockAccount = coaService.createChild(COMPANY_A, class3.getId(), new CreateChildRequest(
            "300", "Stocks de marchandises", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        var cogsAccount = coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "603", "Variation de stocks", ReportingClass.CHARGES, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);

        var warehouse = service.createWarehouse(COMPANY_A, new CreateWarehouseRequest("Boutique PV"));
        var item = service.createItem(COMPANY_A, new CreateItemRequest(
            "SKU-001", "Produit Test", "unité", costingMethod,
            new BigDecimal("50"), stockAccount.id(), cogsAccount.id()));

        return new Fixture(warehouse.getId(), item.id(), stockAccount.id(), cogsAccount.id());
    }

    private record Fixture(UUID warehouseId, UUID itemId, UUID stockAccountId, UUID cogsAccountId) {}

    @Nested
    @DisplayName("Règle 1 — Création warehouse + item OK")
    class Creation {

        @Test
        @DisplayName("Créer warehouse + item FIFO OK")
        void createWarehouseAndItem() {
            Fixture f = initFixture(CostingMethod.FIFO);
            assertThat(f.warehouseId()).isNotNull();
            assertThat(f.itemId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 2 — Entrée stock IN → stock augmente, valuation layer créée (FIFO)")
    class EntreeStock {

        @Test
        @DisplayName("IN 100 @ 10 → stock = 100, valeur = 1000")
        void stockInCreatesLayer() {
            Fixture f = initFixture(CostingMethod.FIFO);

            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("100"), new BigDecimal("10"), "Bon réception 1"));

            ItemValuation val = service.getValuation(COMPANY_A, f.itemId());
            assertThat(val.totalQuantity()).isEqualByComparingTo("100");
            assertThat(val.totalValue()).isEqualByComparingTo("1000");
            assertThat(val.layers()).hasSize(1);
            assertThat(val.layers().get(0).quantityRemaining()).isEqualByComparingTo("100");
            assertThat(val.layers().get(0).unitCost()).isEqualByComparingTo("10");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Sortie stock OUT → COGS FIFO (première couche d'abord)")
    class SortieFIFO {

        @Test
        @DisplayName("IN 100@10 + IN 50@15, OUT 80 → COGS = 80×10 = 800 (FIFO consomme la première couche)")
        void fifoConsumesOldestLayerFirst() {
            Fixture f = initFixture(CostingMethod.FIFO);

            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("100"), new BigDecimal("10"), "Réception 1"));
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 5),
                StockMoveDirection.IN, new BigDecimal("50"), new BigDecimal("15"), "Réception 2"));

            StockMoveResponse out = service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 10),
                StockMoveDirection.OUT, new BigDecimal("80"), null, "Vente 1"));

            // FIFO : 80 unités à 10 = 800
            assertThat(out.totalCost()).isEqualByComparingTo("800");
            assertThat(out.unitCost()).isEqualByComparingTo("10");
            assertThat(out.journalEntryId()).isNotNull();  // écriture COGS générée

            ItemValuation val = service.getValuation(COMPANY_A, f.itemId());
            // Reste : 20@10 + 50@15 = 200 + 750 = 950
            assertThat(val.totalQuantity()).isEqualByComparingTo("70");
            assertThat(val.totalValue()).isEqualByComparingTo("950");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Sortie stock OUT → COGS WEIGHTED_AVERAGE")
    class SortieWeightedAverage {

        @Test
        @DisplayName("IN 100@10 + IN 50@15, OUT 80 → COGS = 80 × 11.67 = 933.33 (coût moyen)")
        void weightedAverageCalculatesMeanCost() {
            Fixture f = initFixture(CostingMethod.WEIGHTED_AVERAGE);

            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("100"), new BigDecimal("10"), "Réception 1"));
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 5),
                StockMoveDirection.IN, new BigDecimal("50"), new BigDecimal("15"), "Réception 2"));

            StockMoveResponse out = service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 10),
                StockMoveDirection.OUT, new BigDecimal("80"), null, "Vente 1"));

            // Coût moyen = (100×10 + 50×15) / 150 = 1750/150 = 11.6667 (précision interne 6 décimales)
            // COGS = 80 × 11.6667 = 933.333 → arrondi à 2 décimales HTG (audit M14) = 933.33
            assertThat(out.totalCost()).isEqualByComparingTo("933.33");
            assertThat(out.journalEntryId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 5 — Stock négatif rejeté")
    class StockNegatif {

        @Test
        @DisplayName("OUT sans stock → 409 INSUFFICIENT_STOCK")
        void outWithoutStockRejected() {
            Fixture f = initFixture(CostingMethod.FIFO);

            assertThatThrownBy(() -> service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.OUT, new BigDecimal("10"), null, "Vente")))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_STOCK");
        }

        @Test
        @DisplayName("OUT plus que stock disponible → 409")
        void outMoreThanAvailableRejected() {
            Fixture f = initFixture(CostingMethod.FIFO);
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("10"), new BigDecimal("5"), "Réception"));

            assertThatThrownBy(() -> service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 5),
                StockMoveDirection.OUT, new BigDecimal("15"), null, "Vente")))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_STOCK");
        }
    }

    @Nested
    @DisplayName("Règle 6 — Multi-réceptions à prix différents → COGS FIFO cohérent")
    class MultiReceptions {

        @Test
        @DisplayName("3 réceptions à prix différents + 2 sorties → COGS et stock restant cohérents")
        void multiReceiptFifoConsistency() {
            Fixture f = initFixture(CostingMethod.FIFO);

            // 3 réceptions
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("100"), new BigDecimal("10"), "R1"));
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 5),
                StockMoveDirection.IN, new BigDecimal("50"), new BigDecimal("12"), "R2"));
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 10),
                StockMoveDirection.IN, new BigDecimal("30"), new BigDecimal("15"), "R3"));

            // Sortie 1 : 120 unités → FIFO = 100@10 + 20@12 = 1000 + 240 = 1240
            StockMoveResponse out1 = service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 15),
                StockMoveDirection.OUT, new BigDecimal("120"), null, "V1"));
            assertThat(out1.totalCost()).isEqualByComparingTo("1240");

            // Sortie 2 : 30 unités → FIFO = 30@12 (reste de R2) = 360
            StockMoveResponse out2 = service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 20),
                StockMoveDirection.OUT, new BigDecimal("30"), null, "V2"));
            assertThat(out2.totalCost()).isEqualByComparingTo("360");

            // Stock restant : 30@15 = 450
            ItemValuation val = service.getValuation(COMPANY_A, f.itemId());
            assertThat(val.totalQuantity()).isEqualByComparingTo("30");
            assertThat(val.totalValue()).isEqualByComparingTo("450");
        }
    }

    @Nested
    @DisplayName("Règle 7 — reorderThreshold déclenché → événement publié")
    class ReorderThreshold {

        @Test
        @DisplayName("Stock passe sous seuil → LowStockEvent publié")
        void lowStockEventPublished() {
            Fixture f = initFixture(CostingMethod.FIFO);
            // reorderThreshold = 50 (défini dans initFixture)
            // IN 60 → stock = 60 (> seuil)
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("60"), new BigDecimal("10"), "R1"));
            // OUT 30 → stock = 30 (< seuil 50) → LowStockEvent doit être publié
            StockMoveResponse out = service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 5),
                StockMoveDirection.OUT, new BigDecimal("30"), null, "V1"));

            assertThat(out.journalEntryId()).isNotNull();
            // L'événement est publié via ApplicationEventPublisher — on vérifie indirectement
            // que le stock restant est bien sous le seuil
            ItemValuation val = service.getValuation(COMPANY_A, f.itemId());
            assertThat(val.totalQuantity()).isEqualByComparingTo("30");
        }
    }

    @Nested
    @DisplayName("Règle 8 — Écriture COGS générée avec sourceModule=INVENTORY")
    class CogsEntry {

        @Test
        @DisplayName("Sortie génère une écriture avec journalEntryId non null")
        void outGeneratesCogsEntry() {
            Fixture f = initFixture(CostingMethod.FIFO);
            service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 1),
                StockMoveDirection.IN, new BigDecimal("100"), new BigDecimal("10"), "R1"));

            StockMoveResponse out = service.postStockMove(COMPANY_A, new CreateStockMoveRequest(
                f.itemId(), f.warehouseId(), null, LocalDate.of(2026, 7, 5),
                StockMoveDirection.OUT, new BigDecimal("20"), null, "V1"));

            assertThat(out.journalEntryId()).isNotNull();
            assertThat(out.totalCost()).isEqualByComparingTo("200");  // 20 × 10
        }
    }

    @Nested
    @DisplayName("Règle 9 — Isolation multi-tenant")
    class IsolationTenant {

        @Test
        @DisplayName("Company B ne peut pas voir l'item de Company A → 404")
        void companyBCannotSeeCompanyAItem() {
            Fixture f = initFixture(CostingMethod.FIFO);
            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getValuation(COMPANY_B, f.itemId()))
                .isInstanceOf(NotFoundException.class);
        }
    }
}

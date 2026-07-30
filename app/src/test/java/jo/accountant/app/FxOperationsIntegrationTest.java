package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import jo.accountant.core.currency.ExchangeRateRepository;
import jo.accountant.core.currency.ExchangeRateService;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.fxoperations.dto.CreateFxOperationRequest;
import jo.accountant.fxoperations.dto.FxOperationResponse;
import jo.accountant.fxoperations.entity.FxOperationType;
import jo.accountant.fxoperations.repository.FxOperationRepository;
import jo.accountant.fxoperations.service.FxOperationsService;
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
 * Tests d'intégration du module {@code fx-operations} (restructuration 2026-07-24 suite 3).
 *
 * <p>Couverture des 5 règles métier :
 * <ol>
 *   <li>BUY — achat de devise étrangère : {@code fxGainLoss = 0} (taux direct, ni gain ni perte)
 *       + écriture comptable générée ({@code journalEntryId} non null).</li>
 *   <li>SELL — vente de devise avec gain latent : {@code fxGainLoss > 0}.</li>
 *   <li>REVALUATION — réévaluation de fin de période : {@code fxGainLoss = 10 000 HTG}
 *       (solde historique 150 000 HTG → solde clôture 160 000 HTG).</li>
 *   <li>Devises identiques rejetées : {@code ValidationException("SAME_CURRENCY")}.</li>
 *   <li>Taux incohérent rejeté : {@code ValidationException("INCONSISTENT_RATE")}.</li>
 * </ol>
 *
 * <p>Fixture : plan SYSCOHADA + comptes 521 (Banque, ACTIF, CASH), 776 (Gains de change,
 * PRODUITS, FX_GAIN), 676 (Pertes de change, CHARGES, FX_LOSS) + journal "OD" + exercice 2024
 * + taux de change USD→HTG = 150 au 2024-01-01 et 160 au 2024-12-31.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, FxOperationsIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class FxOperationsIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private FxOperationsService fxService;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private FxOperationRepository fxOperationRepo;
    @Autowired private ExchangeRateRepository exchangeRateRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            fxOperationRepo.deleteAllInBatch();
            jlatRepo.deleteAllInBatch();
            jlRepo.deleteAllInBatch();
            jeRepo.deleteAllInBatch();
            journalRepo.deleteAllInBatch();
            fpRepo.deleteAllInBatch();
            fyRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
            exchangeRateRepo.deleteAllInBatch();
            docSeqCounterRepo.deleteAll();
            docSeqConfigRepo.deleteAllInBatch();
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    /**
     * Initialise la fixture : plan SYSCOHADA + comptes 521/776/676 (avec taxMappingCodes
     * CASH/FX_GAIN/FX_LOSS) + journal OD + exercice 2024 + séquence JOURNAL_ENTRY OD +
     * taux USD→HTG = 150 au 2024-01-01 et 160 au 2024-12-31.
     */
    private void initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        // 521 Banque (ACTIF, CASH) sous classe 5
        var class5 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "5").orElseThrow();
        coaService.createChild(COMPANY_A, class5.getId(), new CreateChildRequest(
            "521", "Banque", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, "CASH", List.of()));

        // 776 Gains de change (PRODUITS, FX_GAIN) sous classe 7
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "776", "Gains de change", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, "FX_GAIN", List.of()));

        // 676 Pertes de change (CHARGES, FX_LOSS) sous classe 6
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "676", "Pertes de change", ReportingClass.CHARGES, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, "FX_LOSS", List.of()));

        // Journal OD + exercice 2024 + séquence JOURNAL_ENTRY OD
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);

        // Taux de change USD → HTG : 150 au 2024-01-01 (historique), 160 au 2024-12-31 (clôture)
        exchangeRateService.createRate(COMPANY_A, "USD", "HTG",
            new BigDecimal("150"), LocalDate.of(2024, 1, 1), "test");
        exchangeRateService.createRate(COMPANY_A, "USD", "HTG",
            new BigDecimal("160"), LocalDate.of(2024, 12, 31), "test");
    }

    // ════════════════════════════════════════════════════════════════════
    //  Règles métier
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Règle 1 — BUY : achat de devise, fxGainLoss = 0")
    class BuyOperation {
        @Test
        @DisplayName("BUY 2000 USD avec 300 000 HTG (taux marché 150) → fxGainLoss = 0 + écriture générée")
        void buyForeignCurrencyNoGainNoLoss() {
            initFixture();

            // BUY : on vend fromCurrency (HTG) pour acheter toCurrency (USD).
            // Convention API : 1 fromCurrency = rate toCurrency, donc 1 HTG = (1/150) USD.
            // Pour respecter la validation toAmount = fromAmount × rate avec fromAmount=300 000
            // HTG et toAmount=2000 USD, on fournit rate = 1/150.
            BigDecimal rate = BigDecimal.ONE.divide(new BigDecimal("150"), 10, RoundingMode.HALF_UP);

            FxOperationResponse res = fxService.create(COMPANY_A, new CreateFxOperationRequest(
                FxOperationType.BUY,
                "HTG", "USD",
                new BigDecimal("300000"), new BigDecimal("2000"),
                rate,
                LocalDate.of(2024, 1, 1),
                "Achat 2000 USD avec 300 000 HTG",
                null));

            assertThat(res.id()).isNotNull();
            // fromAmountFunctional = 300 000 HTG (identité HTG→HTG)
            // toAmountFunctional   = 2000 USD × 150 (taux USD→HTG au 2024-01-01) = 300 000 HTG
            // fxGainLoss (BUY) = toAmountFunctional − fromAmountFunctional = 0
            assertThat(res.fxGainLoss()).isEqualByComparingTo("0");
            assertThat(res.journalEntryId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 2 — SELL avec gain latent")
    class SellWithGain {
        @Test
        @DisplayName("SELL 1000 USD → 150 000 HTG (taux marché 160 au 2024-12-31) → fxGainLoss > 0")
        void sellForeignCurrencyWithGain() {
            initFixture();

            // SELL : on vend fromCurrency (USD) pour acheter toCurrency (HTG).
            // L'utilisateur fournit toAmount = 150 000 HTG (valorisation historique au taux 150),
            // mais le taux applicable à la date d'opération (2024-12-31) est 160.
            // → fromAmountFunctional = 1000 × 160 = 160 000 HTG (valorisation clôture)
            // → toAmountFunctional   = 150 000 HTG (identité HTG→HTG, valeur historique)
            // → fxGainLoss (SELL) = fromAmountFunctional − toAmountFunctional = +10 000 HTG (gain)
            FxOperationResponse res = fxService.create(COMPANY_A, new CreateFxOperationRequest(
                FxOperationType.SELL,
                "USD", "HTG",
                new BigDecimal("1000"), new BigDecimal("150000"),
                new BigDecimal("150"),
                LocalDate.of(2024, 12, 31),
                "Vente 1000 USD",
                null));

            assertThat(res.id()).isNotNull();
            assertThat(res.fxGainLoss()).isPositive();
            assertThat(res.fxGainLoss()).isEqualByComparingTo("10000");
            assertThat(res.journalEntryId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 3 — REVALUATION : gain latent de 10 000 HTG")
    class Revaluation {
        @Test
        @DisplayName("REVALUATION 1000 USD (historique 150 000 HTG → clôture 160 000 HTG) → fxGainLoss = 10 000")
        void revaluationProducesExpectedGain() {
            initFixture();

            // REVALUATION : fromAmount = solde en devise étrangère (1000 USD),
            // toAmount = solde converti au taux historique (150 000 HTG).
            // À la date d'opération (2024-12-31), le taux USD→HTG = 160.
            // → fromAmountFunctional = 1000 × 160 = 160 000 HTG (clôture)
            // → toAmountFunctional   = 150 000 HTG (identité, valeur historique)
            // → fxGainLoss (REVALUATION) = fromAmountFunctional − toAmountFunctional = +10 000 HTG
            FxOperationResponse res = fxService.create(COMPANY_A, new CreateFxOperationRequest(
                FxOperationType.REVALUATION,
                "USD", "HTG",
                new BigDecimal("1000"), new BigDecimal("150000"),
                new BigDecimal("150"),
                LocalDate.of(2024, 12, 31),
                "Réévaluation fin d'exercice",
                null));

            assertThat(res.id()).isNotNull();
            assertThat(res.fxGainLoss()).isEqualByComparingTo("10000");
            assertThat(res.journalEntryId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 4 — Devises identiques rejetées")
    class SameCurrencyRejected {
        @Test
        @DisplayName("BUY avec fromCurrency=HTG, toCurrency=HTG → 422 SAME_CURRENCY")
        void sameCurrencyRejected() {
            initFixture();

            assertThatThrownBy(() -> fxService.create(COMPANY_A, new CreateFxOperationRequest(
                FxOperationType.BUY,
                "HTG", "HTG",
                new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ONE,
                LocalDate.of(2024, 1, 1),
                "Devises identiques",
                null)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("SAME_CURRENCY");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Taux incohérent rejeté")
    class InconsistentRateRejected {
        @Test
        @DisplayName("BUY with fromAmount=100, rate=2, toAmount=500 (devrait être 200) → 422 INCONSISTENT_RATE")
        void inconsistentRateRejected() {
            initFixture();

            // expectedTo = fromAmount × rate = 100 × 2 = 200, mais toAmount = 500 → |500−200| > 0.01
            assertThatThrownBy(() -> fxService.create(COMPANY_A, new CreateFxOperationRequest(
                FxOperationType.BUY,
                "USD", "HTG",
                new BigDecimal("100"), new BigDecimal("500"),
                new BigDecimal("2"),
                LocalDate.of(2024, 1, 1),
                "Taux incohérent",
                null)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("INCONSISTENT_RATE");
        }
    }
}

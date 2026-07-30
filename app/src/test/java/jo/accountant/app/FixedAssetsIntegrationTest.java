package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.FiscalYear;
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
import jo.accountant.fixedassets.dto.AssetResponse;
import jo.accountant.fixedassets.dto.CreateAssetRequest;
import jo.accountant.fixedassets.dto.DisposeAssetRequest;
import jo.accountant.fixedassets.dto.ScheduleLineResponse;
import jo.accountant.fixedassets.entity.AssetStatus;
import jo.accountant.fixedassets.entity.DepreciationMethod;
import jo.accountant.fixedassets.repository.AssetRepository;
import jo.accountant.fixedassets.repository.DepreciationScheduleLineRepository;
import jo.accountant.fixedassets.service.FixedAssetsService;
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
 * Tests d'intégration du module {@code fixed-assets} — Phase 8.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, FixedAssetsIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class FixedAssetsIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private FixedAssetsService service;
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
    @Autowired private AssetRepository assetRepo;
    @Autowired private DepreciationScheduleLineRepository dslRepo;
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
            dslRepo.deleteAllInBatch();
            assetRepo.deleteAllInBatch();
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
    @DisplayName("Règle 9 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si FIXED_ASSETS désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.FIXED_ASSETS))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    /** Initialise le fixture : plan SYSCOHADA + comptes + journal OD + exercice 2026. */
    private CreateAssetRequest initFixtureAndReturnAssetRequest() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        // Comptes : 244 (actif immobilisé - matériel de transport), 2844 (amortissement cumulé),
        // 631 (charge d'amortissement)
        var class2 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "2").orElseThrow();
        var assetAccount = coaService.createChild(COMPANY_A, class2.getId(), new CreateChildRequest(
            "244", "Matériel de transport", ReportingClass.ACTIF, ReportingSubcategory.NON_COURANT,
            NormalBalance.DEBIT, false, null, List.of()));
        var accumulatedAccount = coaService.createChild(COMPANY_A, class2.getId(), new CreateChildRequest(
            "2844", "Amortissement matériel de transport", ReportingClass.ACTIF,
            ReportingSubcategory.NON_COURANT, NormalBalance.CREDIT, false, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        var expenseAccount = coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "631", "Charges d'amortissement", ReportingClass.CHARGES, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "OD", "Journal des opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));
        // Exercice 2027 — nécessaire pour les cessions en 2027
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), "Exercice 2027"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);

        return new CreateAssetRequest(
            "Véhicule Toyota Corolla 2026",
            LocalDate.of(2026, 1, 15),   // acquisition le 15 janvier
            new BigDecimal("5000000"),    // 5,000,000 HTG
            60,                            // 60 mois = 5 ans
            new BigDecimal("500000"),     // valeur résiduelle 500,000
            DepreciationMethod.STRAIGHT_LINE,
            assetAccount.id(),
            expenseAccount.id(),
            accumulatedAccount.id()
        );
    }

    @Nested
    @DisplayName("Règle 1 — Création auto-génère l'échéancier")
    class CreationAutoSchedule {

        @Test
        @DisplayName("Créer un actif 60 mois → échéancier de 60 lignes")
        void createGeneratesSchedule() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            assertThat(asset.id()).isNotNull();
            assertThat(asset.status()).isEqualTo(AssetStatus.ACTIVE);
            assertThat(asset.cumulativeDepreciation()).isEqualByComparingTo("0");  // rien posté encore

            List<ScheduleLineResponse> schedule = service.getSchedule(COMPANY_A, asset.id());
            assertThat(schedule).hasSize(60);  // 60 mois
            assertThat(schedule.get(0).periodDate()).isEqualTo(LocalDate.of(2026, 2, 1));  // février 2026 (mois suivant acquisition)
            assertThat(schedule.get(59).periodDate()).isEqualTo(LocalDate.of(2031, 1, 1));  // janvier 2031
            assertThat(schedule).allSatisfy(line -> {
                assertThat(line.posted()).isFalse();
                assertThat(line.journalEntryId()).isNull();
            });
        }
    }

    @Nested
    @DisplayName("Règle 2 — STRAIGHT_LINE : montant = (coût − résiduel) / mois")
    class StraightLine {

        @Test
        @DisplayName("Montant mensuel = (5000000 - 500000) / 60 = 75000")
        void straightLineAmount() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            List<ScheduleLineResponse> schedule = service.getSchedule(COMPANY_A, asset.id());
            // (5000000 - 500000) / 60 = 75000
            assertThat(schedule.get(0).amount()).isEqualByComparingTo("75000.0000");
            assertThat(schedule.get(10).amount()).isEqualByComparingTo("75000.0000");
            // Cumul à la fin = 4500000 (coût - résiduel)
            assertThat(schedule.get(59).cumulativeAmount()).isEqualByComparingTo("4500000.0000");
        }
    }

    @Nested
    @DisplayName("Règle 3 — postPeriodDepreciation génère une écriture POSTED")
    class PostPeriod {

        @Test
        @DisplayName("Poster l'amortissement de février 2026 → écriture POSTED avec reference")
        void postPeriodGeneratesEntry() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            // Trouver la période fiscale de février 2026
            FiscalYear fy = fyRepo.findByCompanyIdOrderByStartDateAsc(COMPANY_A).get(0);
            var feb2026 = fpRepo.findByFiscalYearIdOrderByStartDateAsc(fy.getId()).get(1);  // index 1 = février

            ScheduleLineResponse posted = service.postPeriodDepreciation(
                COMPANY_A, asset.id(), feb2026.getId());

            assertThat(posted.posted()).isTrue();
            assertThat(posted.journalEntryId()).isNotNull();
            assertThat(posted.postedAt()).isNotNull();
            assertThat(posted.amount()).isEqualByComparingTo("75000.0000");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Impossible de poster la même période deux fois")
    class DoublePost {

        @Test
        @DisplayName("Re-poster la même période → 409 PERIOD_ALREADY_POSTED")
        void cannotPostSamePeriodTwice() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            FiscalYear fy = fyRepo.findByCompanyIdOrderByStartDateAsc(COMPANY_A).get(0);
            var feb2026 = fpRepo.findByFiscalYearIdOrderByStartDateAsc(fy.getId()).get(1);

            service.postPeriodDepreciation(COMPANY_A, asset.id(), feb2026.getId());

            assertThatThrownBy(() -> service.postPeriodDepreciation(COMPANY_A, asset.id(), feb2026.getId()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("PERIOD_ALREADY_POSTED");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Cession calcule la plus/moins-value")
    class CessionGainLoss {

        @Test
        @DisplayName("Cession avec prix > VNC → plus-value")
        void disposalWithGain() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            // Poster 11 mois d'amortissement (février à décembre 2026, cumul = 11 × 75000 = 825000)
            // L'échéancier démarre en février 2026 (mois suivant acquisition janvier 2026)
            FiscalYear fy2026 = fyRepo.findByCompanyIdOrderByStartDateAsc(COMPANY_A).get(0);
            for (int i = 1; i <= 11; i++) {  // index 1 = février, ..., index 11 = décembre
                var period = fpRepo.findByFiscalYearIdOrderByStartDateAsc(fy2026.getId()).get(i);
                service.postPeriodDepreciation(COMPANY_A, asset.id(), period.getId());
            }

            // VNC = 5000000 - 825000 = 4175000
            // Prix de cession = 4500000 → plus-value = 325000
            AssetResponse disposed = service.dispose(COMPANY_A, asset.id(),
                new DisposeAssetRequest(LocalDate.of(2027, 1, 10), new BigDecimal("4500000"), null));

            assertThat(disposed.status()).isEqualTo(AssetStatus.DISPOSED);
            assertThat(disposed.disposalDate()).isEqualTo(LocalDate.of(2027, 1, 10));
            assertThat(disposed.disposalAmount()).isEqualByComparingTo("4500000");
            assertThat(disposed.gainOrLoss()).isEqualByComparingTo("325000");  // plus-value
        }

        @Test
        @DisplayName("Cession avec prix < VNC → moins-value")
        void disposalWithLoss() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            // Poster 11 mois (février à décembre 2026, cumul = 825000)
            FiscalYear fy2026 = fyRepo.findByCompanyIdOrderByStartDateAsc(COMPANY_A).get(0);
            for (int i = 1; i <= 11; i++) {
                var period = fpRepo.findByFiscalYearIdOrderByStartDateAsc(fy2026.getId()).get(i);
                service.postPeriodDepreciation(COMPANY_A, asset.id(), period.getId());
            }

            // VNC = 4175000, prix = 3500000 → moins-value = -675000
            AssetResponse disposed = service.dispose(COMPANY_A, asset.id(),
                new DisposeAssetRequest(LocalDate.of(2027, 1, 10), new BigDecimal("3500000"), null));

            assertThat(disposed.gainOrLoss()).isEqualByComparingTo("-675000");  // moins-value
            assertThat(disposed.status()).isEqualTo(AssetStatus.DISPOSED);
        }
    }

    @Nested
    @DisplayName("Règle 6 — Actif DISPOSED ne peut plus être amorti")
    class DisposedCannotDepreciate {

        @Test
        @DisplayName("Amortir un actif DISPOSED → 409 ASSET_DISPOSED")
        void cannotDepreciateDisposedAsset() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            service.dispose(COMPANY_A, asset.id(),
                new DisposeAssetRequest(LocalDate.of(2027, 1, 10), new BigDecimal("4500000"), null));

            FiscalYear fy = fyRepo.findByCompanyIdOrderByStartDateAsc(COMPANY_A).get(0);
            // Février 2026 (index 1) — première ligne de l'échéancier
            var feb2026 = fpRepo.findByFiscalYearIdOrderByStartDateAsc(fy.getId()).get(1);

            assertThatThrownBy(() -> service.postPeriodDepreciation(COMPANY_A, asset.id(), feb2026.getId()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ASSET_DISPOSED");
        }
    }

    @Nested
    @DisplayName("Règle 7 — Actif DISPOSED ne peut pas être cédé à nouveau")
    class DoubleDispose {

        @Test
        @DisplayName("Céder un actif déjà cédé → 409 ASSET_ALREADY_DISPOSED")
        void cannotDisposeTwice() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            service.dispose(COMPANY_A, asset.id(),
                new DisposeAssetRequest(LocalDate.of(2027, 1, 10), new BigDecimal("4500000"), null));

            assertThatThrownBy(() -> service.dispose(COMPANY_A, asset.id(),
                new DisposeAssetRequest(LocalDate.of(2027, 6, 10), new BigDecimal("4000000"), null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ASSET_ALREADY_DISPOSED");
        }
    }

    @Nested
    @DisplayName("Règle 8 — Isolation multi-tenant")
    class IsolationTenant {

        @Test
        @DisplayName("Company B ne peut pas voir l'actif de Company A → 404")
        void companyBCannotSeeCompanyAAsset() {
            CreateAssetRequest req = initFixtureAndReturnAssetRequest();
            AssetResponse asset = service.createAsset(COMPANY_A, req);

            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getAsset(COMPANY_B, asset.id()))
                .isInstanceOf(NotFoundException.class);
        }
    }
}

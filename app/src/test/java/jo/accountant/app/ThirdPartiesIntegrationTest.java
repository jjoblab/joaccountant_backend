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
import jo.accountant.accountingengine.entity.FiscalYear;
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
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.thirdparties.dto.AgedBalance;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.LettrageRequest;
import jo.accountant.thirdparties.dto.LettrageResponse;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.dto.ThirdPartyStatement;
import jo.accountant.thirdparties.entity.LettrageStatus;
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
 * Tests d'intégration du module {@code third-parties} — Phase 7.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ThirdPartiesIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ThirdPartiesIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private ThirdPartiesService service;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
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

    /** Initialise le fixture : plan SYSCOHADA + compte collectif 411 + journal VT + exercice 2026. */
    private ThirdPartyResponse initFixtureWithClient() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        // Compte collectif 411000 (Clients) sous la classe 4
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        // Compte 701000 (Ventes) pour les écritures de facture
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701000", "Ventes", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Journal des ventes");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);

        var compte411000 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "411000").orElseThrow();
        return service.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.CLIENT, "Boutique Pétion-Ville",
            compte411000.getId(), "client@test.dev", "Pétion-Ville, Haïti"));
    }

    private UUID postEntryWithThirdParty(String journalCode, String idemKey, String accountCode,
                                          UUID thirdPartyId, BigDecimal debit, BigDecimal credit) {
        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            journalCode, LocalDate.of(2026, 7, 15), "Test entry",
            List.of(new LineDto(accountCode, thirdPartyId, debit, credit, null, List.of()),
                    // Contrepartie sur 701000 (pas de tiers)
                    new LineDto("701000", null, credit, debit, null, List.of())),
            JournalEntrySourceModule.MANUAL);
        JournalEntryResponse created = accountingService.createJournalEntry(COMPANY_A, idemKey, req);
        accountingService.postJournalEntry(COMPANY_A, created.id(), List.of());
        return created.id();
    }

    @Nested
    @DisplayName("Règle 1 — Création de tiers avec auto-génération du compte dédié")
    class CreationTiers {

        @Test
        @DisplayName("Créer un client rattaché à un compte collectif → compte dédié auto-généré")
        void createClientAutoGeneratesDedicatedAccount() {
            ThirdPartyResponse tp = initFixtureWithClient();

            assertThat(tp.id()).isNotNull();
            assertThat(tp.type()).isEqualTo(ThirdPartyType.CLIENT);
            assertThat(tp.name()).isEqualTo("Boutique Pétion-Ville");
            assertThat(tp.collectiveAccountCode()).isEqualTo("411000");
            assertThat(tp.dedicatedAccountId()).isNotNull();
            assertThat(tp.dedicatedAccountCode()).isNotNull();
            // Le compte dédié doit commencer par le code du compte collectif (411000 + suffixe)
            assertThat(tp.dedicatedAccountCode()).startsWith("411000");
            assertThat(tp.dedicatedAccountCode()).isNotEqualTo("411000");  // pas le collectif lui-même
        }

        @Test
        @DisplayName("Créer un tiers rattaché à un compte non collectif → 422")
        void createThirdPartyOnNonCollectiveAccountThrows422() {
            asTenant(COMPANY_A);
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
            var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
            coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
                "701000", "Ventes", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
                NormalBalance.CREDIT, false, null, List.of()));

            var compte701 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "701000").orElseThrow();
            assertThatThrownBy(() -> service.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.CLIENT, "Test", compte701.getId(), null, null)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_COLLECTIVE");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Lettrage FULL (somme débit = somme crédit)")
    class LettrageFull {

        @Test
        @DisplayName("Facture 1000 + Règlement 1000 → FULL")
        void lettrageFullWhenSumsEqual() {
            ThirdPartyResponse tp = initFixtureWithClient();

            // Poste une facture : 411000 (compte dédié) D 1000, 701000 C 1000
            // Note : on poste sur le compte dédié, pas le collectif
            UUID factureEntryId = postEntryWithThirdParty("VT", "key-facture-1",
                tp.dedicatedAccountCode(), tp.id(), new BigDecimal("1000"), null);

            // Poste un règlement : 411000 C 1000, 701000 D 1000 (contrepassation)
            // En réalité, un règlement débite la banque et crédite le client. Pour simplifier,
            // on crédite le client et on débite 701000 (pas réaliste mais teste le lettrage).
            UUID paiementEntryId = postEntryWithThirdParty("VT", "key-paiement-1",
                tp.dedicatedAccountCode(), tp.id(), null, new BigDecimal("1000"));

            // Récupérer les lignes du tiers
            ThirdPartyStatement stmt = service.getStatement(COMPANY_A, tp.id(), null, null);
            assertThat(stmt.lines()).hasSize(2);
            assertThat(stmt.unletteredBalance()).isEqualByComparingTo(BigDecimal.ZERO);  // 1000 - 1000 = 0

            // Lettrer les 2 lignes
            List<UUID> lineIds = stmt.lines().stream()
                .map(ThirdPartyStatement.StatementLine::journalLineId).toList();
            LettrageResponse lettrage = service.lettrer(COMPANY_A,
                new LettrageRequest(tp.id(), lineIds));

            assertThat(lettrage.status()).isEqualTo(LettrageStatus.FULL);
            assertThat(lettrage.matchCode()).isEqualTo("A");  // premier lettrage = A
            assertThat(lettrage.matchedAmount()).isEqualByComparingTo("2000");  // 1000 + 1000
        }
    }

    @Nested
    @DisplayName("Règle 4 — Lettrage PARTIAL (somme débit ≠ somme crédit)")
    class LettragePartial {

        @Test
        @DisplayName("Facture 1000 + Règlement 800 → PARTIAL")
        void lettragePartialWhenSumsDiffer() {
            ThirdPartyResponse tp = initFixtureWithClient();

            postEntryWithThirdParty("VT", "key-facture-2",
                tp.dedicatedAccountCode(), tp.id(), new BigDecimal("1000"), null);
            postEntryWithThirdParty("VT", "key-paiement-2",
                tp.dedicatedAccountCode(), tp.id(), null, new BigDecimal("800"));

            ThirdPartyStatement stmt = service.getStatement(COMPANY_A, tp.id(), null, null);
            List<UUID> lineIds = stmt.lines().stream()
                .map(ThirdPartyStatement.StatementLine::journalLineId).toList();

            LettrageResponse lettrage = service.lettrer(COMPANY_A,
                new LettrageRequest(tp.id(), lineIds));

            assertThat(lettrage.status()).isEqualTo(LettrageStatus.PARTIAL);
            assertThat(lettrage.matchedAmount()).isEqualByComparingTo("1800");  // 1000 + 800
        }
    }

    @Nested
    @DisplayName("Règle 5 — Solde non lettré visible")
    class SoldeNonLettré {

        @Test
        @DisplayName("Le relevé affiche le solde non lettré")
        void statementShowsUnletteredBalance() {
            ThirdPartyResponse tp = initFixtureWithClient();

            // Poste une facture non réglée
            postEntryWithThirdParty("VT", "key-unpaid-1",
                tp.dedicatedAccountCode(), tp.id(), new BigDecimal("5000"), null);

            ThirdPartyStatement stmt = service.getStatement(COMPANY_A, tp.id(), null, null);
            assertThat(stmt.totalDebit()).isEqualByComparingTo("5000");
            assertThat(stmt.totalCredit()).isEqualByComparingTo("0");
            assertThat(stmt.balance()).isEqualByComparingTo("5000");
            assertThat(stmt.unletteredBalance()).isEqualByComparingTo("5000");
            assertThat(stmt.lines().get(0).matchCode()).isNull();  // non lettrée
        }
    }

    @Nested
    @DisplayName("Règle 6 — Balance âgée (0-30/31-60/61-90/90+)")
    class BalanceAgée {

        @Test
        @DisplayName("Balance âgée répartit le solde non lettré par tranche d'âge")
        void agedBalanceBucketsByAge() {
            ThirdPartyResponse tp = initFixtureWithClient();

            // Poste une facture récente (0-30 jours par rapport à 2026-08-15)
            // entryDate = 2026-07-15, asOf = 2026-08-15 → 31 jours → bucket 31-60
            postEntryWithThirdParty("VT", "key-aged-1",
                tp.dedicatedAccountCode(), tp.id(), new BigDecimal("1000"), null);

            // AsOf = 2026-08-15 → ageDays = 31 → bucket 31-60
            AgedBalance ab = service.getAgedBalance(COMPANY_A, tp.id(), LocalDate.of(2026, 8, 15));

            assertThat(ab.bucket0to30()).isEqualByComparingTo("0");
            assertThat(ab.bucket31to60()).isEqualByComparingTo("1000");
            assertThat(ab.bucket61to90()).isEqualByComparingTo("0");
            assertThat(ab.bucket90plus()).isEqualByComparingTo("0");
            assertThat(ab.totalUnlettered()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("asOf = 2026-10-15 → ageDays = 92 → bucket 90+")
        void agedBalance90plus() {
            ThirdPartyResponse tp = initFixtureWithClient();
            postEntryWithThirdParty("VT", "key-aged-2",
                tp.dedicatedAccountCode(), tp.id(), new BigDecimal("2000"), null);

            AgedBalance ab = service.getAgedBalance(COMPANY_A, tp.id(), LocalDate.of(2026, 10, 15));
            assertThat(ab.bucket90plus()).isEqualByComparingTo("2000");
            assertThat(ab.totalUnlettered()).isEqualByComparingTo("2000");
        }
    }

    @Nested
    @DisplayName("Règle 7 — Isolation multi-tenant")
    class IsolationTenant {

        @Test
        @DisplayName("Company B ne peut pas voir les tiers de Company A → 404")
        void companyBCannotSeeCompanyAThirdParties() {
            ThirdPartyResponse tp = initFixtureWithClient();

            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getStatement(COMPANY_B, tp.id(), null, null))
                .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Règle supplémentaire — Ligne déjà lettrée → 422")
    class LigneDejaLettrée {

        @Test
        @DisplayName("Tenter de re-lettrer une ligne déjà lettrée → 422")
        void cannotLettrerAlreadyLetteredLine() {
            ThirdPartyResponse tp = initFixtureWithClient();
            postEntryWithThirdParty("VT", "key-facture-3",
                tp.dedicatedAccountCode(), tp.id(), new BigDecimal("1000"), null);
            postEntryWithThirdParty("VT", "key-paiement-3",
                tp.dedicatedAccountCode(), tp.id(), null, new BigDecimal("1000"));

            ThirdPartyStatement stmt = service.getStatement(COMPANY_A, tp.id(), null, null);
            List<UUID> lineIds = stmt.lines().stream()
                .map(ThirdPartyStatement.StatementLine::journalLineId).toList();
            service.lettrer(COMPANY_A, new LettrageRequest(tp.id(), lineIds));

            // Tenter de re-lettrer les mêmes lignes → 422
            assertThatThrownBy(() -> service.lettrer(COMPANY_A, new LettrageRequest(tp.id(), lineIds)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("LINE_ALREADY_LETTERED");
        }
    }
}

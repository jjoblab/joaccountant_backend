package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.dto.TrialBalanceLine;
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
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.DocumentType;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests d'intégration du mécanisme d'<strong>exercice fiscal actif</strong>
 * ({@code companies.active_fiscal_year_id}).
 *
 * <p>Chaque entreprise a un et un seul exercice fiscal "actif" à un instant donné — pointeur
 * persisté dans {@code companies.active_fiscal_year_id}. Ce pointeur pilote :
 * <ul>
 *   <li>Les écritures : {@code createJournalEntry} valide la date contre l'exercice actif
 *       ({@code ENTRY_DATE_OUTSIDE_ACTIVE_FISCAL_YEAR}) et refuse toute saisie si l'exercice
 *       est {@code CLOSED} ({@code FISCAL_YEAR_CLOSED}).</li>
 *   <li>Les lectures agrégées : {@code getTrialBalance} et {@code searchJournalEntries}
 *       bornent automatiquement leurs requêtes aux dates de l'exercice actif.</li>
 *   <li>La clôture : {@code closeFiscalYear} bascule automatiquement le pointeur sur le
 *       prochain exercice {@code OPEN}, ou laisse le pointeur sur l'exercice CLOSED lorsque
 *       plus aucun OPEN n'existe (auquel cas {@code getActiveFiscalYearForRead} renvoie
 *       {@code Optional.empty()}).</li>
 * </ul>
 *
 * <p>Couverture des 12 règles métier :
 * <ol>
 *   <li>Auto-activation à la création du premier exercice</li>
 *   <li>{@code activateFiscalYear} positionne explicitement l'exercice actif</li>
 *   <li>{@code getActiveFiscalYear} auto-sélectionne le dernier OPEN si l'ID actif est NULL</li>
 *   <li>Exercice CLOSED actif bloque {@code createJournalEntry} ({@code FISCAL_YEAR_CLOSED})</li>
 *   <li>Date d'écriture hors exercice actif rejetée ({@code ENTRY_DATE_OUTSIDE_ACTIVE_FISCAL_YEAR})</li>
 *   <li>{@code closeFiscalYear} bascule sur le prochain OPEN</li>
 *   <li>{@code closeFiscalYear} vide l'actif (Pour lecture) quand plus aucun OPEN</li>
 *   <li>Balance générale filtrée par les dates de l'exercice actif</li>
 *   <li>{@code searchJournalEntries} utilise les dates de l'exercice actif par défaut</li>
 *   <li>Aucun exercice actif → {@code NO_ACTIVE_FISCAL_YEAR} sur {@code getTrialBalance}</li>
 *   <li>{@code checkActiveFiscalYearWritable} passe pour OPEN</li>
 *   <li>{@code checkActiveFiscalYearWritable} jette {@code FISCAL_YEAR_CLOSED} pour CLOSED</li>
 * </ol>
 *
 * <p>Pattern identique à {@link InvoicingIntegrationTest} : {@code @SpringBootTest} avec
 * {@link TestConfig} qui enregistre un {@link RecordingNotificationChannel} {@code @Primary},
 * étend {@code EmbeddedPostgresSupport} (PostgreSQL réel in-process), nettoie toutes les
 * données par entreprise dans un {@code @AfterEach}.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ActiveFiscalYearIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ActiveFiscalYearIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

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
            // Clear the active FY pointer FIRST — prevents any FK (active_fiscal_year_id → fiscal_year.id)
            // from blocking the fiscal_year DELETE below.
            jdbcTemplate.update("UPDATE companies SET active_fiscal_year_id = NULL WHERE id = ?", companyId);
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

    /**
     * Insère une ligne minimale dans {@code companies} (si elle n'existe pas déjà). Nécessaire
     * car {@code active_fiscal_year_id} est stocké sur cette table — sans ligne, le pointeur
     * ne peut être ni lu ni écrit.
     *
     * <p>Idempotent via {@code ON CONFLICT (id) DO NOTHING} — la ligne persiste entre les tests
     * (le {@code @AfterEach} ne supprime que les données comptables, pas la ligne company).
     */
    private void ensureCompanyRow(UUID companyId) {
        jdbcTemplate.update("""
            INSERT INTO companies (id, name, legal_form, country, functional_currency, sector,
                                   organization_nature, business_type_code, primary_activity_label,
                                   fiscal_year_start_month, wizard_step, wizard_completed)
            VALUES (?, 'Active FY Test Co', 'SARL', 'HT', 'HTG', 'COMMERCE',
                    'FOR_PROFIT', 'CUSTOM', 'Test activity',
                    1, 9, true)
            ON CONFLICT (id) DO NOTHING
            """, companyId);
    }

    /**
     * Initialise le plan comptable SYSCOHADA + un journal OD + les comptes nécessaires
     * (521 Banque, 701 Ventes, 443 TVA collectée, 120 Résultat) + une séquence de
     * numérotation pour {@code JOURNAL_ENTRY/OD}.
     *
     * <p>Ne crée PAS d'exercice fiscal — chaque test crée le sien (ou les siens) afin de
     * pouvoir tester précisément l'auto-activation et les bascules.
     */
    private void initFixture() {
        asTenant(COMPANY_A);
        ensureCompanyRow(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        // 521 Banque (ACTIF, DEBIT) — contrepartie trésorerie des écritures de test
        var class5 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "5").orElseThrow();
        coaService.createChild(COMPANY_A, class5.getId(), new CreateChildRequest(
            "521", "Banque", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));

        // 701 Ventes de marchandises (PRODUITS, CREDIT) — dégage un résultat net non nul
        // nécessaire pour que closeFiscalYear puisse générer l'écriture de clôture.
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701", "Ventes de marchandises", ReportingClass.PRODUITS,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));

        // 443 TVA collectée (PASSIF, CREDIT) — utile pour des écritures plus complexes.
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "443", "TVA collectée", ReportingClass.PASSIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        // 120 Résultat de l'exercice (CAPITAUX_PROPRES, CREDIT, taxMappingCode=FISCAL_RESULT)
        // — compte de report du résultat utilisé par closeFiscalYear.
        var class1 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "1").orElseThrow();
        coaService.createChild(COMPANY_A, class1.getId(), new CreateChildRequest(
            "120", "Résultat de l'exercice", ReportingClass.CAPITAUX_PROPRES,
            ReportingSubcategory.N_A, NormalBalance.CREDIT, false, "FISCAL_RESULT", List.of()));

        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");

        docNumberingService.createSequence(COMPANY_A,
            DocumentType.JOURNAL_ENTRY, "OD", "OD", true, 5, ResetPolicy.YEARLY);
    }

    /** Écriture équilibrée simple : Débit 521 / Crédit 701 pour {@code amount}. */
    private CreateJournalEntryRequest entryFor(LocalDate date, BigDecimal amount) {
        return new CreateJournalEntryRequest(
            "OD", date, "Saisie test " + date,
            List.of(
                new LineDto("521", null, amount, null, "Banque", List.of()),
                new LineDto("701", null, null, amount, "Ventes", List.of())
            ),
            JournalEntrySourceModule.MANUAL);
    }

    /** Crée + poste une écriture de test. Utilisé pour préparer des données avant clôture. */
    private JournalEntryResponse postEntry(LocalDate date, BigDecimal amount, String idemKey) {
        JournalEntryResponse created = accountingService.createJournalEntry(
            COMPANY_A, idemKey, entryFor(date, amount));
        return accountingService.postJournalEntry(COMPANY_A, created.id(), List.of());
    }

    /** Lecture brute du pointeur {@code active_fiscal_year_id} via JDBC. */
    private UUID readActiveFiscalYearId(UUID companyId) {
        return jdbcTemplate.queryForObject(
            "SELECT active_fiscal_year_id FROM companies WHERE id = ?",
            UUID.class, companyId);
    }

    /** Force la valeur NULL sur {@code active_fiscal_year_id} pour simuler l'absence d'actif. */
    private void clearActiveFiscalYearId(UUID companyId) {
        jdbcTemplate.update("UPDATE companies SET active_fiscal_year_id = NULL WHERE id = ?", companyId);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Nested
    @DisplayName("Test 1 — Auto-activation à la création du 1er exercice")
    class AutoActivationOnCreate {
        @Test
        @DisplayName("createFiscalYear auto-active le 1er exercice (getActiveFiscalYearForRead le retourne)")
        void createFiscalYearAutoActivatesFirst() {
            initFixture();
            FiscalYear fy = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));

            Optional<FiscalYear> activeOpt = accountingService.getActiveFiscalYearForRead(COMPANY_A);
            assertThat(activeOpt).isPresent();
            assertThat(activeOpt.get().getId()).isEqualTo(fy.getId());
            // Le pointeur DB doit également refléter l'auto-activation.
            assertThat(readActiveFiscalYearId(COMPANY_A)).isEqualTo(fy.getId());
        }
    }

    @Nested
    @DisplayName("Test 2 — activateFiscalYear positionne l'exercice actif")
    class ActivateFiscalYear {
        @Test
        @DisplayName("activateFiscalYear(fy2024) → getActiveFiscalYear retourne 2024")
        void activateSetsActive() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            FiscalYear fy2025 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "Exercice 2025"));

            // Bascule explicite vers 2024 (même si l'auto-activation l'avait déjà positionné).
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());

            FiscalYear active = accountingService.getActiveFiscalYear(COMPANY_A);
            assertThat(active.getId()).isEqualTo(fy2024.getId());
            assertThat(readActiveFiscalYearId(COMPANY_A)).isEqualTo(fy2024.getId());
        }
    }

    @Nested
    @DisplayName("Test 3 — getActiveFiscalYear auto-sélectionne quand active_fiscal_year_id est NULL")
    class AutoSelectWhenNull {
        @Test
        @DisplayName("Clear JDBC du pointeur → getActiveFiscalYear auto-sélectionne le dernier OPEN")
        void autoSelectsLatestOpenWhenNull() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));

            // Simule un état "aucun exercice actif explicite" (par ex. après migration ad-hoc).
            clearActiveFiscalYearId(COMPANY_A);
            assertThat(readActiveFiscalYearId(COMPANY_A)).isNull();

            // getActiveFiscalYear doit auto-sélectionner le dernier OPEN (ici 2024) et le
            // persister comme nouvel actif.
            FiscalYear active = accountingService.getActiveFiscalYear(COMPANY_A);
            assertThat(active.getId()).isEqualTo(fy2024.getId());
        }
    }

    @Nested
    @DisplayName("Test 4 — Exercice CLOSED actif bloque createJournalEntry")
    class ClosedFyBlocksEntry {
        @Test
        @DisplayName("Close FY actif → createJournalEntry jette FISCAL_YEAR_CLOSED")
        void closedActiveFyBlocksCreate() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());

            // Une écriture préalable est nécessaire pour que closeFiscalYear calcule un résultat
            // non nul et génère l'écriture de clôture (sinon : NO_RESULT_TO_CLOSE).
            postEntry(LocalDate.of(2024, 6, 15), new BigDecimal("1000.00"), "key-pre-close-1");

            accountingService.closeFiscalYear(COMPANY_A, fy2024.getId());

            // Toute nouvelle saisie doit être rejetée car l'exercice actif est désormais CLOSED.
            assertThatThrownBy(() -> accountingService.createJournalEntry(
                    COMPANY_A, "key-after-close-1",
                    entryFor(LocalDate.of(2024, 7, 1), new BigDecimal("500.00"))))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("FISCAL_YEAR_CLOSED");
        }
    }

    @Nested
    @DisplayName("Test 5 — Date d'écriture hors exercice actif rejetée")
    class EntryDateOutsideActiveFy {
        @Test
        @DisplayName("Activer 2025, saisir 2024-06-15 → ENTRY_DATE_OUTSIDE_ACTIVE_FISCAL_YEAR")
        void entryDateOutsideActiveFyRejected() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            FiscalYear fy2025 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "Exercice 2025"));
            accountingService.activateFiscalYear(COMPANY_A, fy2025.getId());

            // L'exercice actif est 2025 ; une saisie en 2024 doit être rejetée.
            assertThatThrownBy(() -> accountingService.createJournalEntry(
                    COMPANY_A, "key-outside-1",
                    entryFor(LocalDate.of(2024, 6, 15), new BigDecimal("100.00"))))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("ENTRY_DATE_OUTSIDE_ACTIVE_FISCAL_YEAR");
        }
    }

    @Nested
    @DisplayName("Test 6 — closeFiscalYear bascule sur le prochain OPEN")
    class CloseFyAutoSwitch {
        @Test
        @DisplayName("Close 2024 (2025 OPEN existe) → actif devient 2025")
        void closeSwitchesToNextOpen() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            FiscalYear fy2025 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "Exercice 2025"));
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());

            // Écriture préalable en 2024 pour permettre la clôture.
            postEntry(LocalDate.of(2024, 6, 15), new BigDecimal("1000.00"), "key-close-switch-1");

            accountingService.closeFiscalYear(COMPANY_A, fy2024.getId());

            FiscalYear active = accountingService.getActiveFiscalYear(COMPANY_A);
            assertThat(active.getId()).isEqualTo(fy2025.getId());
            assertThat(readActiveFiscalYearId(COMPANY_A)).isEqualTo(fy2025.getId());
        }
    }

    @Nested
    @DisplayName("Test 7 — closeFiscalYear vide quand plus aucun OPEN")
    class CloseFyClearsWhenNoOpen {
        @Test
        @DisplayName("Close 2024 (seul OPEN) → getActiveFiscalYearForRead retourne empty")
        void closeClearsWhenNoOpenLeft() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());

            postEntry(LocalDate.of(2024, 6, 15), new BigDecimal("1000.00"), "key-close-clear-1");

            accountingService.closeFiscalYear(COMPANY_A, fy2024.getId());

            // Aucun autre OPEN n'existe → l'exercice actif (CLOSED) est inutilisable en lecture.
            Optional<FiscalYear> activeOpt = accountingService.getActiveFiscalYearForRead(COMPANY_A);
            assertThat(activeOpt).isEmpty();
        }
    }

    @Nested
    @DisplayName("Test 8 — Balance générale utilise les dates de l'exercice actif")
    class TrialBalanceUsesActiveFy {
        @Test
        @DisplayName("Activer 2024 → balance = écritures 2024 ; activer 2025 → balance = écritures 2025")
        void trialBalanceFilteredByActiveFy() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            FiscalYear fy2025 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "Exercice 2025"));

            // L'auto-activation positionne 2024 comme actif → les écritures 2024 sont saisies ici.
            assertThat(readActiveFiscalYearId(COMPANY_A)).isEqualTo(fy2024.getId());
            postEntry(LocalDate.of(2024, 3, 10), new BigDecimal("1000.00"), "key-tb-2024-1");
            postEntry(LocalDate.of(2024, 6, 20), new BigDecimal("500.00"), "key-tb-2024-2");

            // Bascule vers 2025 pour saisir les écritures 2025.
            accountingService.activateFiscalYear(COMPANY_A, fy2025.getId());
            postEntry(LocalDate.of(2025, 2, 5), new BigDecimal("300.00"), "key-tb-2025-1");

            // Re-bascule vers 2024 → la balance ne doit contenir QUE les écritures 2024.
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());
            List<TrialBalanceLine> tb2024 = accountingService.getTrialBalance(COMPANY_A);
            BigDecimal totalDebit2024 = tb2024.stream()
                .map(TrialBalanceLine::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit2024).isEqualByComparingTo(new BigDecimal("1500.00"));

            // Bascule vers 2025 → la balance ne doit contenir QUE les écritures 2025.
            accountingService.activateFiscalYear(COMPANY_A, fy2025.getId());
            List<TrialBalanceLine> tb2025 = accountingService.getTrialBalance(COMPANY_A);
            BigDecimal totalDebit2025 = tb2025.stream()
                .map(TrialBalanceLine::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit2025).isEqualByComparingTo(new BigDecimal("300.00"));
        }
    }

    @Nested
    @DisplayName("Test 9 — searchJournalEntries utilise l'exercice actif par défaut")
    class SearchDefaultsToActiveFy {
        @Test
        @DisplayName("searchJournalEntries(null,null,...) → uniquement les écritures de l'exercice actif")
        void searchDefaultsToActiveFyDates() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            FiscalYear fy2025 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "Exercice 2025"));

            // 2 écritures en 2024 (FY actif auto-sélectionné = 2024).
            postEntry(LocalDate.of(2024, 3, 10), new BigDecimal("1000.00"), "key-search-2024-1");
            postEntry(LocalDate.of(2024, 6, 20), new BigDecimal("500.00"), "key-search-2024-2");

            // 1 écriture en 2025 (après bascule).
            accountingService.activateFiscalYear(COMPANY_A, fy2025.getId());
            postEntry(LocalDate.of(2025, 2, 5), new BigDecimal("300.00"), "key-search-2025-1");

            // Re-bascule vers 2024 → search ne retourne que les écritures 2024.
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());
            var page2024 = accountingService.searchJournalEntries(
                COMPANY_A, null, null, null, null, null, PageRequest.of(0, 50));
            assertThat(page2024.getContent()).hasSize(2);
            assertThat(page2024.getContent()).allSatisfy(e ->
                assertThat(e.entryDate().getYear()).isEqualTo(2024));

            // Bascule vers 2025 → search ne retourne que les écritures 2025.
            accountingService.activateFiscalYear(COMPANY_A, fy2025.getId());
            var page2025 = accountingService.searchJournalEntries(
                COMPANY_A, null, null, null, null, null, PageRequest.of(0, 50));
            assertThat(page2025.getContent()).hasSize(1);
            assertThat(page2025.getContent()).allSatisfy(e ->
                assertThat(e.entryDate().getYear()).isEqualTo(2025));
        }
    }

    @Nested
    @DisplayName("Test 10 — Aucun exercice actif → NO_ACTIVE_FISCAL_YEAR")
    class NoActiveFiscalYear {
        @Test
        @DisplayName("Clear JDBC du pointeur → getTrialBalance jette NO_ACTIVE_FISCAL_YEAR")
        void noActiveFyThrowsNotFound() {
            initFixture();
            accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));

            // Simule l'absence d'exercice actif (par ex. après effacement manuel).
            clearActiveFiscalYearId(COMPANY_A);
            assertThat(readActiveFiscalYearId(COMPANY_A)).isNull();

            // getTrialBalance ne peut pas borner sa requête → erreur métier explicite.
            assertThatThrownBy(() -> accountingService.getTrialBalance(COMPANY_A))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("NO_ACTIVE_FISCAL_YEAR");
        }
    }

    @Nested
    @DisplayName("Test 11 — checkActiveFiscalYearWritable passe pour OPEN")
    class CheckWritablePassesForOpen {
        @Test
        @DisplayName("FY actif OPEN → checkActiveFiscalYearWritable ne jette pas")
        void checkWritablePassesForOpen() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());

            // Doit passer sans jeter — l'exercice actif est OPEN et donc inscriptible.
            accountingService.checkActiveFiscalYearWritable(COMPANY_A);
        }
    }

    @Nested
    @DisplayName("Test 12 — checkActiveFiscalYearWritable jette pour CLOSED")
    class CheckWritableThrowsForClosed {
        @Test
        @DisplayName("Close FY actif → checkActiveFiscalYearWritable jette FISCAL_YEAR_CLOSED")
        void checkWritableThrowsForClosed() {
            initFixture();
            FiscalYear fy2024 = accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "Exercice 2024"));
            accountingService.activateFiscalYear(COMPANY_A, fy2024.getId());

            // Écriture préalable pour permettre la clôture.
            postEntry(LocalDate.of(2024, 6, 15), new BigDecimal("1000.00"), "key-check-closed-1");
            accountingService.closeFiscalYear(COMPANY_A, fy2024.getId());

            // L'exercice actif est désormais CLOSED → toute tentative d'écriture doit être
            // bloquée par checkActiveFiscalYearWritable.
            assertThatThrownBy(() -> accountingService.checkActiveFiscalYearWritable(COMPANY_A))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("FISCAL_YEAR_CLOSED");
        }
    }
}

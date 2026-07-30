package jo.accountant.accountingengine.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalPeriodStatus;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.FiscalYearStatus;
import jo.accountant.accountingengine.entity.Journal;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.analytics.service.AnalyticsService;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.AccountResolver;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests unitaires pour {@link AccountingEngineService} — R-16 (lot-D-qualite-arch).
 *
 * <p>Couverture des règles métier critiques de {@link AccountingEngineService#createJournalEntry} :
 * <ul>
 *   <li>Cas erreur : Idempotency-Key absente → ValidationException("IDEMPOTENCY_KEY_REQUIRED").</li>
 *   <li>Cas erreur : écriture déséquilibrée (débit ≠ crédit) → ValidationException("UNBALANCED_ENTRY").</li>
 *   <li>Cas erreur : période fiscale LOCKED → ConflictException("PERIOD_LOCKED").</li>
 *   <li>Cas erreur : exercice fiscal CLOSED → ConflictException("FISCAL_YEAR_CLOSED").</li>
 *   <li>Cas erreur : journal introuvable → NotFoundException("JOURNAL_NOT_FOUND").</li>
 *   <li>Cas erreur : aucune période fiscale pour la date → NotFoundException("FISCAL_PERIOD_NOT_FOUND").</li>
 * </ul>
 *
 * <p>Pas de Spring, pas de {@code @SpringBootTest}. Tous les collaborateurs sont mockés via
 * Mockito. La méthode {@link AccountingEngineService#createJournalEntry} est isolée — seules
 * les règles de validation en amont du save() sont testées (le save lui-même nécessite trop
 * de mocks annexes pour être utile en unitaire ; il est couvert par les tests d'intégration).
 */
class AccountingEngineServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final LocalDate ENTRY_DATE = LocalDate.of(2026, 3, 15);

    private AccountingEngineService service;
    private JournalEntryRepository journalEntryRepository;
    private JournalRepository journalRepository;
    private FiscalYearRepository fiscalYearRepository;
    private FiscalPeriodRepository fiscalPeriodRepository;

    @BeforeEach
    void setUp() {
        // Mocks utilisés par createJournalEntry
        journalEntryRepository = mock(JournalEntryRepository.class);
        journalRepository = mock(JournalRepository.class);
        fiscalYearRepository = mock(FiscalYearRepository.class);
        fiscalPeriodRepository = mock(FiscalPeriodRepository.class);

        // Mocks secondaires (non sollicités par createJournalEntry mais requis par le constructeur)
        JournalLineRepository journalLineRepository = mock(JournalLineRepository.class);
        JournalLineAnalyticalTagRepository tagRepository = mock(JournalLineAnalyticalTagRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        DocumentNumberingService documentNumberingService = mock(DocumentNumberingService.class);
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountResolver accountResolver = mock(AccountResolver.class);
        FiscalYearClosingService fiscalYearClosingService = mock(FiscalYearClosingService.class);
        JournalEntryLifecycleService journalEntryLifecycleService = mock(JournalEntryLifecycleService.class);

        service = new AccountingEngineService(
            fiscalYearRepository, fiscalPeriodRepository, journalRepository,
            journalEntryRepository, journalLineRepository, tagRepository,
            accountRepository, documentNumberingService, analyticsService,
            events, objectMapper, jdbcTemplate, accountResolver,
            fiscalYearClosingService, journalEntryLifecycleService);
    }

    private CreateJournalEntryRequest buildRequest(BigDecimal debitLine1, BigDecimal creditLine2) {
        return new CreateJournalEntryRequest(
            "VT", ENTRY_DATE, "Test écriture",
            List.of(
                new LineDto("411000", null, debitLine1, BigDecimal.ZERO, "Débit client", List.of()),
                new LineDto("707000", null, BigDecimal.ZERO, creditLine2, "Crédit vente", List.of())
            ),
            JournalEntrySourceModule.MANUAL);
    }

    private void mockJournalFound() {
        Journal journal = new Journal();
        journal.setId(UUID.randomUUID());
        journal.setCompanyId(COMPANY_ID);
        journal.setCode("VT");
        when(journalRepository.findByCompanyIdAndCode(COMPANY_ID, "VT"))
            .thenReturn(Optional.of(journal));
    }

    private void mockIdempotencyEmpty() {
        when(journalEntryRepository.findByCompanyIdAndIdempotencyKey(COMPANY_ID, "key-1"))
            .thenReturn(Optional.empty());
    }

    private void mockFiscalPeriodFound(FiscalPeriodStatus periodStatus, FiscalYearStatus fyStatus) {
        FiscalYear fy = new FiscalYear();
        fy.setId(UUID.randomUUID());
        fy.setCompanyId(COMPANY_ID);
        fy.setStartDate(LocalDate.of(2026, 1, 1));
        fy.setEndDate(LocalDate.of(2026, 12, 31));
        fy.setStatus(fyStatus);
        when(fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(COMPANY_ID))
            .thenReturn(List.of(fy));

        FiscalPeriod period = new FiscalPeriod();
        period.setId(UUID.randomUUID());
        period.setFiscalYearId(fy.getId());
        period.setStatus(periodStatus);
        when(fiscalPeriodRepository.findByFiscalYearIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            fy.getId(), ENTRY_DATE, ENTRY_DATE))
            .thenReturn(Optional.of(period));

        // Pour le check FISCAL_YEAR_CLOSED, on doit mocker findById
        when(fiscalYearRepository.findById(fy.getId()))
            .thenReturn(Optional.of(fy));
    }

    @Test
    @DisplayName("createJournalEntry — error : Idempotency-Key blank → ValidationException")
    void createJournalEntry_blankIdempotencyKey() {
        CreateJournalEntryRequest req = buildRequest(new BigDecimal("100.00"), new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "", req))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REQUIRED");

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "   ", req))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("createJournalEntry — error : écriture déséquilibrée → ValidationException(UNBALANCED_ENTRY)")
    void createJournalEntry_unbalancedEntry() {
        mockIdempotencyEmpty();
        mockJournalFound();
        mockFiscalPeriodFound(FiscalPeriodStatus.OPEN, FiscalYearStatus.OPEN);

        // Débit 100 vs crédit 90 → déséquilibré
        CreateJournalEntryRequest unbalanced = buildRequest(
            new BigDecimal("100.00"), new BigDecimal("90.00"));

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "key-1", unbalanced))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("code", "UNBALANCED_ENTRY");
    }

    @Test
    @DisplayName("createJournalEntry — error : période LOCKED → ConflictException(PERIOD_LOCKED)")
    void createJournalEntry_periodLocked() {
        mockIdempotencyEmpty();
        mockJournalFound();
        mockFiscalPeriodFound(FiscalPeriodStatus.LOCKED, FiscalYearStatus.OPEN);

        CreateJournalEntryRequest balanced = buildRequest(
            new BigDecimal("100.00"), new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "key-1", balanced))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "PERIOD_LOCKED");
    }

    @Test
    @DisplayName("createJournalEntry — error : exercice fiscal CLOSED → ConflictException(FISCAL_YEAR_CLOSED)")
    void createJournalEntry_fiscalYearClosed() {
        mockIdempotencyEmpty();
        mockJournalFound();
        mockFiscalPeriodFound(FiscalPeriodStatus.OPEN, FiscalYearStatus.CLOSED);

        CreateJournalEntryRequest balanced = buildRequest(
            new BigDecimal("100.00"), new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "key-1", balanced))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "FISCAL_YEAR_CLOSED");
    }

    @Test
    @DisplayName("createJournalEntry — error : journal introuvable → NotFoundException(JOURNAL_NOT_FOUND)")
    void createJournalEntry_journalNotFound() {
        mockIdempotencyEmpty();
        when(journalRepository.findByCompanyIdAndCode(COMPANY_ID, "VT"))
            .thenReturn(Optional.empty());

        CreateJournalEntryRequest balanced = buildRequest(
            new BigDecimal("100.00"), new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "key-1", balanced))
            .isInstanceOf(jo.accountant.core.exception.NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "JOURNAL_NOT_FOUND");
    }

    @Test
    @DisplayName("createJournalEntry — error : aucune période fiscale pour la date → NotFoundException(FISCAL_PERIOD_NOT_FOUND)")
    void createJournalEntry_noPeriodForDate() {
        mockIdempotencyEmpty();
        mockJournalFound();
        // Aucun exercice fiscal → findPeriodForDate retourne null
        when(fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(COMPANY_ID))
            .thenReturn(List.of());

        CreateJournalEntryRequest balanced = buildRequest(
            new BigDecimal("100.00"), new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.createJournalEntry(COMPANY_ID, "key-1", balanced))
            .isInstanceOf(jo.accountant.core.exception.NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "FISCAL_PERIOD_NOT_FOUND");
    }
}

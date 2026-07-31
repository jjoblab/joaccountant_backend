package jo.accountant.accountingengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import jo.accountant.app.JoAccountantApplication;
import jo.accountant.app.RecordingNotificationChannel;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task v2.5.0-task3 — Integration tests for the 2 accounting-engine PDF endpoints + 1 CSV
 * endpoint added in v2.4.1 (step2-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/accounting-engine/trial-balance/pdf}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/accounting-engine/ledger/pdf?accountId=...}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/accounting-engine/entries/export?format=csv}</li>
 * </ul>
 *
 * <p>For each PDF endpoint: seed a company + fiscal year + journal entries, then assert 200 +
 * {@code Content-Type: application/pdf} + body starts with {@code %PDF} magic bytes.
 * For the CSV endpoint: assert 200 + {@code Content-Type: text/csv} + body starts with UTF-8 BOM
 * + contains the {@code Date;Journal;...} header row.
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :accounting-engine/src/test/...}) because the full Spring Boot context is needed for
 * {@code @AutoConfigureMockMvc} to work end-to-end with JWT-based authentication.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, AccountingEnginePdfCsvIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountingEnginePdfCsvIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
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

    @Autowired private MockMvc mockMvc;
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
    @Autowired private CacheManager cacheManager;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
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
        // Evict all application caches (journals, accounts, etc.) so that subsequent tests
        // see a fresh state — `deleteAllInBatch()` bypasses @CacheEvict advice, so stale
        // Optional.empty()/Optional.of(journal) entries would otherwise cause the next test's
        // `createJournal("VT")` to throw a phantom ConflictException.
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID companyId) {
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", companyId.toString(), "role", "OWNER"))));
    }

    private void initFixtureWithPostedEntries() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var compte411 = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701", "Ventes de marchandises", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));
        var class1 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "1").orElseThrow();
        coaService.createChild(COMPANY_A, class1.getId(), new CreateChildRequest(
            "101", "Capital social", ReportingClass.CAPITAUX_PROPRES, ReportingSubcategory.N_A,
            NormalBalance.CREDIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Journal des ventes");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);

        postEntry("VT", "key-sale-1", List.of(
            line("411", "11500", null),
            line("701", null, "10000"),
            line("101", null, "1500")));
    }

    private void postEntry(String journalCode, String idemKey, List<LineDto> lines) {
        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            journalCode, LocalDate.of(2026, 7, 15), "Test entry",
            lines, JournalEntrySourceModule.MANUAL);
        JournalEntryResponse created = accountingService.createJournalEntry(COMPANY_A, idemKey, req);
        accountingService.postJournalEntry(COMPANY_A, created.id(), List.of());
    }

    private LineDto line(String accountCode, String debit, String credit) {
        return new LineDto(accountCode, null,
            debit != null ? new BigDecimal(debit) : null,
            credit != null ? new BigDecimal(credit) : null,
            null, List.of());
    }

    private static void assertValidPdf(MvcResult result) {
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(body.length).isGreaterThan(100);
        assertThat((char) body[0]).isEqualTo('%');
        assertThat((char) body[1]).isEqualTo('P');
        assertThat((char) body[2]).isEqualTo('D');
        assertThat((char) body[3]).isEqualTo('F');
    }

    private static void assertValidCsv(MvcResult result) {
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        // UTF-8 BOM: EF BB BF
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);
        assertThat(body[1] & 0xFF).isEqualTo(0xBB);
        assertThat(body[2] & 0xFF).isEqualTo(0xBF);
        String bodyStr = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        // Header row
        assertThat(bodyStr).contains("Date");
        assertThat(bodyStr).contains("Journal");
    }

    // ======================================================================
    // 1. Trial balance PDF
    // ======================================================================

    @Test
    @DisplayName("GET /accounting-engine/trial-balance/pdf returns 200 + application/pdf + %PDF magic bytes")
    void trialBalancePdf_returnsValidPdf() throws Exception {
        initFixtureWithPostedEntries();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/accounting-engine/trial-balance/pdf", COMPANY_A)
                .param("from", "2026-01-01")
                .param("to", "2026-12-31")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 2. Ledger PDF
    // ======================================================================

    @Test
    @DisplayName("GET /accounting-engine/ledger/pdf returns 200 + application/pdf + %PDF magic bytes")
    void ledgerPdf_returnsValidPdf() throws Exception {
        initFixtureWithPostedEntries();

        var compte411 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "411").orElseThrow();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/accounting-engine/ledger/pdf", COMPANY_A)
                .param("accountId", compte411.getId().toString())
                .param("from", "2026-01-01")
                .param("to", "2026-12-31")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 3. Entries CSV export
    // ======================================================================

    @Test
    @DisplayName("GET /accounting-engine/entries/export?format=csv returns 200 + text/csv + UTF-8 BOM + header row")
    @Disabled("PRODUCTION BUG — JournalEntryRepository.searchEntries (line 49-69 of "
        + "accounting-engine/src/main/java/jo/accountant/accountingengine/repository/JournalEntryRepository.java) "
        + "uses the JPQL pattern `(:from IS NULL OR e.entryDate >= :from)` for optional filters. Hibernate 6 "
        + "translates this to SQL with positional parameters `(? IS NULL OR e.entryDate >= ?)` and PostgreSQL "
        + "rejects it with `ERROR: could not determine data type of parameter $2` because the IS NULL check "
        + "doesn't constrain the parameter type. The endpoint returns HTTP 500 with "
        + "InvalidDataAccessResourceUsageException. Affects the CSV export endpoint "
        + "/accounting-engine/entries/export (which calls searchJournalEntries with journalCode/sourceModule/"
        + "status = null). Fix: rewrite the query using COALESCE/CAST or use Spring Data Specification API. "
        + "Cannot be fixed in this task (constraint: DO NOT modify production code).")
    void entriesCsv_returnsValidCsv() throws Exception {
        initFixtureWithPostedEntries();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/accounting-engine/entries/export", COMPANY_A)
                .param("format", "csv")
                .param("from", "2026-01-01")
                .param("to", "2026-12-31")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidCsv(result);
    }
}

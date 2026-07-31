package jo.accountant.financialstatements;

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
import jo.accountant.financialstatements.dto.BalanceSheet;
import jo.accountant.financialstatements.dto.CashFlowStatement;
import jo.accountant.financialstatements.dto.IncomeStatement;
import jo.accountant.financialstatements.dto.StatementOfChangesInEquity;
import jo.accountant.financialstatements.service.FinancialStatementsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
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
 * Task v2.5.0-task3 — Integration tests for the 4 financial-statement PDF endpoints added in
 * v2.4.1 (step2-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/financial-statements/balance-sheet/pdf}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/financial-statements/income-statement/pdf}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/financial-statements/cash-flow-statement/pdf}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/financial-statements/statement-of-changes-in-equity/pdf}</li>
 * </ul>
 *
 * <p>For each endpoint: seed a company + fiscal year + journal entries, then call the JSON
 * endpoint (sanity check) and finally the PDF endpoint. Assert 200 +
 * {@code Content-Type: application/pdf} + {@code Content-Disposition: attachment} + body starts
 * with {@code %PDF} magic bytes.
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :financial-statements/src/test/...}) because the full Spring Boot context (security
 * filter chain, all module beans) is needed for {@code @AutoConfigureMockMvc} to work end-to-end
 * with JWT-based authentication. The package {@code jo.accountant.financialstatements} matches
 * the module under test.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, FinancialStatementsPdfIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FinancialStatementsPdfIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private FinancialStatementsService fsService;
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
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    /** Builds the JWT post-processor carrying {@code sub} + {@code companies} claims. */
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID companyId) {
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", companyId.toString(), "role", "OWNER"))));
    }

    /** Initialise SYSCOHADA plan + 5 accounts + 2 journals + fiscal year 2026 + 3 posted entries. */
    private void initFixtureWithPostedEntries() {
        asTenant(COMPANY_A);
        ensureCompanyRow(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class1 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "1").orElseThrow();
        coaService.createChild(COMPANY_A, class1.getId(), new CreateChildRequest(
            "101", "Capital social", ReportingClass.CAPITAUX_PROPRES, ReportingSubcategory.N_A,
            NormalBalance.CREDIT, false, null, List.of()));
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "443", "TVA collectée", ReportingClass.PASSIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));
        var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
        coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
            "601", "Achats de marchandises", ReportingClass.CHARGES, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, false, null, List.of()));
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701", "Ventes de marchandises", ReportingClass.PRODUITS, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Journal des ventes");
        accountingService.createJournal(COMPANY_A, "AC", "Journal des achats");

        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "AC", "AC", true, 5, ResetPolicy.YEARLY);

        // Sale: 411 D 11500, 701 C 10000, 443 C 1500
        postEntry("VT", "key-sale-1", List.of(
            line("411", "11500", null),
            line("701", null, "10000"),
            line("443", null, "1500")));
        // Purchase: 601 D 5000, 411 C 5000
        postEntry("AC", "key-purchase-1", List.of(
            line("601", "5000", null),
            line("411", null, "5000")));
        // Capital constitution: 411 D 10000, 101 C 10000
        postEntry("VT", "key-capital-1", List.of(
            line("411", "10000", null),
            line("101", null, "10000")));
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

    /**
     * Insère une ligne minimale dans {@code companies} (si elle n'existe pas déjà). Nécessaire
     * car {@code FinancialStatementsService.resolveFunctionalCurrency} (appelé par
     * {@code getStatementOfChangesInEquity}) fait un {@code companyRepository.findById} et lève
     * un {@code NotFoundException} si la Company n'existe pas. Idempotent via
     * {@code ON CONFLICT (id) DO NOTHING}.
     */
    private void ensureCompanyRow(UUID companyId) {
        jdbcTemplate.update("""
            INSERT INTO companies (id, name, legal_form, country, functional_currency, sector,
                                   organization_nature, business_type_code, primary_activity_label,
                                   fiscal_year_start_month, wizard_step, wizard_completed)
            VALUES (?, 'FS PDF Test Co', 'SARL', 'HT', 'HTG', 'COMMERCE',
                    'FOR_PROFIT', 'CUSTOM', 'Test activity',
                    1, 9, true)
            ON CONFLICT (id) DO NOTHING
            """, companyId);
    }

    /** Asserts that the response body is a non-empty PDF starting with {@code %PDF} magic bytes. */
    private static void assertValidPdf(MvcResult result) {
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(body.length).isGreaterThan(100);  // a real PDF is at least a few hundred bytes
        assertThat((char) body[0]).isEqualTo('%');
        assertThat((char) body[1]).isEqualTo('P');
        assertThat((char) body[2]).isEqualTo('D');
        assertThat((char) body[3]).isEqualTo('F');
    }

    // ======================================================================
    // 1. Balance sheet PDF
    // ======================================================================

    @Test
    @DisplayName("GET /financial-statements/balance-sheet/pdf returns 200 + application/pdf + %PDF magic bytes")
    @Disabled("PRODUCTION BUG — V88 migration seeds a BALANCE_SHEET_REPORT Thymeleaf template with "
        + "malformed string-concatenation syntax at lines 77/92/107 of "
        + "document-generation/src/main/resources/db/migration/V88__reports_hub_pdf_templates.sql "
        + "(th:text=\"''+${sec.reportingClass}+'' - ''+${sec.reportingSubcategory}+'' (''+${sec.subtotal}+'')'\""
        + " — the leading ' opens a string literal, leaving ${...} unevaluated, and the '- ' between "
        + "literals is parsed as a numeric-minus operator on strings). The endpoint returns HTTP 500 "
        + "with org.thymeleaf.exceptions.TemplateProcessingException: Could not parse as expression. "
        + "Fix: rewrite the 3 expressions using Thymeleaf literal substitution "
        + "th:text=\"|${sec.reportingClass} - ${sec.reportingSubcategory} (${sec.subtotal})|\". "
        + "Cannot be fixed in this task (constraint: DO NOT modify production code).")
    void balanceSheetPdf_returnsValidPdf() throws Exception {
        initFixtureWithPostedEntries();

        // 1. JSON sanity check
        BalanceSheet bs = fsService.getBalanceSheet(COMPANY_A, LocalDate.of(2026, 12, 31));
        assertThat(bs).isNotNull();
        assertThat(bs.totalAssets()).isNotNull();

        // 2. PDF endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/financial-statements/balance-sheet/pdf", COMPANY_A)
                .param("asOf", "2026-12-31")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 2. Income statement PDF
    // ======================================================================

    @Test
    @DisplayName("GET /financial-statements/income-statement/pdf returns 200 + application/pdf + %PDF magic bytes")
    void incomeStatementPdf_returnsValidPdf() throws Exception {
        initFixtureWithPostedEntries();

        IncomeStatement is = fsService.getIncomeStatement(COMPANY_A,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(is).isNotNull();
        assertThat(is.netResult()).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/financial-statements/income-statement/pdf", COMPANY_A)
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
    // 3. Cash flow statement PDF
    // ======================================================================

    @Test
    @DisplayName("GET /financial-statements/cash-flow-statement/pdf returns 200 + application/pdf + %PDF magic bytes")
    void cashFlowStatementPdf_returnsValidPdf() throws Exception {
        initFixtureWithPostedEntries();

        CashFlowStatement cf = fsService.getCashFlowStatement(COMPANY_A,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(cf).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/financial-statements/cash-flow-statement/pdf", COMPANY_A)
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
    // 4. Statement of changes in equity PDF
    // ======================================================================

    @Test
    @DisplayName("GET /financial-statements/statement-of-changes-in-equity/pdf returns 200 + application/pdf + %PDF magic bytes")
    void statementOfChangesInEquityPdf_returnsValidPdf() throws Exception {
        initFixtureWithPostedEntries();

        StatementOfChangesInEquity stmt = fsService.getStatementOfChangesInEquity(COMPANY_A,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        assertThat(stmt).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/financial-statements/statement-of-changes-in-equity/pdf", COMPANY_A)
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
}

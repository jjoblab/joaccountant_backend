package jo.accountant.thirdparties;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task v2.5.0-task3 — Integration tests for the third-parties PDF + CSV endpoints added in
 * v2.4.1 / v2.4.2 (step2-backend / step7-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/third-parties/lettrage/pdf} (v2.4.2)</li>
 *   <li>{@code GET /api/v1/companies/{cid}/third-parties/export?format=csv} (v2.4.1)</li>
 * </ul>
 *
 * <p>For the PDF: seed a third party + 2 journal entries (invoice + payment), then lettrage them,
 * then call the PDF endpoint and assert 200 + {@code Content-Type: application/pdf} + body starts
 * with {@code %PDF} magic bytes.
 *
 * <p>For the CSV: seed at least one third party, then call the CSV endpoint and assert 200 +
 * {@code Content-Type: text/csv} + UTF-8 BOM + header row containing {@code Type;Nom;...}.
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :third-parties/src/test/...}) because the full Spring Boot context is needed for
 * {@code @AutoConfigureMockMvc} to work end-to-end with JWT-based authentication.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, LettrageAndCsvIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LettrageAndCsvIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private ThirdPartiesService tpService;
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
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
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

    /** Initialise plan + compte collectif 411000 + journal VT + exercice + 1 client. */
    private ThirdPartyResponse initFixtureWithClient() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
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
        return tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.CLIENT, "Boutique Pétion-Ville",
            compte411000.getId(), "client@test.dev", "Pétion-Ville, Haïti"));
    }

    private UUID postEntryWithThirdParty(String journalCode, String idemKey, String accountCode,
                                          UUID thirdPartyId, BigDecimal debit, BigDecimal credit) {
        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            journalCode, LocalDate.of(2026, 7, 15), "Test entry",
            List.of(new LineDto(accountCode, thirdPartyId, debit, credit, null, List.of()),
                    new LineDto("701000", null, credit, debit, null, List.of())),
            JournalEntrySourceModule.MANUAL);
        JournalEntryResponse created = accountingService.createJournalEntry(COMPANY_A, idemKey, req);
        accountingService.postJournalEntry(COMPANY_A, created.id(), List.of());
        return created.id();
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
        assertThat(bodyStr).contains("Type");
        assertThat(bodyStr).contains("Nom");
    }

    // ======================================================================
    // 1. Lettrage PDF
    // ======================================================================
    //
    // The lettrage/pdf endpoint lists all lettrages (FULL + PARTIAL) for the company.
    // It returns a PDF even if no lettrages exist (empty list → empty table).
    // To make the test more meaningful, we seed a lettrage before calling the endpoint.

    @Test
    @DisplayName("GET /third-parties/lettrage/pdf returns 200 + application/pdf + %PDF magic bytes")
    void lettragePdf_returnsValidPdf() throws Exception {
        ThirdPartyResponse tp = initFixtureWithClient();

        // Post an invoice (debit dedicated account) and a payment (credit dedicated account)
        // using the third party's dedicated account code.
        postEntryWithThirdParty("VT", "key-inv-1", tp.dedicatedAccountCode(),
            tp.id(), new BigDecimal("2000"), null);
        postEntryWithThirdParty("VT", "key-pay-1", tp.dedicatedAccountCode(),
            tp.id(), null, new BigDecimal("2000"));

        // Retrieve the journal line IDs via the third party statement
        ThirdPartyStatement stmt = tpService.getStatement(COMPANY_A, tp.id(), null, null);
        assertThat(stmt.lines()).hasSize(2);
        List<UUID> lineIds = stmt.lines().stream()
            .map(ThirdPartyStatement.StatementLine::journalLineId).toList();

        // Perform the lettrage
        LettrageResponse lettrage = tpService.lettrer(COMPANY_A, new LettrageRequest(tp.id(), lineIds));
        assertThat(lettrage).isNotNull();
        assertThat(lettrage.status()).isEqualTo(LettrageStatus.FULL);

        // Now call the PDF endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/third-parties/lettrage/pdf", COMPANY_A)
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
    // 2. Third parties CSV export
    // ======================================================================

    @Test
    @DisplayName("GET /third-parties/export?format=csv returns 200 + text/csv + UTF-8 BOM + header row")
    void thirdPartiesCsv_returnsValidCsv() throws Exception {
        ThirdPartyResponse tp = initFixtureWithClient();
        assertThat(tp).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/third-parties/export", COMPANY_A)
                .param("format", "csv")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidCsv(result);
    }
}

package jo.accountant.chartofaccounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.app.JoAccountantApplication;
import jo.accountant.app.RecordingNotificationChannel;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task v2.5.0-task3 — Integration test for the chart-of-accounts CSV endpoint added in v2.4.1
 * (step2-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/chart-of-accounts/export?format=csv}</li>
 * </ul>
 *
 * <p>Seed the SYSCOHADA chart of accounts (creates the 7 top-level classes 1..7), then call the
 * CSV endpoint and assert 200 + {@code Content-Type: text/csv} + UTF-8 BOM + header row
 * containing {@code Code;Libelle;Classe;...}.
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :chart-of-accounts/src/test/...}) because the full Spring Boot context is needed for
 * {@code @AutoConfigureMockMvc} to work end-to-end with JWT-based authentication.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ChartOfAccountsCsvIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChartOfAccountsCsvIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            accountRepo.deleteAllInBatch();
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

    // ======================================================================
    // 1. Chart of accounts CSV export (with seeded COA)
    // ======================================================================

    @Test
    @DisplayName("GET /chart-of-accounts/export?format=csv returns 200 + text/csv + UTF-8 BOM + header row")
    void chartOfAccountsCsv_returnsValidCsv() throws Exception {
        // 1. Seed the SYSCOHADA chart of accounts (creates 7 top-level classes)
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        // 2. Call the CSV endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/chart-of-accounts/export", COMPANY_A)
                .param("format", "csv")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        // 3. Verify the body
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        // UTF-8 BOM: EF BB BF
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);
        assertThat(body[1] & 0xFF).isEqualTo(0xBB);
        assertThat(body[2] & 0xFF).isEqualTo(0xBF);
        String bodyStr = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        // Header row
        assertThat(bodyStr).contains("Code");
        assertThat(bodyStr).contains("Libelle");
        assertThat(bodyStr).contains("Classe");
        // At least one account line (the 7 SYSCOHADA classes)
        assertThat(bodyStr).contains("1");
        assertThat(bodyStr).contains("2");
    }

    // ======================================================================
    // 2. Chart of accounts CSV export — empty COA still returns valid CSV
    // ======================================================================

    @Test
    @DisplayName("GET /chart-of-accounts/export?format=csv with empty COA returns 200 + valid CSV (header only)")
    void chartOfAccountsCsv_emptyCoa_returnsValidCsv() throws Exception {
        // No COA initialized — empty account list
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/chart-of-accounts/export", COMPANY_A)
                .param("format", "csv")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        // UTF-8 BOM
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);
        assertThat(body[1] & 0xFF).isEqualTo(0xBB);
        assertThat(body[2] & 0xFF).isEqualTo(0xBF);
        String bodyStr = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        // Header row present even with zero accounts
        assertThat(bodyStr).contains("Code");
        assertThat(bodyStr).contains("Libelle");
    }

    // ======================================================================
    // 3. Chart of accounts CSV export — unsupported format returns 422
    // ======================================================================

    @Test
    @DisplayName("GET /chart-of-accounts/export?format=xls returns 422 UNSUPPORTED_FORMAT")
    void chartOfAccountsCsv_unsupportedFormat_returns422() throws Exception {
        mockMvc.perform(
            get("/api/v1/companies/{cid}/chart-of-accounts/export", COMPANY_A)
                .param("format", "xls")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isUnprocessableEntity());
    }
}

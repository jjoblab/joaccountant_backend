package jo.accountant.reporting;

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
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.reporting.service.ReportingService;
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
 * Task v2.5.0-task3 — Integration tests for the aged-balance PDF endpoint added in v2.4.1
 * (step2-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/reporting/aged-balance/pdf?type=receivables}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/reporting/aged-balance/pdf?type=payables}</li>
 * </ul>
 *
 * <p>For each {@code type} value: call the JSON endpoint (sanity check), then call the PDF
 * endpoint and assert 200 + {@code Content-Type: application/pdf} + body starts with
 * {@code %PDF} magic bytes.
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :reporting/src/test/...}) because the full Spring Boot context is needed for
 * {@code @AutoConfigureMockMvc} to work end-to-end with JWT-based authentication.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ReportingPdfIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReportingPdfIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ReportingService reportingService;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID companyId) {
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", companyId.toString(), "role", "OWNER"))));
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

    // ======================================================================
    // 1. Aged balance PDF — receivables
    // ======================================================================

    @Test
    @DisplayName("GET /reporting/aged-balance/pdf?type=receivables returns 200 + application/pdf + %PDF magic bytes")
    void agedBalancePdf_receivables_returnsValidPdf() throws Exception {
        // 1. JSON sanity check (empty balance is fine — the PDF template renders even with zeros)
        var balance = reportingService.getAgedBalance(COMPANY_A);
        assertThat(balance).isNotNull();

        // 2. PDF endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/reporting/aged-balance/pdf", COMPANY_A)
                .param("type", "receivables")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 2. Aged balance PDF — payables
    // ======================================================================

    @Test
    @DisplayName("GET /reporting/aged-balance/pdf?type=payables returns 200 + application/pdf + %PDF magic bytes")
    void agedBalancePdf_payables_returnsValidPdf() throws Exception {
        var balance = reportingService.getSupplierAgedBalance(COMPANY_A);
        assertThat(balance).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/reporting/aged-balance/pdf", COMPANY_A)
                .param("type", "payables")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 3. Aged balance PDF — invalid type returns 422
    // ======================================================================

    @Test
    @DisplayName("GET /reporting/aged-balance/pdf?type=invalid returns 422 UNSUPPORTED")
    void agedBalancePdf_invalidType_returns422() throws Exception {
        mockMvc.perform(
            get("/api/v1/companies/{cid}/reporting/aged-balance/pdf", COMPANY_A)
                .param("type", "invalid")
                .with(jwtFor(COMPANY_A)))
            .andExpect(status().isUnprocessableEntity());
    }
}

package jo.accountant.payroll;

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
import jo.accountant.payroll.dto.CnssReturnResponse;
import jo.accountant.payroll.service.PayrollService;
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
 * Task v2.5.0-task3 — Integration tests for the 2 payroll PDF endpoints added in v2.4.1 + v2.4.2
 * (step2-backend / step7-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/payroll/summary/pdf} (v2.4.1)</li>
 *   <li>{@code GET /api/v1/companies/{cid}/payroll/cnss-return/pdf} (v2.4.2)</li>
 * </ul>
 *
 * <p>For each endpoint: call the JSON endpoint (sanity check) then the PDF endpoint and assert
 * 200 + {@code Content-Type: application/pdf} + body starts with {@code %PDF} magic bytes.
 *
 * <p><b>Seed strategy</b>: the PDF templates render correctly with empty data (zero runs / zero
 * payslips → all totals are "0", line lists are empty). The {@code cnss-return} endpoint
 * specifically aggregates payslip deductions/employerContributions matching codes
 * {@code CNSS_HT* / OFATMA_HT* / AST_HT*} (V68 ContributionRule) — seeding a realistic payslip
 * with that JSONB structure is non-trivial and out of scope for this integration test (the
 * endpoint + PDF rendering is what we want to verify, not the JSONB aggregation logic which is
 * covered by the existing {@code PayrollIntegrationTest}). The empty-data PDF is sufficient to
 * detect regressions on the rendering pipeline (template lookup → Thymeleaf → openhtmltopdf →
 * bytes).
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :payroll/src/test/...}) because the full Spring Boot context is needed for
 * {@code @AutoConfigureMockMvc} to work end-to-end with JWT-based authentication.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, PayrollPdfIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PayrollPdfIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private PayrollService payrollService;
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
    // 1. Payroll summary PDF
    // ======================================================================

    @Test
    @DisplayName("GET /payroll/summary/pdf returns 200 + application/pdf + %PDF magic bytes")
    void payrollSummaryPdf_returnsValidPdf() throws Exception {
        // 1. JSON sanity check (empty runs → empty list, no NPE)
        var runs = payrollService.listRuns(COMPANY_A, 120);
        assertThat(runs).isNotNull();

        // 2. PDF endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/payroll/summary/pdf", COMPANY_A)
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
    // 2. CNSS return PDF
    // ======================================================================
    //
    // NOTE — the cnss-return endpoint aggregates payslip deductions matching codes
    // CNSS_HT* / OFATMA_HT* / AST_HT* (V68 ContributionRule). Seeding a realistic payslip
    // with that JSONB structure is non-trivial — the existing PayrollIntegrationTest covers
    // the full cycle (create employee → create run → calculate → approve → payslips with
    // deductions). For this PDF integration test, we verify that the endpoint + PDF
    // rendering pipeline works end-to-end with empty data (no runs → empty CNSS_RETURN
    // PDF). A follow-up test could seed a full payroll cycle and verify that the CNSS_RETURN
    // PDF contains the expected aggregated amounts.

    @Test
    @DisplayName("GET /payroll/cnss-return/pdf returns 200 + application/pdf + %PDF magic bytes")
    void cnssReturnPdf_returnsValidPdf() throws Exception {
        // 1. JSON sanity check
        CnssReturnResponse data = payrollService.getCnssReturn(COMPANY_A,
            java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31));
        assertThat(data).isNotNull();

        // 2. PDF endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/payroll/cnss-return/pdf", COMPANY_A)
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

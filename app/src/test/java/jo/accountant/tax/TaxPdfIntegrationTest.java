package jo.accountant.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.app.JoAccountantApplication;
import jo.accountant.app.RecordingNotificationChannel;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.repository.CompanyModuleRepository;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.tax.dto.CorporateTaxProjection;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.service.TaxService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task v2.5.0-task3 — Integration tests for the 3 tax PDF endpoints added in v2.4.1
 * (step2-backend Reports Hub):
 * <ul>
 *   <li>{@code GET /api/v1/companies/{cid}/tax/declarations/pdf?taxType=VAT&from=...&to=...}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/tax/declarations/pdf?taxType=TCA&from=...&to=...}</li>
 *   <li>{@code GET /api/v1/companies/{cid}/tax/corporate-tax/projection/pdf?from=...&to=...}</li>
 * </ul>
 *
 * <p>The {@code ModuleAccessGuard} on these endpoints requires the TAX module to be enabled
 * for the company. The {@code corporate-tax/projection/pdf} endpoint additionally requires
 * the ACCOUNTANT role (not just VIEWER).
 *
 * <p><b>Location note</b>: this test lives in the {@code :app} module (not in
 * {@code :tax/src/test/...}) because the full Spring Boot context is needed for
 * {@code @AutoConfigureMockMvc} to work end-to-end with JWT-based authentication.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, TaxPdfIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TaxPdfIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private TaxService taxService;
    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private CompanyModuleRepository companyModuleRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            companyModuleRepository.deleteAllInBatch();
        });
        TenantContext.clear();
    }

    /** VIEWER jwt — sufficient for VAT/TCA declarations (but NOT for corporate-tax projection). */
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtViewer(UUID companyId) {
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", companyId.toString(), "role", "VIEWER"))));
    }

    /** ACCOUNTANT jwt — required for corporate-tax projection. */
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtAccountant(UUID companyId) {
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", companyId.toString(), "role", "ACCOUNTANT"))));
    }

    /**
     * Insère une ligne minimale dans {@code companies} (si elle n'existe pas déjà). Nécessaire car
     * {@code companyModuleService.enable(COMPANY_A, ModuleCode.TAX)} fait un INSERT dans la table
     * {@code company_module} avec une FK vers {@code companies} — sans ligne company, PostgreSQL
     * rejette avec {@code company_module_company_id_fkey} violation. Idempotent via
     * {@code ON CONFLICT (id) DO NOTHING}.
     */
    private void ensureCompanyRow(UUID companyId) {
        jdbcTemplate.update("""
            INSERT INTO companies (id, name, legal_form, country, functional_currency, sector,
                                   organization_nature, business_type_code, primary_activity_label,
                                   fiscal_year_start_month, wizard_step, wizard_completed)
            VALUES (?, 'Tax PDF Test Co', 'SARL', 'HT', 'HTG', 'COMMERCE',
                    'FOR_PROFIT', 'CUSTOM', 'Test activity',
                    1, 9, true)
            ON CONFLICT (id) DO NOTHING
            """, companyId);
    }

    private void enableTaxModule() {
        TenantContext.setCompanyId(COMPANY_A);
        TenantContext.setUserId(USER_X);
        ensureCompanyRow(COMPANY_A);
        companyModuleService.enable(COMPANY_A, ModuleCode.TAX);
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
    // 1. VAT declaration PDF
    // ======================================================================

    @Test
    @DisplayName("GET /tax/declarations/pdf?taxType=VAT returns 200 + application/pdf + %PDF magic bytes")
    void vatDeclarationPdf_returnsValidPdf() throws Exception {
        enableTaxModule();

        // 1. JSON sanity check
        TaxDeclaration decl = taxService.getDeclaration(COMPANY_A,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "VAT");
        assertThat(decl).isNotNull();

        // 2. PDF endpoint
        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/tax/declarations/pdf", COMPANY_A)
                .param("taxType", "VAT")
                .param("from", "2026-01-01")
                .param("to", "2026-01-31")
                .with(jwtViewer(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 2. TCA declaration PDF
    // ======================================================================

    @Test
    @DisplayName("GET /tax/declarations/pdf?taxType=TCA returns 200 + application/pdf + %PDF magic bytes")
    void tcaDeclarationPdf_returnsValidPdf() throws Exception {
        enableTaxModule();

        TaxDeclaration decl = taxService.getDeclaration(COMPANY_A,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "TCA");
        assertThat(decl).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/tax/declarations/pdf", COMPANY_A)
                .param("taxType", "TCA")
                .param("from", "2026-01-01")
                .param("to", "2026-01-31")
                .with(jwtViewer(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }

    // ======================================================================
    // 3. Corporate tax projection PDF
    // ======================================================================

    @Test
    @DisplayName("GET /tax/corporate-tax/projection/pdf returns 200 + application/pdf + %PDF magic bytes")
    void corporateTaxProjectionPdf_returnsValidPdf() throws Exception {
        enableTaxModule();

        CorporateTaxProjection projection = taxService.projectCorporateTax(COMPANY_A,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(projection).isNotNull();

        MvcResult result = mockMvc.perform(
            get("/api/v1/companies/{cid}/tax/corporate-tax/projection/pdf", COMPANY_A)
                .param("from", "2026-01-01")
                .param("to", "2026-12-31")
                .with(jwtAccountant(COMPANY_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("attachment; filename=")))
            .andReturn();

        assertValidPdf(result);
    }
}

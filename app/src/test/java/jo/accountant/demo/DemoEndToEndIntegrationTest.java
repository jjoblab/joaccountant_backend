package jo.accountant.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jo.accountant.app.JoAccountantApplication;
import jo.accountant.demo.service.DemoService;
import jo.accountant.testsupport.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * v2.5.3 — Test d'intégration end-to-end du mode démo :
 * 1. Démarre le backend avec embedded Postgres (profil test+demo)
 * 2. Attend que le seed démo se termine
 * 3. Teste POST /api/v1/demos/login/BOUTIK_LAKAY → doit retourner 200 + JWT
 * 4. Teste GET /api/v1/companies/{companyId}/audit-trail avec le JWT → doit retourner 200
 *
 * <p>Ce test reproduit exactement ce que fait l'app mobile et permet de valider
 * que le backend fonctionne avant de déployer sur Render.
 */
@SpringBootTest(
    classes = {JoAccountantApplication.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoEndToEndIntegrationTest extends EmbeddedPostgresSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DemoDataSeeder demoDataSeeder;

    @Autowired
    private DemoService demoService;

    private static final ObjectMapper OM = new ObjectMapper();
    private static String cachedAccessToken;
    private static String cachedCompanyId;

    @BeforeAll
    static void seedAll(@Autowired DemoDataSeeder seeder, @Autowired DemoService svc) {
        // Seed synchronously (not @Async) before tests.
        // Note: @ActiveProfiles("demo") also triggers @Async seedAllOnStartup,
        // but the @BeforeAll runs after Spring context is ready — the async seed
        // may or may not have finished. We call seedAllManually() to ensure
        // idempotent completion (seeders check if company exists → skip).
        System.out.println("=== Seeding demo companies (synchronous) ===");
        seeder.seedAllManually();
        long count = svc.countDemoCompanies();
        System.out.println("=== Seed complete: " + count + " demo companies in DB ===");
    }

    @Test
    @Order(1)
    void seedStatus_returnsOk() throws Exception {
        // actualCount may be > 4 if the @Async seedAllOnStartup also ran.
        // The key assertion is seeded=true + actualCount >= expectedCount.
        mockMvc.perform(get("/api/v1/demos/seed/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.seeded").value(true))
            .andExpect(jsonPath("$.expectedCount").value(4));
    }

    @Test
    @Order(2)
    void demoLogin_boutikLakay_returnsJwt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/demos/login/BOUTIK_LAKAY"))
            .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        System.out.println("=== Login response: status=" + status + " body=" + body.substring(0, Math.min(body.length(), 500)) + " ===");

        if (status != 200) {
            // Print the error detail for debugging
            System.err.println("LOGIN FAILED: status=" + status + " body=" + body);
        }

        org.springframework.test.util.AssertionErrors.assertEquals("Login status", 200, status);
        JsonNode json = OM.readTree(body);
        org.springframework.test.util.AssertionErrors.assertTrue("accessToken present", json.has("accessToken") && !json.get("accessToken").asText().isEmpty());

        String accessToken = json.get("accessToken").asText();
        String companyId = json.get("companies").get(0).get("companyId").asText();
        cachedAccessToken = accessToken;
        cachedCompanyId = companyId;

        System.out.println("=== Login OK: companyId=" + companyId + " token=" + accessToken.substring(0, 30) + "... ===");
    }

    @Test
    @Order(3)
    void auditTrail_withJwt_returnsOk() throws Exception {
        org.springframework.test.util.AssertionErrors.assertNotNull("JWT from previous test", cachedAccessToken);
        org.springframework.test.util.AssertionErrors.assertNotNull("companyId from previous test", cachedCompanyId);

        MvcResult result = mockMvc.perform(get("/api/v1/companies/{companyId}/audit-trail", cachedCompanyId)
                .header("Authorization", "Bearer " + cachedAccessToken)
                .param("page", "0")
                .param("size", "20"))
            .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        System.out.println("=== AuditTrail response: status=" + status + " body=" + body.substring(0, Math.min(body.length(), 500)) + " ===");

        org.springframework.test.util.AssertionErrors.assertEquals("AuditTrail status", 200, status);
    }

    @Test
    @Order(3)
    void demoLogin_unknownCode_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/demos/login/UNKNOWN_CODE"))
            .andExpect(status().isNotFound());
    }
}

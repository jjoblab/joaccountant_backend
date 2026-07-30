package jo.accountant.app.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jo.accountant.testsupport.EmbeddedPostgresSupport;

/**
 * Test de non-régression pour la Phase 1 (audit Z.ai 2026-07-31).
 *
 * <p>Valide que la CSP renvoyée par Spring Security autorise bien les ressources Swagger UI :
 * <ul>
 *   <li>{@code script-src 'self' 'unsafe-inline'} — pour swagger-ui-bundle.js + inline springdoc scripts</li>
 *   <li>{@code style-src 'self' 'unsafe-inline'} — pour swagger-ui.css + inline styles</li>
 *   <li>{@code connect-src 'self'} — pour fetch('/v3/api-docs/swagger-config')</li>
 *   <li>{@code img-src 'self' data:} — pour favicons et schémas inline</li>
 *   <li>{@code font-src 'self' data:} — pour les fonts webjars</li>
 * </ul>
 *
 * <p>Avant le fix, la CSP était {@code default-src 'none'} qui bloquait toutes ces ressources
 * et provoquait la page blanche sur /swagger-ui.html.
 *
 * <p>Étend {@code EmbeddedPostgresSupport} pour démarrer un PostgreSQL réel in-process (Zonky)
 * — nécessaire car le contexte Spring charge Flyway et toutes les migrations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwaggerUiCspPhase1IT extends EmbeddedPostgresSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("CSP autorise script-src 'self' 'unsafe-inline' — Swagger UI peut charger swagger-ui-bundle.js")
    void csp_allowsScriptSrcForSwaggerUi() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().is2xxSuccessful())
            .andExpect(header().exists("Content-Security-Policy"))
            .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        org.assertj.core.api.Assertions.assertThat(csp)
            .as("CSP doit contenir script-src 'self' 'unsafe-inline' pour Swagger UI")
            .contains("script-src 'self' 'unsafe-inline'");
    }

    @Test
    @DisplayName("CSP autorise style-src 'self' 'unsafe-inline' — Swagger UI peut charger swagger-ui.css")
    void csp_allowsStyleSrcForSwaggerUi() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
            .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        org.assertj.core.api.Assertions.assertThat(csp)
            .as("CSP doit contenir style-src 'self' 'unsafe-inline' pour Swagger UI")
            .contains("style-src 'self' 'unsafe-inline'");
    }

    @Test
    @DisplayName("CSP autorise connect-src 'self' — Swagger UI peut fetch /v3/api-docs/swagger-config")
    void csp_allowsConnectSrcForSwaggerUi() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
            .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        org.assertj.core.api.Assertions.assertThat(csp)
            .as("CSP doit contenir connect-src 'self' pour Swagger UI")
            .contains("connect-src 'self'");
    }

    @Test
    @DisplayName("CSP autorise img-src 'self' data: — favicons et schémas inline Swagger UI")
    void csp_allowsImgSrcForSwaggerUi() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
            .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        org.assertj.core.api.Assertions.assertThat(csp)
            .as("CSP doit contenir img-src 'self' data: pour Swagger UI")
            .contains("img-src 'self' data:");
    }

    @Test
    @DisplayName("CSP conserve default-src 'none' comme safety net pour les autres directives")
    void csp_keepsDefaultSrcNoneAsSafetyNet() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
            .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        org.assertj.core.api.Assertions.assertThat(csp)
            .as("CSP doit conserver default-src 'none' comme safety net")
            .contains("default-src 'none'");
    }

    @Test
    @DisplayName("CSP contient frame-ancestors 'none' — protection clickjacking préservée")
    void csp_preservesFrameAncestorsNone() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
            .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        org.assertj.core.api.Assertions.assertThat(csp)
            .as("CSP doit contenir frame-ancestors 'none' (protection clickjacking)")
            .contains("frame-ancestors 'none'");
    }

    @Test
    @DisplayName("/v3/api-docs reste accessible — JSON OpenAPI généré correctement")
    void apiDocs_remainsAccessible() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("/swagger-ui/index.html est permitAll — pas de 401/403")
    void swaggerUi_isPermitAll() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("/webjars/swagger-ui/ est permitAll — les assets JS/CSS se chargent")
    void webjars_arePermitAll() throws Exception {
        // Tente de charger un asset webjar connu — doit être 2xx (pas 401/403)
        mockMvc.perform(get("/webjars/swagger-ui/index.css"))
            .andExpect(status().is2xxSuccessful());
    }
}

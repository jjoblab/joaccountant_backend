package jo.accountant.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Tests unitaires pour {@link JwtService} — R-16 (lot-D-qualite-arch).
 *
 * <p>Couverture de {@link JwtService#parseAndVerifyClaims(String)} (R-01 — lot-A-securite) :
 * <ul>
 *   <li>Cas nominal : JWT signé valide → claims décodés.</li>
 *   <li>Cas erreur : signature invalide → {@link InvalidJwtException}.</li>
 *   <li>Cas erreur : JWT expiré → {@link InvalidJwtException}.</li>
 *   <li>Cas erreur : issuer inattendu → {@link InvalidJwtException}.</li>
 *   <li>Cas erreur : null/blank → {@link InvalidJwtException}.</li>
 * </ul>
 *
 * <p>Algorithme HS256 (mono-instance) — pas de dépendance fichier PEM RS256. Le secret de
 * test fait 64 caractères (≥ 32 requis par {@code validateSecret()}).
 */
class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz-0123";
    private static final String ISSUER = "joaccountant";
    private static final String AUDIENCE = "joaccountant-api";

    private Environment environment;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        environment = mock(Environment.class);
        when(environment.matchesProfiles("dev", "test")).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        jwtService = new JwtService(
            TEST_SECRET, "HS256", 900L, ISSUER, AUDIENCE,
            "", "test-key", environment);
        jwtService.validateSecret();
    }

    @Test
    @DisplayName("parseAndVerifyClaims — nominal : JWT HS256 valide → claims décodés")
    void parseAndVerifyClaims_valid() {
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        List<Map<String, Object>> companies = List.of(
            Map.of("companyId", UUID.randomUUID().toString(), "role", "OWNER"));

        String jwt = jwtService.issueAccessToken(userId, email, companies);

        Map<String, Object> claims = jwtService.parseAndVerifyClaims(jwt);
        assertThat(claims.get("sub")).isEqualTo(userId.toString());
        assertThat(claims.get("email")).isEqualTo(email);
    }

    @Test
    @DisplayName("parseAndVerifyClaims — error : signature invalide → InvalidJwtException")
    void parseAndVerifyClaims_invalidSignature() {
        UUID userId = UUID.randomUUID();
        String jwt = jwtService.issueAccessToken(userId, "u@x.com", List.of());

        // Tamper le payload : remplacer un caractère au milieu du JWT
        // JWT format : header.payload.signature — on modifie le payload
        String[] parts = jwt.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2) + "AA";
        String tamperedJwt = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims(tamperedJwt))
            .isInstanceOf(InvalidJwtException.class)
            .hasMessageContaining("Signature");
    }

    @Test
    @DisplayName("parseAndVerifyClaims — error : JWT expiré → InvalidJwtException")
    void parseAndVerifyClaims_expired() {
        // Construire un service avec TTL négatif → token immédiatement expiré
        JwtService expiredService = new JwtService(
            TEST_SECRET, "HS256", -10L, ISSUER, AUDIENCE,
            "", "test-key", environment);
        expiredService.validateSecret();

        String expiredJwt = expiredService.issueAccessToken(
            UUID.randomUUID(), "u@x.com", List.of());

        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims(expiredJwt))
            .isInstanceOf(InvalidJwtException.class)
            .hasMessageContaining("expiré");
    }

    @Test
    @DisplayName("parseAndVerifyClaims — error : issuer inattendu → InvalidJwtException")
    void parseAndVerifyClaims_wrongIssuer() {
        // Service émetteur avec un issuer différent
        JwtService otherIssuer = new JwtService(
            TEST_SECRET, "HS256", 900L, "another-issuer", AUDIENCE,
            "", "test-key", environment);
        otherIssuer.validateSecret();

        String jwt = otherIssuer.issueAccessToken(
            UUID.randomUUID(), "u@x.com", List.of());

        // Vérifier avec le service principal (issuer=joaccountant) → rejeté
        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims(jwt))
            .isInstanceOf(InvalidJwtException.class)
            .hasMessageContaining("Issuer");
    }

    @Test
    @DisplayName("parseAndVerifyClaims — error : JWT null ou blank → InvalidJwtException")
    void parseAndVerifyClaims_nullOrBlank() {
        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims(null))
            .isInstanceOf(InvalidJwtException.class)
            .hasMessageContaining("manquant");

        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims(""))
            .isInstanceOf(InvalidJwtException.class)
            .hasMessageContaining("manquant");

        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims("   "))
            .isInstanceOf(InvalidJwtException.class)
            .hasMessageContaining("manquant");
    }

    @Test
    @DisplayName("parseAndVerifyClaims — error : JWT mal formé (3 segments attendus) → InvalidJwtException")
    void parseAndVerifyClaims_malformed() {
        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims("not-a-jwt"))
            .isInstanceOf(InvalidJwtException.class);

        assertThatThrownBy(() -> jwtService.parseAndVerifyClaims("a.b"))
            .isInstanceOf(InvalidJwtException.class);
    }
}

package jo.accountant.app.security;

import java.util.Arrays;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.core.tenant.TenantContextFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configuration Spring Security globale (§3.4).
 *
 * <p>JWT stateless (pas de session serveur). Access token validé via HS256 contre le secret
 * partagé. Après authentification, le {@link TenantContextFilter} (enregistré ailleurs) et la
 * chaîne de filtres d'auth alimentent {@link TenantContext} avec userId + companyId depuis les
 * claims du JWT.
 *
 * <p><b>Audit v4.7 §6.3</b> : CORS restrictif configurable via {@code app.cors.allowed-origins}
 * (défaut {@code *} pour dev seulement). Headers de sécurité ajoutés (HSTS, X-Frame-Options,
 * X-Content-Type-Options, Referrer-Policy, CSP).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private final TenantContextFilter tenantContextFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(TenantContextFilter tenantContextFilter,
                          RateLimitFilter rateLimitFilter) {
        this.tenantContextFilter = tenantContextFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                           CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Audit v4.7 §6.3 — Headers de sécurité (HSTS, X-Frame-Options, CSP, Referrer-Policy)
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(ct -> {})  // X-Content-Type-Options: nosniff (activé par défaut, explicite)
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))  // 1 an
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/login/mfa",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password",
                    "/api/v1/auth/demo-login",
                    // MFA — setup/verify/check/recovery-code doivent rester accessibles sans Bearer
                    // car l'utilisateur peut être dans l'entre-deux du flow 2-step (challenge token
                    // en query param, validé par MfaService). Status/disable exigent un vrai Bearer.
                    "/api/v1/auth/mfa/setup",
                    "/api/v1/auth/mfa/verify",
                    "/api/v1/auth/mfa/check",
                    "/api/v1/auth/mfa/recovery-code").permitAll()
                .requestMatchers(HttpMethod.GET,
                    // JWKS endpoint — RFC 7517, public par construction (clé publique uniquement).
                    "/.well-known/jwks.json").permitAll()
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/swagger-resources/**",
                    "/webjars/**",
                    "/actuator/health",
                    "/actuator/info").permitAll()
                // V8.1 — Module Démos : endpoints publics GET /api/v1/demos/** (lecture seule,
                // entreprises fictives is_demo=true). Pas d'auth pour prospection commerciale.
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/demos",
                    "/api/v1/demos/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)))
            .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
            .addFilterBefore(tenantContextFilter, BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new TenantClaimFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.jwt.secret:}") String secret,
                                   @Value("${app.jwt.algorithm:HS256}") String algorithm,
                                   @Value("${app.jwt.rsa.public-key-path:}") String rsaPublicKeyPath,
                                   @Value("${app.jwt.issuer:joaccountant}") String issuer,
                                   @Value("${app.jwt.audience:joaccountant-api}") String audience) {
        // Audit v4.7 §6.3 — JwtDecoder adapté à l'algorithme configuré (HS256 ou RS256).
        // Pour HS256 : secret partagé symétrique.
        // Pour RS256 : clé publique RSA (la clé privée est utilisée par JwtService pour signer).
        String algoUpper = algorithm.trim().toUpperCase();
        if ("RS256".equals(algoUpper)) {
            // RS256 — charger la clé publique RSA
            if (rsaPublicKeyPath == null || rsaPublicKeyPath.isBlank()) {
                throw new IllegalStateException(
                    "app.jwt.algorithm=RS256 mais app.jwt.rsa.public-key-path n'est pas configuré. " +
                    "Générer la clé publique avec 'openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem' " +
                    "et configurer app.jwt.rsa.public-key-path=/path/to/jwt-public.pem");
            }
            try {
                String pemContent = java.nio.file.Files.readString(java.nio.file.Path.of(rsaPublicKeyPath)).trim();
                String pemBody = pemContent
                    .replaceAll("-{5}BEGIN [A-Z ]+-{5}", "")
                    .replaceAll("-{5}END [A-Z ]+-{5}", "")
                    .replaceAll("\\s+", "");
                byte[] keyBytes = java.util.Base64.getDecoder().decode(pemBody);
                java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(keyBytes);
                java.security.interfaces.RSAPublicKey publicKey = (java.security.interfaces.RSAPublicKey)
                    java.security.KeyFactory.getInstance("RSA").generatePublic(keySpec);
                LOG.info("JwtDecoder configuré en RS256 (clé publique depuis {})", rsaPublicKeyPath);
                return NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(
                    org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
            } catch (Exception ex) {
                throw new IllegalStateException("Impossible de charger la clé publique RSA : " + rsaPublicKeyPath, ex);
            }
        }
        // HS256 (défaut)
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "app.jwt.algorithm=HS256 mais app.jwt.secret n'est pas configuré. " +
                "Positionner APP_JWT_SECRET avec une valeur d'au moins 256 bits d'entropie.");
        }
        byte[] keyBytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        LOG.info("JwtDecoder configuré en HS256 (secret partagé)");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Configuration CORS restrictive.
     *
     * <p><b>Audit v4.7 §6.3 Finding</b> : la config originale autorisait {@code *} avec
     * {@code allowCredentials=true}, ce qui permettait à n'importe quel site web de faire des
     * requêtes authentifiées vers l'API. Désormais, les origines autorisées sont configurables via
     * {@code app.cors.allowed-origins} (séparées par virgule). Défaut {@code *} uniquement pour
     * faciliter le dev local — en production, positionner explicitement la liste.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:*}") String allowedOriginsCsv) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);  // Preflight cache 1h
        LOG.info("CORS configuré avec allowedOriginPatterns={} (allowCredentials=true)", origins);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

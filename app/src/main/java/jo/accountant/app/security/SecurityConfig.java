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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
// BearerTokenAuthenticationFilter déprécié en Spring Security 6.x.
// On utilise l'interface de base (qui reste stable) pour le positioning des filtres.
// import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
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
 * <p><b></b> : CORS restrictif configurable via {@code app.cors.allowed-origins}
 * (défaut {@code *} pour dev seulement). Headers de sécurité ajoutés (HSTS, X-Frame-Options,
 * X-Content-Type-Options, Referrer-Policy, CSP).
 
 *
 * @author jo@Dev
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
            //— Headers de sécurité (HSTS, X-Frame-Options, CSP, Referrer-Policy)
            //
            // ⚠️ FIXCSP default-src 'none' bloquait Swagger UI.
            // La directive `default-src 'none'` est la CSP la plus restrictive possible : elle
            // impose que TOUTES les directives fallback (script-src, style-src, img-src, font-src,
            // connect-src, frame-src, etc.) soient à 'none' sauf si redéfinies explicitement.
            // Or Swagger UI charge dynamiquement :
            // - /webjars/swagger-ui/swagger-ui-bundle.js → bloqué par script-src 'none'
            // - /webjars/swagger-ui/swagger-ui.css → bloqué par style-src 'none'
            // - fetch('/v3/api-docs/swagger-config') → bloqué par connect-src 'none'
            // - inline scripts injectés par springdoc → bloqués (pas de 'unsafe-inline')
            // Résultat : HTML 200 OK mais page blanche.
            //
            // Solution : CSP permissive pour Swagger UI tout en préservant les autres restrictions.
            // - 'self' autorise les webjars servis depuis la même origine
            // - 'unsafe-inline' est nécessaire pour script-src et style-src car springdoc
            // injecte du JS inline dans /swagger-ui/index.html (configuration dynamique)
            // - img-src 'self' data: permet les favicons et schémas inline des exemples
            // - connect-src 'self' autorise les fetch XHR vers /v3/api-docs et /swagger-config
            // - default-src 'none' reste comme safety net pour les autres directives (frame, object, etc.)
            // - frame-ancestors 'none' remplace X-Frame-Options pour la protection clickjacking
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(ct -> {}) // X-Content-Type-Options: nosniff (activé par défaut, explicite)
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)) // 1 an
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'none'; " +
                    "img-src 'self' data:; " +
                    "font-src 'self' data:; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "script-src 'self' 'unsafe-inline'; " +
                    "connect-src 'self'; " +
                    "frame-ancestors 'none'")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/login/mfa",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password",
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
                    "/v3/api-docs/**",
                    "/swagger-resources/**",
                    "/webjars/**",
                    "/actuator/health",
                    "/actuator/info").permitAll()
                // Module Démos : endpoints publics GET /api/v1/demos/** (lecture seule,
                // entreprises fictives is_demo=true). Pas d'auth pour prospection commerciale.
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/demos",
                    "/api/v1/demos/**").permitAll()
                // V9 — Login démo en un clic (POST /api/v1/demos/login/{demoCode}).
                // FIX v9.4.1 (audit T1.2) — remplacé permitAll par HTTP Basic auth
                // pour restreindre l'émission de vrais JWT démo. Le mobile doit envoyer
                // l'en-tête Authorization: Basic <base64(user:pass)> en plus de la
                // requête POST. Les credentials sont configurés via :
                //   app.demo.basic-auth.username (défaut: demo)
                //   app.demo.basic-auth.password (défaut: demo-secret-2026)
                // Sans Basic auth valide → 401 Unauthorized. Empêche l'extraction
                // arbitraire de JWT par un attaquant qui connaîtrait l'URL.
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/demos/login/**").hasRole("DEMO")
                // Re-seed manuel démo (POST /api/v1/demos/seed). Idem basic auth.
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/demos/seed").hasRole("DEMO")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)))
            .addFilterBefore(rateLimitFilter, AnonymousAuthenticationFilter.class)
            .addFilterBefore(tenantContextFilter, AnonymousAuthenticationFilter.class)
            .addFilterAfter(new TenantClaimFilter(), AnonymousAuthenticationFilter.class)
            // FIX v9.4.1 (audit T1.2) — active HTTP Basic auth pour les endpoints démo
            // POST /api/v1/demos/login/** et /api/v1/demos/seed. Les credentials sont
            // fournis par demoUserDetailsService() (configurables via propriétés).
            .httpBasic(basic -> basic.realmName("JoAccountant Demo"));
        return http.build();
    }

    /**
     * FIX v9.4.1 (audit T1.2) — UserDetailsService en mémoire pour les endpoints démo.
     *
     * <p>Crée un utilisateur unique avec le rôle {@code DEMO} et les credentials configurés
     * via {@code app.demo.basic-auth.username} (défaut: {@code demo}) et
     * {@code app.demo.basic-auth.password} (défaut: {@code demo-secret-2026}).
     *
     * <p>Le mot de passe est en clair (NoOpPasswordEncoder) car il s'agit d'un secret partagé
     * pour la démo publique — l'objectif est de limiter l'émission de JWT démo, pas de
     * protéger un véritable compte utilisateur. En production réelle, ce bean doit être
     * supprimé et les endpoints /demos/login/** + /demos/seed désactivés.
     *
     * @param username nom d'utilisateur basic auth démo
     * @param password mot de passe basic auth démo
     * @return InMemoryUserDetailsManager avec un utilisateur {username, password, ROLE_DEMO}
     */
    @Bean
    public UserDetailsService demoUserDetailsService(
            @Value("${app.demo.basic-auth.username:demo}") String username,
            @Value("${app.demo.basic-auth.password:demo-secret-2026}") String password) {
        UserDetails demoUser = User.withUsername(username)
            .password(password)
            .roles("DEMO")
            .build();
        LOG.info("Demo Basic auth configuré — username='{}' (rôle DEMO pour endpoints POST /demos/**)", username);
        return new InMemoryUserDetailsManager(demoUser);
    }

    /**
     * FIX v9.4.1 (audit T1.2) — PasswordEncoder no-op pour le UserDetailsService démo.
     *
     * <p>Les credentials démo sont stockés en clair (secret partagé), on n'a donc pas besoin
     * de BCrypt. <strong>Attention</strong> : ce bean est global et peut affecter d'autres
     * composants Spring Security. C'est volontaire — l'app n'utilise pas Spring Security pour
     * la gestion des passwords métier (elle a son propre AuthService + BCrypt dans :auth).
     */
    @Bean
    public static PasswordEncoder demoPasswordEncoder() {
        // NoOpPasswordEncoder est déprécié car non-sécurisé pour des mots de passe utilisateur.
        // Ici on l'utilise volontairement car le secret est partagé et public (démo).
        @SuppressWarnings("deprecation")
        PasswordEncoder encoder = NoOpPasswordEncoder.getInstance();
        return encoder;
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.jwt.secret:}") String secret,
                                   @Value("${app.jwt.algorithm:HS256}") String algorithm,
                                   @Value("${app.jwt.rsa.public-key-path:}") String rsaPublicKeyPath,
                                   @Value("${app.jwt.issuer:joaccountant}") String issuer,
                                   @Value("${app.jwt.audience:joaccountant-api}") String audience) {
        //— JwtDecoder adapté à l'algorithme configuré (HS256 ou RS256).
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
     * <p><b>Finding</b> : la config originale autorisait {@code *} avec
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
        config.setMaxAge(3600L); // Preflight cache 1h
        LOG.info("CORS configuré avec allowedOriginPatterns={} (allowCredentials=true)", origins);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

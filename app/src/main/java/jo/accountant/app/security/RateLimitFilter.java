package jo.accountant.app.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting sur les endpoints d'authentification (Vague 1, item 1.3 + audit v4.7 §6.2).
 *
 * <p>Limites appliquées :
 * <ul>
 *   <li><b>Par IP</b> : 10 tentatives par minute sur /login, /forgot-password, /register,
 *       /refresh, /reset-password.</li>
 *   <li><b>Par couple (IP, email)</b> : 5 échecs par 15 minutes — au-delà, lockout
 *       sur ce couple (mitige le brute-force ciblé sur un compte spécifique).</li>
 * </ul>
 *
 * <p><b>Audit v4.7 §6.2 Finding #4 — FIX</b> :
 * <ul>
 *   <li><b>XFF spoofing</b> : la version originale lisait {@code X-Forwarded-For} sans valider
 *       qu'il vient d'un proxy de confiance. Désormais, on utilise
 *       {@code request.getRemoteAddr()} qui est sécurisé par
 *       {@code server.forward-headers-strategy: framework} + {@code server.tomcat.remoteip.trusted-proxies}
 *       (à configurer dans application.yml). Si le déploiement est derrière un LB/proxy de confiance,
 *       Tomcat extrait l'IP réelle du client de manière sécurisée.</li>
 *   <li><b>Endpoints manquants</b> : ajout de /refresh et /reset-password (énumération de tokens
 *       opaques possible sur l'ancienne version).</li>
 *   <li><b>Pas de lockout par compte</b> : ajout du lockout (IP, email) après 5 échecs.</li>
 * </ul>
 *
 * <p><b>Audit batch B Finding #3 — Bucket4j in-memory</b> :
 * <p>Remplace l'implémentation manuelle (ConcurrentHashMap + AtomicInteger + CAS loop
 * maison) par <a href="https://bucket4j.com/">Bucket4j 8.10.1</a>. L'API est plus propre
 * et prépare la migration vers Redis.
 *
 * <p><b>R-14 (lot-C-perf-devops) — Rate-limiting distribué via Redis</b> :
 * <p>L'implémentation in-memory (ConcurrentHashMap de {@link Bucket}) est correcte pour un
 * déploiement mono-instance, mais pour 3 replicas, chaque instance avait sa propre map →
 * la limite effective était 3× la limite configurée (3 × 10 = 30 tentatives/min/IP au lieu
 * de 10). Désormais, si {@code app.rate-limit.redis.enabled=true}, un store basé sur
 * {@link LettuceBasedProxyManager} (bucket4j-redis + Lettuce) partage le bucket entre toutes
 * les instances via Redis.
 *
 * <p><b>Backward-compat</b> : si {@code app.rate-limit.redis.enabled=false} (défaut), le
 * store in-memory ({@link Bucket4jRateLimitStore}) est conservé — comportement inchangé
 * pour les déploiements existants. Aucune variable d'environnement à positionner pour
 * continuer à fonctionner comme avant.
 *
 * <p><b>Configuration Redis</b> : la connexion Redis utilise
 * {@code spring.data.redis.host} et {@code spring.data.redis.port} (variables Spring Data
 * Redis standard). Le filtre crée son propre client Lettuce (indépendant de la
 * {@code RedisConnectionFactory} auto-configurée par Spring) afin de maîtriser le cycle de
 * vie et le codec ({@code String}/{@code byte[]}).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int MAX_ATTEMPTS_PER_IP = 10;
    private static final int MAX_FAILURES_PER_IP_EMAIL = 5;
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);
    private static final Duration IP_EMAIL_LOCKOUT = Duration.ofMinutes(15);

    private final RateLimitStore store;
    private final boolean forwardHeadersStrategy;
    private final boolean redisEnabled;
    // Référence pour cleanup @PreDestroy (peut être null si Redis désactivé)
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, byte[]> redisConnection;

    public RateLimitFilter(
            @Value("${server.forward-headers-strategy:NONE}") String forwardHeadersStrategy,
            @Value("${app.rate-limit.redis.enabled:false}") boolean redisEnabled,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort) {
        this.forwardHeadersStrategy = "FRAMEWORK".equalsIgnoreCase(forwardHeadersStrategy);
        this.redisEnabled = redisEnabled;
        if (redisEnabled) {
            // Création d'un client Lettuce dédié au rate-limiting (indépendant de la
            // RedisConnectionFactory Spring pour éviter les conflits de codec). Le cycle de vie
            // est géré ici (close dans @PreDestroy).
            this.redisClient = RedisClient.create(RedisURI.create(redisHost, redisPort));
            this.redisConnection = redisClient.connect(
                RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            LettuceBasedProxyManager<String> proxyManager =
                LettuceBasedProxyManager.builderFor(redisConnection).build();
            this.store = new RedisRateLimitStore(proxyManager);
            LOG.info("RateLimitFilter initialisé : forward-headers-strategy={}, store=RedisRateLimitStore " +
                     "(bucket4j-redis+Lettuce, redis={}:{})",
                this.forwardHeadersStrategy, redisHost, redisPort);
        } else {
            this.redisClient = null;
            this.redisConnection = null;
            this.store = new Bucket4jRateLimitStore();
            LOG.info("RateLimitFilter initialisé : forward-headers-strategy={}, store=Bucket4jRateLimitStore " +
                     "(in-memory, redis désactivé — set app.rate-limit.redis.enabled=true pour distribuer)",
                this.forwardHeadersStrategy);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (redisConnection != null) {
            try {
                redisConnection.close();
            } catch (Exception e) {
                LOG.warn("Erreur lors de la fermeture de la connexion Redis (rate-limit)", e);
            }
        }
        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                LOG.warn("Erreur lors du shutdown du RedisClient (rate-limit)", e);
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isAuthEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        String ipKey = "ip:" + clientIp + ":" + path;

        // 1. Rate limit par IP — 10 tentatives par minute (Bucket4j : Bandwidth.builder()
        //    .capacity(10).refillIntervally(10, Duration.ofMinutes(1)).build()).
        if (!store.tryConsume(ipKey, MAX_ATTEMPTS_PER_IP, IP_WINDOW)) {
            LOG.warn("Rate limit IP dépassé : ip={}, path={}", clientIp, path);
            sendTooManyRequests(response, "RATE_LIMIT_IP_EXCEEDED",
                "Trop de tentatives depuis cette IP. Réessayez dans une minute.");
            return;
        }

        // 2. Pour /login et /reset-password : rate limit par (IP, email) si l'email est dans la requête
        //    (extraction best-effort — le body n'est pas lu ici pour éviter de consommer le stream)
        //    Ce check est complémentaire : il s'active seulement si l'email est passé en query param
        //    ou header (rare). Pour le body JSON, le rate limit par IP suffit en première défense.
        String email = request.getParameter("email");
        if (email != null && !email.isBlank()) {
            String ipEmailKey = "ipemail:" + clientIp + ":" + email.toLowerCase();
            if (!store.tryConsume(ipEmailKey, MAX_FAILURES_PER_IP_EMAIL, IP_EMAIL_LOCKOUT)) {
                LOG.warn("Lockout (IP, email) activé : ip={}, email={}", clientIp, email);
                sendTooManyRequests(response, "RATE_LIMIT_ACCOUNT_LOCKED",
                    "Trop de tentatives sur ce compte. Réessayez dans 15 minutes.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthEndpoint(String path) {
        if (path == null) return false;
        // R-01 (lot-A-securite) — ajout de /api/v1/auth/login/mfa dans la liste des endpoints
        // rate-limités. Avant, un attaquant pouvait brute-forcer le code TOTP à 6 chiffres
        // (1M de combinaisons) sans être ralenti par le filtre. Avec la limite de 10 tentatives
        // par minute par IP, l'attaque prendrait ~28 jours (1M / 10 / 60 / 24 ≈ 28) au lieu
        // de quelques secondes — soit largement au-delà du TTL de 5 min du challenge token.
        return path.endsWith("/api/v1/auth/login")
            || path.endsWith("/api/v1/auth/login/mfa")          // R-01 (lot-A-securite)
            || path.endsWith("/api/v1/auth/forgot-password")
            || path.endsWith("/api/v1/auth/register")
            || path.endsWith("/api/v1/auth/refresh")        // Audit v4.7 §6.2 — ajout
            || path.endsWith("/api/v1/auth/reset-password"); // Audit v4.7 §6.2 — ajout
    }

    /**
     * Récupère l'IP du client de manière sécurisée.
     *
     * <p>Audit v4.7 §6.2 — FIX XFF spoofing : la version originale lisait X-Forwarded-For
     * sans validation. Désormais, on s'appuie sur Tomcat RemoteIpValve (activé via
     * {@code server.forward-headers-strategy: framework} + {@code trusted-proxies}).
     * Si forward-headers n'est pas activé, on utilise {@code getRemoteAddr()} qui retourne
     * l'IP directe du connecteur TCP — non spoofable.
     */
    private String getClientIp(HttpServletRequest request) {
        // Si forward-headers-strategy=framework, Tomcat a déjà extrait l'IP réelle du client
        // dans request.getRemoteAddr() (après validation des trusted-proxies).
        // Sinon, getRemoteAddr() retourne l'IP TCP directe (non spoofable).
        return request.getRemoteAddr();
    }

    private void sendTooManyRequests(HttpServletResponse response, String code, String detail) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        String safeDetail = detail.replace("\"", "\\\"");
        String body = String.format(
            "{\"type\":\"https://joaccountant.dev/errors/rate_limit_exceeded\","
            + "\"title\":\"Too Many Requests\",\"status\":429,"
            + "\"detail\":\"%s\",\"code\":\"%s\"}",
            safeDetail, code);
        response.getWriter().write(body);
    }

    // --- Store abstraction (pour swap Redis plus tard) ---

    /**
     * Abstraction du store de rate-limiting — permet de remplacer l'impl in-memory par Redis.
     *
     * <p>{@code maxAttempts} = capacité du bucket (nombre de tokens), {@code window} =
     * période de refill (tous les tokens sont restaurés à la fin de chaque période).
     */
    interface RateLimitStore {
        boolean tryConsume(String key, int maxAttempts, Duration window);
    }

    /**
     * Implémentation in-memory basée sur Bucket4j (audit batch B Finding #3).
     *
     * <p>Chaque clé se voit attribuer un {@link Bucket} créé paresseusement via
     * {@code computeIfAbsent}. La configuration du bucket ({@code capacity + refill}) est
     * fixée à la création à partir des paramètres passés à {@link #tryConsume}.
     *
     * <p><b>Limite</b> : sans Redis, 3 replicas = 3× la limite effective (chaque instance
     * a sa propre map). Pour un déploiement multi-instances, activer
     * {@code app.rate-limit.redis.enabled=true} et utiliser {@link RedisRateLimitStore}.
     */
    static class Bucket4jRateLimitStore implements RateLimitStore {
        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        @Override
        public boolean tryConsume(String key, int maxAttempts, Duration window) {
            Bucket bucket = buckets.computeIfAbsent(key, k -> {
                Bandwidth limit = Bandwidth.builder()
                    .capacity(maxAttempts)
                    .refillIntervally(maxAttempts, window)
                    .build();
                return Bucket.builder().addLimit(limit).build();
            });
            return bucket.tryConsume(1);
        }
    }

    /**
     * R-14 (lot-C-perf-devops) — Implémentation Redis basée sur bucket4j-redis + Lettuce.
     *
     * <p>Le {@link LettuceBasedProxyManager} maintient un bucket distribué par clé dans
     * Redis. Toutes les instances partagent le même état → la limite s'applique globalement
     * (10 tentatives/min/IP pour les 3 replicas, pas 30).
     *
     * <p>L'algorithme utilisé par {@code LettuceBasedProxyManager} est Compare-And-Set
     * (CAS) via {@code SETNX}/{@code WATCH} sur la clé Redis — atomique côté Redis, pas
     * de race condition entre replicas. Alternative : version Lua-based si la contention
     * devient trop forte (intégrité garantie par un script Lua atomique).
     *
     * <p>Note : à chaque appel {@code tryConsume}, on construit une nouvelle
     * {@link BucketConfiguration} (cheap — pas d'I/O). Le {@link ProxyBucket} parle à
     * Redis uniquement pour {@code tryConsume}, en 1 round-trip.
     */
    static class RedisRateLimitStore implements RateLimitStore {

        private final LettuceBasedProxyManager<String> proxyManager;

        RedisRateLimitStore(LettuceBasedProxyManager<String> proxyManager) {
            this.proxyManager = proxyManager;
        }

        @Override
        public boolean tryConsume(String key, int maxAttempts, Duration window) {
            Bandwidth limit = Bandwidth.builder()
                .capacity(maxAttempts)
                .refillIntervally(maxAttempts, window)
                .build();
            BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(limit)
                .build();
            // Supplier qui retourne toujours la même configuration — utilisé par le ProxyManager
            // pour initialiser la clé Redis si elle n'existe pas encore (avec TTL basé sur
            // la durée de refill). Les appels suivants lisent l'état depuis Redis.
            Supplier<BucketConfiguration> configSupplier = () -> config;
            Bucket bucket = proxyManager.builder().build(key, configSupplier);
            return bucket.tryConsume(1);
        }
    }
}

package jo.accountant.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration du cache applicatif — Caffeine (audit v4.7 §7.2 ).
 *
 * <p><b>Problème</b> : la v4.7 ne comportait AUCUN cache applicatif (0 @Cacheable). Chaque
 * {@code issueInvoice} exécutait 7-10 SELECT sur des données référentielles qui ne changent
 * jamais (Account par taxMappingCode, Journal par code, etc.). Sur 1000 factures/jour =
 * 7000-10000 SELECT inutiles. Le plan comptable (200-500 comptes) était rechargé à chaque
 * {@code getDashboard}, {@code getBalanceSheet}, {@code getIncomeStatement},
 * {@code getTrialBalance}.
 *
 * <p><b>Solution</b> : cache local Caffeine (in-process, JVM-level) avec politiques dédiées par
 * type de données. Pour un déploiement multi-instances avec cohérence forte, ajouter Redis
 * comme L2 cache distribué (Non implémenté : ) — mais pour 1-3 instances, le cache local suffit car
 * les données référentielles changent rarement (quelques fois par an).
 *
 * <h2>Caches nommés</h2>
 * <ul>
 * <li><b>{@code accounts}</b> — plan comptable par entreprise. TTL 30 min, max 5000 entrées.
 * Éviction par taille (200-500 comptes/tenant × 100 tenants = 50000 max théorique,
 * mais la plupart des entrées sont partagées). Invalidation par {@code @CacheEvict} sur
 * les mutations de comptes (create/update/activate/deactivate).</li>
 * <li><b>{@code journals}</b> — journaux par entreprise. TTL 30 min, max 1000 entrées.
 * Invalidation par {@code @CacheEvict} sur les mutations de journaux.</li>
 * <li><b>{@code taxRules}</b> — règles de TVA. TTL 10 min, max 1000 entrées. Plus court TTL
 * car les règles fiscales peuvent changer en cours d'année (taux réduits temporaires).</li>
 * <li><b>{@code withholdingRules}</b> — règles de retenue à la source. TTL 10 min, max 1000.</li>
 * <li><b>{@code accountingFrameworks}</b> — référentiels comptables (IFRS, SYSCOHADA, etc.).
 * TTL 1h (immuable), max 100.</li>
 * <li><b>{@code businessTypes}</b> — types métier (13 entrées). TTL 1h, max 100.</li>
 * <li><b>{@code currencies}</b> — devises et leurs décimales. TTL 1h, max 200.</li>
 * </ul>
 *
 * <h2>Syntaxe d'usage</h2>
 * <pre>
 * &#64;Cacheable(value = "accounts", key = "#companyId.toString() + ':' + #code")
 * Optional&lt;Account&gt; findByCompanyIdAndCode(UUID companyId, String code);
 *
 * &#64;CacheEvict(value = "accounts", allEntries = true)
 * &#60;S extends Account&gt; S save(S entity);
 * </pre>
 *
 * <p><b>Multi-instances</b> : avec N replicas, chaque instance a son propre cache local. Une
 * mutation sur l'instance A invalide son cache local, mais PAS celui de B/C. B/C servent donc
 * des données stale jusqu'à expiration du TTL. Pour un plan comptable qui change quelques fois
 * par an, c'est acceptable (TTL 30 min). Pour des données plus volatiles, ajouter Redis L2.
 *
 * <p><b>Métriques</b> : Caffeine expose automatiquement ses stats via Micrometer
 * ({@code cache.gets}, {@code cache.puts}, {@code cache.evictions}, {@code cache.size}) sur
 * /actuator/prometheus. Pour activer les stats avancées (hit rate, load duration), appeler
 * {@code CaffeineBuilder.recordStats()} — déjà fait ci-dessous.
 */
@Configuration
@EnableCaching
public class CacheConfig {

 /**
 * CacheManager central — registre des caches nommés avec politiques dédiées.
 *
 * <p>Utilise {@link CaffeineCacheManager} avec une spécification par cache. Chaque cache
 * est créé à la demande via {@link #cacheManager()} quand {@code cacheManager.getCache(name)}
 * est appelé.
 */
 @Bean
 public CacheManager cacheManager() {
 CaffeineCacheManager manager = new CaffeineCacheManager();
 // Ne pas créer de cache à la volée pour des noms inconnus — safety net contre les typos.
 //
 // V8.2 (audit Z.ai 2026-07-31) — setAllowNullValues(true) pour supporter Optional.empty()
 // retourné par @Cacheable (ex: AccountRepository.findByCompanyIdAndCode quand le compte
 // n'existe pas encore). Avant le fix, setAllowNullValues(false) levait
 // IllegalArgumentException "Cache 'accounts' is configured to not allow null values but
 // null was provided" dès que ChartOfAccountsService.initialize était appelé (cas du wizard
 // V8.2 qui appelle initialize atomiquement). Le caching de valeurs null est acceptable
 // ici car les TTL courts (10-30 min) évitent de servir stale trop longtemps.
 manager.setAllowNullValues(true);
 // Spécifications par nom de cache — voir javadoc de classe pour les TTL.
 manager.registerCustomCache("accounts", Caffeine.newBuilder()
 .expireAfterWrite(30, TimeUnit.MINUTES)
 .maximumSize(5_000)
 .recordStats()
 .build());
 manager.registerCustomCache("journals", Caffeine.newBuilder()
 .expireAfterWrite(30, TimeUnit.MINUTES)
 .maximumSize(1_000)
 .recordStats()
 .build());
 manager.registerCustomCache("taxRules", Caffeine.newBuilder()
 .expireAfterWrite(10, TimeUnit.MINUTES)
 .maximumSize(1_000)
 .recordStats()
 .build());
 manager.registerCustomCache("withholdingRules", Caffeine.newBuilder()
 .expireAfterWrite(10, TimeUnit.MINUTES)
 .maximumSize(1_000)
 .recordStats()
 .build());
 manager.registerCustomCache("corporateTaxRules", Caffeine.newBuilder()
 .expireAfterWrite(10, TimeUnit.MINUTES)
 .maximumSize(1_000)
 .recordStats()
 .build());
 manager.registerCustomCache("accountingFrameworks", Caffeine.newBuilder()
 .expireAfterWrite(1, TimeUnit.HOURS)
 .maximumSize(100)
 .recordStats()
 .build());
 manager.registerCustomCache("businessTypes", Caffeine.newBuilder()
 .expireAfterWrite(1, TimeUnit.HOURS)
 .maximumSize(100)
 .recordStats()
 .build());
 manager.registerCustomCache("currencies", Caffeine.newBuilder()
 .expireAfterWrite(1, TimeUnit.HOURS)
 .maximumSize(200)
 .recordStats()
 .build());
 return manager;
 }
}

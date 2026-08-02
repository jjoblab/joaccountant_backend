package jo.accountant.app.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;

/**
 * Métriques business pour le monitoring opérationnel (audit v4.7 §9.3 ).
 *
 * <p>Expose des compteurs et timers sur les opérations métier critiques — au-delà des métriques
 * techniques automatiques (JVM, HikariCP, HTTP server) fournies par Micrometer.
 *
 * <p><b>Métriques exposées</b> :
 * <ul>
 * <li>{@code joaccountant.journal_entries.posted.total} — counter, tags: company_id, source_module</li>
 * <li>{@code joaccountant.journal_entries.reversed.total} — counter, tags: company_id</li>
 * <li>{@code joaccountant.payroll.runs.total} — counter, tags: company_id, status</li>
 * <li>{@code joaccountant.invoices.issued.total} — counter, tags: company_id, currency</li>
 * <li>{@code joaccountant.audit.events.persisted.total} — counter, tags: entity_type, action</li>
 * <li>{@code joaccountant.audit.events.failed.total} — counter, tags: entity_type, action</li>
 * <li>{@code joaccountant.security.events.total} — counter, tags: event_type (LOGIN_SUCCESS, LOGIN_FAILED, ...)</li>
 * <li>{@code joaccountant.fiscal_year.closed.total} — counter, tags: company_id</li>
 * <li>{@code joaccountant.journal_entries.posting.duration} — timer, tags: company_id, source_module</li>
 * <li>{@code joaccountant.payroll.generation.duration} — timer, tags: company_id</li>
 * </ul>
 *
 * <p><b>Utilisation depuis un service</b> :
 * <pre>
 * &#64;Autowired
 * private MeterRegistry meterRegistry;
 *
 * // Compter une écriture postée
 * meterRegistry.counter("joaccountant.journal_entries.posted.total",
 * "company_id", companyId.toString(),
 * "source_module", sourceModule.name())
 * .increment();
 *
 * // Mesurer la latence de postage
 * try (Timer.Sample sample = Timer.start(meterRegistry)) {
 * // ... opération ...
 * sample.stop(meterRegistry.timer("joaccountant.journal_entries.posting.duration",
 * "company_id", companyId.toString(),
 * "source_module", sourceModule.name()));
 * }
 * </pre>
 *
 * <p><b>Note sur la cardinalité</b> : tagger par {@code company_id} crée une série temporelle par
 * tenant — acceptable jusqu'à ~1000 tenants. Au-delà, considérer un push gateway par tenant ou
 * un tag agrégé (cluster). Le tag {@code source_module} a une cardinalité fixe (8 valeurs)
 * — pas de risque d'explosion.
 *
 * <p><b>Pas de MeterBinder</b> : cette classe n'implémente pas {@link MeterBinder} car les
 * compteurs sont créés à la demande (lazy) par les services via {@code meterRegistry.counter(...)}.
 * C'est le pattern recommandé pour les compteurs événementiels — un {@code MeterBinder} est utile
 * seulement pour les gauges synchronisées sur l'état interne.
 */
@Configuration
public class BusinessMetricsConfig {

 /**
 * Constantes de noms de métriques — centralisées pour éviter les typos et faciliter les
 * recherches dans le code. Le préfixe {@code joaccountant.} évite les collisions avec les
 * métriques Spring Boot automatiques.
 */
 public static final class MetricNames {
 // Compteurs d'événements business
 public static final String JOURNAL_ENTRIES_POSTED = "joaccountant.journal_entries.posted.total";
 public static final String JOURNAL_ENTRIES_REVERSED = "joaccountant.journal_entries.reversed.total";
 public static final String PAYROLL_RUNS = "joaccountant.payroll.runs.total";
 public static final String INVOICES_ISSUED = "joaccountant.invoices.issued.total";
 public static final String PURCHASE_INVOICES_RECEIVED = "joaccountant.purchase_invoices.received.total";
 public static final String AUDIT_EVENTS_PERSISTED = "joaccountant.audit.events.persisted.total";
 public static final String AUDIT_EVENTS_FAILED = "joaccountant.audit.events.failed.total";
 public static final String SECURITY_EVENTS = "joaccountant.security.events.total";
 public static final String FISCAL_YEAR_CLOSED = "joaccountant.fiscal_year.closed.total";

 // Timers de latence business
 public static final String JOURNAL_ENTRY_POSTING_DURATION = "joaccountant.journal_entries.posting.duration";
 public static final String PAYROLL_GENERATION_DURATION = "joaccountant.payroll.generation.duration";
 public static final String FISCAL_YEAR_CLOSING_DURATION = "joaccountant.fiscal_year.closing.duration";

 // Gauges d'état (pour info opérationnelle)
 public static final String HIKARI_CONNECTIONS_ACTIVE = "hikaricp.connections.active"; // fourni par micrometer
 public static final String TENANTS_ACTIVE = "joaccountant.tenants.active";

 private MetricNames() {}
 }

 /**
 * Tag keys standardisés — utilisés en second argument de {@code meterRegistry.counter(name, tags)}.
 */
 public static final class TagKeys {
 public static final String COMPANY_ID = "company_id";
 public static final String SOURCE_MODULE = "source_module";
 public static final String EVENT_TYPE = "event_type";
 public static final String ENTITY_TYPE = "entity_type";
 public static final String ACTION = "action";
 public static final String STATUS = "status";
 public static final String CURRENCY = "currency";

 private TagKeys() {}
 }

 /**
 * Helper — incrémente un counter avec gestion defensive des valeurs null.
 *
 * <p>Les compteurs Micrometer sont lazy : si le counter n'existe pas, il est créé avec les
 * tags fournis. La cardinalité des tags doit rester maîtrisée pour éviter l'explosion de
 * séries temporelles (chaque combinaison unique de tags = une série).
 */
 public static void increment(MeterRegistry registry, String metricName, String... tags) {
 try {
 Counter counter = registry.counter(metricName, tags);
 counter.increment();
 } catch (Exception e) {
 // Best-effort : une métrique ne doit jamais casser l'opération métier
 }
 }

 /**
 * Helper — enregistre une durée sur un timer.
 */
 public static void recordDuration(MeterRegistry registry, String metricName, Duration duration, String... tags) {
 try {
 Timer timer = registry.timer(metricName, tags);
 timer.record(duration);
 } catch (Exception e) {
 // Best-effort
 }
 }
}

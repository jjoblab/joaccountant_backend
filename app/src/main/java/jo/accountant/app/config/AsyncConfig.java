package jo.accountant.app.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 *Configuration du {@link ThreadPoolTaskExecutor} pour les méthodes
 * {@code @Async} (audit, security audit, forensic, domain events).
 *
 * <p><b>Problème</b> : sans {@code TaskExecutor} explicite, Spring Boot utilise
 * {@code SimpleAsyncTaskExecutor} par défaut — qui crée <strong>un nouveau thread par
 * appel {@code @Async}</strong>, sans limite. Sur un burst de 1000 événements (ex: import
 * d'un gros fichier de factures qui déclenche 1000 {@code InvoiceIssuedEvent}), l'app
 * démarre 1000 threads → OOM/ThreadLeak certain.
 *
 * <p><b>Solution</b> : un {@link ThreadPoolTaskExecutor} borné :
 * <ul>
 * <li>{@code corePoolSize=10} — 10 threads toujours actives (évite le coût de création
 * au démarrage de chaque appel).</li>
 * <li>{@code maxPoolSize=50} — jusqu'à 50 threads en pic (créées paresseusement quand
 * la queue est pleine).</li>
 * <li>{@code queueCapacity=100} — 100 tâches en attente avant de créer de nouvelles
 * threads (jusqu'à maxPoolSize).</li>
 * <li>{@code rejectedExecutionHandler = CallerRunsPolicy} — si queue pleine ET
 * maxPoolSize atteint, la tâche s'exécute sur le thread appelant (fallback synchrone).
 * C'est la politique <strong>anti-perte</strong> : on ne DROP jamais un événement
 * d'audit — on le traite sync au prix d'une légère latence pour le thread métier.</li>
 * <li>{@code waitForTasksToCompleteOnShutdown=true} + {@code awaitTerminationSeconds=30}
 * — au shutdown (SIGTERM K8s), on attend 30 s que les tâches en cours/cours de
 * queue finissent avant de tuer le pool. Évite de perdre des audits en vol.</li>
 * </ul>
 *
 * <p><b>Migration depuis {@code @EnableAsync} sur {@code JoAccountantApplication}</b> :
 * l'annotation {@code @EnableAsync} est déplacée ici (un seul {@code @EnableAsync} dans
 * l'app). Le bean {@code "audit-async-executor"} est le bean par défaut (via
 * {@code @Bean("audit-async-executor")} + le nom qualifie l'executor explicite à utiliser
 * sur les méthodes {@code @Async("audit-async-executor")}).
 *
 * <p><b>Sizing</b> (audit + security + forensic + domain events) :
 * <ul>
 * <li>Charge typique : 1-5 events/sec/user × 1000 users = 1000-5000 events/sec peak.</li>
 * <li>Latence moyenne d'un event listener : 5-20 ms (1 INSERT ou 1 log INFO).</li>
 * <li>Débit max avec 10 threads × 50ms/event = 200 events/sec.</li>
 * <li>En pic : 50 threads × 50ms = 1000 events/sec — couvre la majorité des bursts.</li>
 * <li>Queue de 100 = 5 sec de buffer à 1000 events/sec — temps d'absorption suffisant
 * pour laisser le pool grandir vers maxPoolSize.</li>
 * <li>Au-delà : CallerRunsPolicy ralentit le thread métier (backpressure naturelle).</li>
 * </ul>
 *
 * <p><b>Pourquoi pas Virtual Threads (Java 21) ?</b> : le projet est en Java 17 (voir
 * build.gradle.kts), virtual threads stable en Java 21. {@code spring.threads.virtual.enabled=true}
 * est déjà présent en configuration (forward-compat) mais no-op en Java 17. Quand la
 * migration Java 21 sera faite, ce {@code ThreadPoolTaskExecutor} pourra être remplacé
 * par un {@code SimpleAsyncTaskExecutor} avec virtual threads (milliers de threads cheap,
 * pas de pool sizing à tuner).
 
 *
 * @author jo@Dev


*/
@Configuration
@EnableAsync
public class AsyncConfig {

 private static final Logger LOG = LoggerFactory.getLogger(AsyncConfig.class);

 /**
 * Bean nommé "audit-async-executor" — référencé par {@code @Async("audit-async-executor")}
 * sur AuditEventListener, SecurityAuditEventListener, DomainEventListener, ForensicEventListener.
 *
 * <p>Le nom explicite est important : sans lui, Spring utilise le bean nommé
 * "taskExecutor" (réservé). En nommant "audit-async-executor", on isole ce pool du pool
 * par défaut Spring — un futur pool "email-async-executor" ou "report-async-executor"
 * pourra avoir un sizing différent sans interférer.
 */
 /**
 * Alias "taskExecutor" sur audit-async-executor pour éviter le warning
 * "More than one TaskExecutor bean found" au startup. Spring @Async sans qualifier
 * utilise le bean nommé "taskExecutor" par défaut.
 */
 @Bean("taskExecutor")
 @org.springframework.context.annotation.Primary
 public Executor taskExecutor() {
 return auditAsyncExecutor();
 }

 @Bean("audit-async-executor")
 public Executor auditAsyncExecutor() {
 ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 executor.setCorePoolSize(10);
 executor.setMaxPoolSize(50);
 executor.setQueueCapacity(100);
 executor.setThreadNamePrefix("audit-async-");
 // CallerRunsPolicy : si queue pleine + maxPoolSize atteint, exécute sur le thread
 // appelant. Backpressure naturelle — ne JAMAIS drop un événement d'audit.
 executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
 // Au shutdown K8s (SIGTERM), attendre 30 s que les tâches en cours/queue finissent.
 // Évite de perdre des audits en vol — perte d'audit = faille de conformité fiscale.
 executor.setWaitForTasksToCompleteOnShutdown(true);
 executor.setAwaitTerminationSeconds(30);
 executor.initialize();
 LOG.info("ThreadPoolTaskExecutor 'audit-async-executor' initialisé : " +
 "corePoolSize=10, maxPoolSize=50, queueCapacity=100, " +
 "rejectedExecutionHandler=CallerRunsPolicy, awaitTerminationSeconds=30");
 return executor;
 }

 /**
 * fix — Bean "thirteenthMonthTaskExecutor" référencé par
 * {@code @Async("thirteenthMonthTaskExecutor")} sur
 * {@link jo.accountant.payroll.service.ThirteenthMonthAsyncRunner#runAsync}.
 *
 * <p>Sans ce bean, Spring lève {@code NoSuchBeanDefinitionException} au moment
 * d'invoquer la méthode {@code @Async} → le calcul du 13e mois échoue en
 * cascade, marquant le {@code PayrollRun} en statut ERROR et cassant les
 * seeders démo qui créent des campagnes de paie THIRTEENTH_MONTH.
 *
 * <p><b>Sizing</b> : le 13e mois calcule 1 payslip par employé éligible,
 * en batches de 100 (cf. {@code ThirteenthMonthAsyncRunner}). Pour 1200
 * employés (Caribbean Textiles), ça fait ~12 batches × 50ms = 600ms. Un
 * pool de 2 threads core (5 max) suffit — c'est un calcul rare (1x/an).
 */
 @Bean("thirteenthMonthTaskExecutor")
 public Executor thirteenthMonthTaskExecutor() {
 ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 executor.setCorePoolSize(2);
 executor.setMaxPoolSize(5);
 executor.setQueueCapacity(10);
 executor.setThreadNamePrefix("13th-month-");
 // AbortPolicy : si queue pleine + maxPoolSize atteint, on préfère fail-fast
 // (le calcul 13e mois est rare, on ne veut pas le faire sur le thread métier).
 executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
 executor.setWaitForTasksToCompleteOnShutdown(true);
 executor.setAwaitTerminationSeconds(60); // 60s pour finir un batch en cours
 executor.initialize();
 LOG.info("ThreadPoolTaskExecutor 'thirteenthMonthTaskExecutor' initialisé : " +
 "corePoolSize=2, maxPoolSize=5, queueCapacity=10, " +
 "rejectedExecutionHandler=AbortPolicy, awaitTerminationSeconds=60");
 return executor;
 }
}

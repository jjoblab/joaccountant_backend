package jo.accountant.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application bootstrap (§3.1).
 *
 * <p>Scans every module's package — :core, :auth, :company, :audit-trail.
 *
 * <p><b>(lot-C-perf-devops)</b> : {@code @EnableAsync} est désormais sur
 * {@link jo.accountant.app.config.AsyncConfig} (avec un {@code ThreadPoolTaskExecutor} borné).
 * Historiquement sur cette classe, l'annotation activait l'async mais sans TaskExecutor
 * explicite → fallback sur {@code SimpleAsyncTaskExecutor} (1 thread par appel, unbounded).
 */
@SpringBootApplication(scanBasePackages = "jo.accountant")
@EntityScan(basePackages = "jo.accountant")
@EnableJpaRepositories(basePackages = "jo.accountant")
@EnableScheduling
public class JoAccountantApplication {

 public static void main(String[] args) {
 SpringApplication.run(JoAccountantApplication.class, args);
 }
}

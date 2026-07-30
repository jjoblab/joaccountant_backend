package jo.accountant.app.dev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * DevLauncher — démarre le backend JOAccountant avec un PostgreSQL embarqué in-process.
 *
 * <p>Usage : {@code ./gradlew :app:bootRun --args='--spring.profiles.active=dev'}
 * OU directement : {@code java -cp ... jo.accountant.app.dev.DevLauncher}
 *
 * <p>Avantages :
 * <ul>
 *   <li>pas d'installation PostgreSQL requise ;</li>
 *   <li>mêmes migrations Flyway qu'en production (pgcrypto, uuidv7()) ;</li>
 *   <li>même dialecte SQL, même planner, mêmes types que la prod ;</li>
 *   <li>idéal pour démos, dev local, tests manuels end-to-end.</li>
 * </ul>
 *
 * <p>Inconvénient : le binaire PostgreSQL embarqué (~50 Mo) est téléchargé au 1er lancement
 * et mis en cache dans {@code ~/.embed-postgres-binaries}.
 *
 * <p>Note : cette classe dépend de {@code io.zonky.test:embedded-postgres} qui est normalement
 * un scope test. Pour le mode dev, on l'expose en scope principal via :test-support (qui est
 * lui-même un {@code java-library} accessible depuis :app en {@code testImplementation}).
 *
 * <p>Pour autoriser l'usage runtime, on triche : on déplace la dépendance :test-support
 * vers {@code implementation} dans le profil dev. En pratique, on lance via le classpath
 * de test qui inclut déjà :test-support.
 */
public class DevLauncher {

    public static void main(String[] args) throws IOException {
        // 1. Démarre PostgreSQL embarqué (Zonky) — binaires téléchargés au 1er run
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        int port = pg.getPort();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  JOAccountant Dev Mode — PostgreSQL embarqué (Zonky)         ║");
        System.out.println("║  JDBC: jdbc:postgresql://localhost:" + port + "/postgres           ║");
        System.out.println("║  User: postgres / postgres                                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 2. Configure les propriétés système pour que Spring Boot utilise ce PG
        System.setProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:" + port + "/postgres");
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("spring.main.allow-bean-definition-overriding", "true");

        // 3. Démarre Spring Boot avec le profil dev
        SpringApplication app = new SpringApplication(JoAccountantDevApp.class);
        app.setAdditionalProfiles("dev");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[DevLauncher] Shutting down embedded PostgreSQL...");
            try {
                pg.close();
            } catch (IOException e) {
                System.err.println("[DevLauncher] Error closing PostgreSQL: " + e.getMessage());
            }
        }));

        ConfigurableApplicationContext ctx = app.run(args);
        System.out.println();
        System.out.println("✓ Backend ready on http://localhost:8080");
        System.out.println("✓ Swagger UI:   http://localhost:8080/swagger-ui.html");
        System.out.println("✓ OpenAPI JSON: http://localhost:8080/v3/api-docs");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();
    }
}

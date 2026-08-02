package jo.accountant.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Démarre une instance PostgreSQL réelle en processus (Zonky embedded-postgres) pour les tests
 * d'intégration.
 *
 * <p>§3.7 impose « PostgreSQL real, pas H2 ». Docker étant indisponible dans l'environnement de
 * dev, on utilise Zonky embedded-postgres qui télécharge et lance un vrai binaire PostgreSQL
 * in-process — mêmes migrations Flyway, mêmes types, même planner, même fonction uuidv7(). Ni
 * mock, ni H2.
 *
 * <p>L'instance est partagée entre toutes les classes de test via un champ statique (une seule
 * base par JVM). Chaque test est responsable du nettoyage de ses propres données (typiquement
 * via {@code @Transactional} + rollback, ou un {@code @AfterEach} qui deleteAll()).
 *
 * <p>Classe délibérément dans un module séparé ({@code :test-support}) pour éviter la dépendance
 * circulaire :test → :app — l'app est le consommateur final du module :app, pas un helper.
 
 *
 * @author jo@Dev


*/
public abstract class EmbeddedPostgresSupport {

    private static volatile EmbeddedPostgres pg;

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) throws IOException {
        EmbeddedPostgres instance = getInstance();
        int port = instance.getPort();
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + port + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    public static synchronized EmbeddedPostgres getInstance() throws IOException {
        if (pg == null) {
            pg = EmbeddedPostgres.builder().start();
        }
        return pg;
    }
}

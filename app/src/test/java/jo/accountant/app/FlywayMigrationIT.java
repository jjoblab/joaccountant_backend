package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fix Dim 1 H1 (audit v9.4) — Test d'intégration dédié qui valide que toutes les migrations
 * Flyway passent avec succès sur une DB vierge.
 *
 * <p><b>Contexte</b> : Le bug {@code vat_mode} (V19_005 référençant une colonne inexistante)
 * aurait dû être attrapé par n'importe quel {@code @SpringBootTest} du module :app, car
 * {@code application-test.yml} active Flyway (ddl-auto: none) et tous les tests IT étendent
 * {@code EmbeddedPostgresSupport} qui démarre un vrai PostgreSQL Zonky. Flyway s'exécute au
 * chargement du contexte Spring, AVANT toute méthode {@code @Test}.
 *
 * <p>Ce test explicite rend la vérification visible et indépendante — il valide que :
 * <ol>
 *   <li>Flyway est correctement configuré (locations, placeholder-replacement, etc.)</li>
 *   <li>Toutes les migrations (103+ à ce jour) passent sans erreur SQL</li>
 *   <li>Le schéma final contient bien les tables attendues (sample check)</li>
 * </ol>
 *
 * <p>Si une migration future casse (ex: référence à une colonne inexistante, syntaxe SQL
 * invalide, contrainte violée par un seed), ce test échouera en ~3 secondes, avant même
 * que le JAR ne soit construit (grâce au {@code bootJar dependsOn test} ajouté dans
 * {@code app/build.gradle.kts}).
 *
 * @author jo@Dev
 */
@SpringBootTest(classes = JoAccountantApplication.class)
@ActiveProfiles("test")
class FlywayMigrationIT extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Toutes les migrations Flyway passent avec succès sur DB vierge")
    void allMigrationsApplySuccessfully() {
        // Reconstruit une instance Flyway à partir de la DataSource de test (DB Zonky
        // déjà migrée par le contexte Spring). validate() vérifie que toutes les
        // migrations sont bien en état SUCCESS et que les checksums correspondent.
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .placeholderReplacement(false)
            .load();

        // validate() lève une FlywayException si une migration a échoué ou si les
        // checksums ne correspondent pas (migration modifiée après coup).
        flyway.validate();

        // Vérifie que le nombre de migrations appliquées est > 0 (sanity check).
        int appliedCount = flyway.info().applied().length;
        assertThat(appliedCount)
            .as("Flyway doit avoir appliqué au moins 100 migrations (103 à ce jour)")
            .isGreaterThan(100);

        // Vérifie qu'aucune migration n'est en état FAILED.
        long failedCount = java.util.Arrays.stream(flyway.info().all())
            .filter(m -> m.getState() == org.flywaydb.core.api.output.MigrationState.FAILED)
            .count();
        assertThat(failedCount)
            .as("Aucune migration ne doit être en état FAILED")
            .isZero();
    }

    @Test
    @DisplayName("Le schéma final contient les tables critiques attendues")
    void schemaContainsCriticalTables() throws Exception {
        // Sample check : vérifie que les tables les plus critiques existent dans le
        // schéma après migration. Si une migration future supprime accidentellement
        // une table, ce test le détectera.
        String[] criticalTables = {
            "companies", "fiscal_year", "fiscal_period", "journal_entry", "journal_line",
            "account", "tax_rule", "invoice", "invoice_line", "third_party",
            "fiscal_year", "document_template", "payslip"
        };

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            for (String table : criticalTables) {
                try (var rs = stmt.executeQuery(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = '" + table + "')")) {
                    rs.next();
                    assertThat(rs.getBoolean(1))
                        .as("La table '%s' doit exister après migration", table)
                        .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Fix v9.4 — La colonne vat_mode existe bien sur tax_rule (régression bug V19_005)")
    void vatModeColumnExistsOnTaxRule() throws Exception {
        // Test de non-régression spécifique au bug vat_mode qui a déclenché l'audit.
        // Si une migration future supprime accidentellement la colonne, ce test le détectera.
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("""
                 SELECT EXISTS (
                   SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'tax_rule' AND column_name = 'vat_mode'
                 )
                 """)) {
            rs.next();
            assertThat(rs.getBoolean(1))
                .as("La colonne 'vat_mode' doit exister sur la table 'tax_rule' (fix V19_005)")
                .isTrue();
        }
    }

    @Test
    @DisplayName("Fix v9.4 — La colonne closed_at existe bien sur fiscal_year (V8_009)")
    void closedAtColumnExistsOnFiscalYear() throws Exception {
        // Test de non-régression pour la migration V8_009 (traçabilité fiscale).
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("""
                 SELECT EXISTS (
                   SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'fiscal_year' AND column_name = 'closed_at'
                 )
                 """)) {
            rs.next();
            assertThat(rs.getBoolean(1))
                .as("La colonne 'closed_at' doit exister sur 'fiscal_year' (fix V8_009)")
                .isTrue();
        }
    }
}

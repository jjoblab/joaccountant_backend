package jo.accountant.demo.support;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * v2.5.2 — Désactive RLS (Row-Level Security) sur les 6 tables protégées par V51
 * au démarrage en profil {@code demo}.
 *
 * <p><b>Pourquoi ?</b> Les seeders démo ({@link jo.accountant.demo.seeders.CompanySeeder})
 * créent des données pour 4 entreprises fictives sans contexte HTTP — donc sans
 * {@link jo.accountant.core.tenant.TenantContext} positionné au début de la transaction.
 * RLS bloquait tous les INSERT/SELECT sur {@code journal_entry}, {@code third_party},
 * etc. avec "violates row-level security policy" ou "current transaction is aborted".
 *
 * <p><b>Approche</b> : au {@code ApplicationReadyEvent} (après que Spring Boot soit
 * démarré), exécuter {@code ALTER TABLE ... DISABLE ROW LEVEL SECURITY} sur les 6
 * tables. Cela désactive RLS pour toutes les requêtes futures (les policies restent
 * définies mais ne sont plus évaluées). Les seeders démo pourront alors créer leurs
 * données sans contrainte.
 *
 * <p><b>Sécurité</b> : cette classe est {@code @Profile("demo")} — elle ne s'enregistre
 * PAS en profil {@code render}/{@code prod}. En production réelle, RLS reste actif
 * et protège l'isolation multi-tenant.
 *
 * <p><b>Idempotence</b> : {@code ALTER TABLE ... DISABLE ROW LEVEL SECURITY} est
 * idempotent — si RLS est déjà désactivé, la commande est un no-op. On peut donc
 * redémarrer l'app sans problème.
 *
 * <p><b>Alternative envisagée</b> : accorder {@code BYPASSRLS} au user applicatif
 * (cf. V53__flyway_bypassrls.sql). Mais {@code BYPASSRLS} requiert {@code SUPERUSER}
 * pour être accordé — Render free tier PostgreSQL ne l'accorde pas. Cette migration
 * V53 lève donc {@code insufficient_privilege} en silence (bloc DO $$ catch) et le
 * user n'a pas BYPASSRLS. D'où le besoin de DISABLE RLS au startup.
 *
 * @see jo.accountant.core.tenant.TenantRlsDataSourcePostProcessor (RLS bypassé en profil demo)
 */
@Component
@Profile("demo")
public class DemoRlsDisabler {

    private static final Logger LOG = LoggerFactory.getLogger(DemoRlsDisabler.class);

    /** Les 6 tables RLS-protégées par V51__postgres_rls.sql. */
    private static final String[] RLS_TABLES = {
        "journal_line",
        "journal_entry",
        "sales_invoice",
        "purchase_invoice",
        "third_party",
        "expense_report",
    };

    private final JdbcTemplate jdbcTemplate;

    public DemoRlsDisabler(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)  // Avant DemoDataSeeder (@Order(100)) pour que RLS soit désactivé avant le seed.
    public void disableRlsForDemo() {
        LOG.warn("═══════════════════════════════════════════════════════════");
        LOG.warn("  v2.5.2 — DÉSACTIVATION DE RLS en profil 'demo'");
        LOG.warn("  Les 6 tables RLS-protégées (V51) vont être DISABLE.");
        LOG.warn("  NE PAS utiliser en production réelle — sécurité multi-tenant compromise.");
        LOG.warn("═══════════════════════════════════════════════════════════");

        for (String table : RLS_TABLES) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
                LOG.info("  ✓ RLS DISABLE sur la table '{}'", table);
            } catch (Exception e) {
                LOG.warn("  ✗ Échec DISABLE RLS sur '{}' : {} (la table existe-t-elle ?)", table, e.getMessage());
            }
        }

        LOG.info("═══════════════════════════════════════════════════════════");
        LOG.info("  v2.5.2 — Désactivation RLS terminée. Les seeders démo peuvent maintenant");
        LOG.info("  créer des données sans contrainte de tenant context.");
        LOG.info("═══════════════════════════════════════════════════════════");
    }
}

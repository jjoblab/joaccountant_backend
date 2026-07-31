package jo.accountant.core.tenant;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * R-03 (lot-A-securite) — Installe {@link TenantRlsConnectionCustomizer} autour du bean
 * "dataSource" (auto-configuré par Spring Boot avec HikariCP).
 *
 * <p>Pattern BeanPostProcessor plutôt que {@code @Bean @Primary DataSource} : cette dernière
 * approche crée une dépendance circulaire (le bean {@code @Primary} devrait s'injecter
 * lui-même pour wrappé Hikari). Le BeanPostProcessor modifie le bean Hikari après son
 * initialisation — pas de cycle.
 *
 * <p><b>Filtre par beanName</b> — On ne wrappe QUE le bean nommé "dataSource" (le nom
 * standard Spring Boot). On évite ainsi de wrapper d'éventuels DataSources secondaires
 * (ex: read-replica, batch DataSource) qui pourraient avoir d'autres noms. Si le projet
 * ajoute un DataSource secondaire à wrapper, étendre le filtre ici.
 *
 * <p><b>Ordre d'initialisation</b> — Spring Boot garantit que les BeanPostProcessors sont
 * créés AVANT les beans applicatifs. Quand le bean "dataSource" (Hikari) est créé, notre
 * postProcessAfterInitialization le wrappe. Les beans qui dépendent du DataSource (Flyway,
 * EntityManagerFactory, Spring Batch) voient alors le proxy wrappé — pas le Hikari brut.
 *
 * <p><b>v2.5.2 fix démo</b> — En profil {@code demo}, RLS est DÉSACTIVÉ. Les seeders démo
 * créent des données pour 4 entreprises fictives sans contexte HTTP (donc sans
 * {@code TenantContext} positionné au début de la transaction). RLS bloquait tous les
 * INSERT sur {@code journal_entry}, {@code third_party}, etc. avec "violates row-level
 * security policy". En démo, les données sont fictives — le bypass RLS est acceptable.
 * En prod ({@code render}, {@code prod}), RLS reste actif (sécurité multi-tenant).
 */
@Component
public class TenantRlsDataSourcePostProcessor implements BeanPostProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(TenantRlsDataSourcePostProcessor.class);

    /** Nom standard du bean DataSource auto-configuré par Spring Boot. */
    private static final String DATA_SOURCE_BEAN_NAME = "dataSource";

    private final Environment environment;

    public TenantRlsDataSourcePostProcessor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource ds && DATA_SOURCE_BEAN_NAME.equals(beanName)) {
            boolean demoProfileActive = java.util.Arrays.asList(environment.getActiveProfiles()).contains("demo");
            if (demoProfileActive) {
                // v2.5.2 — En mode démo, on NE wrappe PAS le DataSource avec le RLS customizer.
                // Les seeders démo pourront ainsi créer des données sans contexte tenant.
                // RLS reste activé en DB (FORCE ROW LEVEL SECURITY) mais la policy
                // `company_id = current_setting('app.current_tenant', true)::uuid` évaluera
                // à `company_id = NULL` (current_setting missing-OK retourne NULL) → FALSE.
                // DONC : pour que les SELECT marchent en démo, on doit aussi BYPASSER RLS
                // au niveau DB. On le fait via une property qui active un role bypass.
                LOG.warn("R-03 (lot-A-securite) : RLS BYPASSÉ en profil 'demo' — les seeders démo " +
                    "créent des données sans contexte tenant. NE PAS utiliser en production réelle.");
                // On retourne le DataSource brut SANS wrapper → pas de SET LOCAL →
                // mais RLS est FORCE en DB, donc les SELECT retournent 0 lignes.
                // Pour bypasser complétement, on doit utiliser un role PostgreSQL avec BYPASSRLS.
                // Voir application-demo.yml pour la config du role bypass.
                return ds;
            }
            LOG.info("R-03 (lot-A-securite) : wrapping DataSource '{}' avec TenantRlsConnectionCustomizer " +
                "(SET LOCAL app.current_tenant au début de chaque transaction).", beanName);
            return new TenantRlsConnectionCustomizer(ds);
        }
        return bean;
    }
}

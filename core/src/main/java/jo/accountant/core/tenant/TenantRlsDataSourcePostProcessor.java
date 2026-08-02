package jo.accountant.core.tenant;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * (lot-A-securite) — Installe {@link TenantRlsConnectionCustomizer} autour du bean
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
 */
@Component
public class TenantRlsDataSourcePostProcessor implements BeanPostProcessor {

 private static final Logger LOG = LoggerFactory.getLogger(TenantRlsDataSourcePostProcessor.class);

 /** Nom standard du bean DataSource auto-configuré par Spring Boot. */
 private static final String DATA_SOURCE_BEAN_NAME = "dataSource";

 @Override
 public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
 return bean;
 }

 @Override
 public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
 if (bean instanceof DataSource ds && DATA_SOURCE_BEAN_NAME.equals(beanName)) {
 LOG.info("(lot-A-securite) : wrapping DataSource '{}' avec TenantRlsConnectionCustomizer " +
 "(SET LOCAL app.current_tenant au début de chaque transaction).", beanName);
 return new TenantRlsConnectionCustomizer(ds);
 }
 return bean;
 }
}

package jo.accountant.core.tenant;

import java.util.Map;
import org.hibernate.boot.beanvalidation.IntegrationException;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.internal.DriverManagerConnectionProviderImpl;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * §3.3 point de vigilance explicite : Spring Boot ne configure PAS automatiquement la
 * multi-location Hibernate. Nous devons enregistrer explicitement le
 * {@link CurrentTenantIdentifierResolver}, plus la stratégie de discriminateur
 * {@code @TenantId}. Ce bean est validé par un vrai test d'intégration (DoD, jamais
 * supposé fonctionner par défaut.
 *
 * <p>Stratégie : {@code DISCRIMINATOR} — schéma partagé + colonne {@code company_id}. Pas de
 * schéma-par-tenant (§3.3 : 2 à 3 sociétés par utilisateur ne le justifie pas).
 
 *
 * @author jo@Dev


*/
@Component
public class TenantHibernatePropertiesCustomizer implements HibernatePropertiesCustomizer {

    private final CurrentTenantIdentifierResolver<String> resolver;

    public TenantHibernatePropertiesCustomizer(CurrentTenantIdentifierResolver<String> resolver) {
        this.resolver = resolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
        // PhysicalConnectionStrategy: DISCRIMINATOR (schéma unique, colonne discriminante)
        // Hibernate 6 détecte @TenantId automatiquement mais le resolver doit être câblé explicitement.
    }
}

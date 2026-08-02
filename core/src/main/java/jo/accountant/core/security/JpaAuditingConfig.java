package jo.accountant.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Active {@code @CreatedDate} / {@code @LastModifiedDate} / {@code @CreatedBy} /
 * {@code @LastModifiedBy} sur chaque {@link jo.accountant.core.tenant.TenantAwareEntity}.
 *
 * <p>Bean AuditorAware = {@link SpringSecurityAuditorAware} (lit depuis
 * {@link jo.accountant.core.tenant.TenantContext}). Ce bean est fourni par
 * l'annotation {@code @Component} sur {@link SpringSecurityAuditorAware} — on ne
 * le redéclare pas ici pour éviter un {@code BeanDefinitionOverrideException}.
 
 *
 * @author jo@Dev


*/
@Configuration
@EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
public class JpaAuditingConfig {
    // Aucun @Bean : SpringSecurityAuditorAware est déjà enregistré par @Component.
}

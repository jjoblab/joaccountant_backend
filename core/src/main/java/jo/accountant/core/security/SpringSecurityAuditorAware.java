package jo.accountant.core.security;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

/**
 * Alimente {@code @CreatedBy} / {@code @LastModifiedBy} sur chaque
 * {@link jo.accountant.core.tenant.TenantAwareEntity} depuis {@link TenantContext#getUserId()}.
 *
 * <p>Quand aucun utilisateur n'est présent (par ex. endpoint d'enregistrement, bootstrap de test),
 * renvoie vide — les colonnes sont nullables pour supporter explicitement ce cas.
 
 *
 * @author jo@Dev


*/
@Component
public class SpringSecurityAuditorAware implements AuditorAware<UUID> {

    @Override
    public Optional<UUID> getCurrentAuditor() {
        return Optional.ofNullable(TenantContext.getUserId());
    }
}

package jo.accountant.core.tenant;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Fait le pont entre {@link TenantContext} et la machinerie multi-tenant d'Hibernate (§3.3).
 *
 * <p>Retourner un placeholder stable quand aucun tenant n'est positionné permet aux migrations et
 * aux requêtes de boot (qui s'exécutent hors requête) de réussir sans fuite de lignes — chaque
 * ligne métier porte un {@code company_id} non nul, le placeholder ne correspond donc à rien.
 */
@Component
public class CurrentTenantIdentifierResolverImpl implements CurrentTenantIdentifierResolver<String> {

    public static final String NO_TENANT = "__no_tenant__";

    @Override
    public String resolveCurrentTenantIdentifier() {
        UUID current = TenantContext.getCompanyId();
        return current == null ? NO_TENANT : current.toString();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}

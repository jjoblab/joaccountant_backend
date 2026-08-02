package jo.accountant.core.tenant;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.UUID;
import jo.accountant.core.exception.ValidationException;
import org.springframework.stereotype.Component;

/**
 * Listener JPA qui injecte {@code companyId} depuis {@link TenantContext} dans chaque
 * {@link TenantAwareEntity} lors des persist/update.
 *
 * <p>Règle impérative (§3.3) : {@code companyId} n'est jamais accepté dans le corps d'une requête
 * entrante. Si {@link TenantContext} est vide lors d'un persist, on échoue bruyamment — mieux vaut
 * casser que d'attacher silencieusement une ligne au mauvais tenant.
 
 *
 * @author jo@Dev


*/
@Component
public class TenantAwareEntityListener {

    @PrePersist
    void onPrePersist(Object entity) {
        if (entity instanceof TenantAwareEntity t) {
            if (t.getCompanyId() == null) {
                UUID current = TenantContext.getCompanyId();
                if (current == null) {
                    throw new ValidationException(
                        "TENANT_CONTEXT_REQUIRED",
                        "TenantContext.companyId must be set before persisting a TenantAwareEntity");
                }
                t.setCompanyId(current);
            }
        }
    }

    @PreUpdate
    void onPreUpdate(Object entity) {
        if (entity instanceof TenantAwareEntity t) {
            if (t.getCompanyId() == null) {
                UUID current = TenantContext.getCompanyId();
                if (current == null) {
                    throw new ValidationException(
                        "TENANT_CONTEXT_REQUIRED",
                        "TenantContext.companyId must be set before updating a TenantAwareEntity");
                }
                t.setCompanyId(current);
            }
        }
    }
}

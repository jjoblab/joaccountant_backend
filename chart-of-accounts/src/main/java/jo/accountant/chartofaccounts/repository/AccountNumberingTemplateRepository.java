package jo.accountant.chartofaccounts.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.AccountNumberingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des gabarits de numérotation (référentiels {@code FREE} uniquement).
 *
 * <p>Une seule ligne par entreprise (relation 1-1).
 
 *
 * @author jo@Dev


*/
public interface AccountNumberingTemplateRepository
    extends JpaRepository<AccountNumberingTemplate, UUID> {

    /** Retourne le gabarit de l'entreprise, ou empty si aucun (référentiel {@code MANDATED}). */
    Optional<AccountNumberingTemplate> findByCompanyId(UUID companyId);
}

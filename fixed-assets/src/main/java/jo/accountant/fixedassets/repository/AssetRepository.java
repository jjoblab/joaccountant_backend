package jo.accountant.fixedassets.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.fixedassets.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des immobilisations.
 
 *
 * @author jo@Dev


*/
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    /** Toutes les immobilisations de l'entreprise, triées par libellé. */
    List<Asset> findByCompanyIdOrderByLabel(UUID companyId);

    /** Immobilisations actives uniquement. */
    List<Asset> findByCompanyIdAndStatus(UUID companyId, jo.accountant.fixedassets.entity.AssetStatus status);
}

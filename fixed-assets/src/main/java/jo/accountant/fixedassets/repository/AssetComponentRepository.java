package jo.accountant.fixedassets.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.fixedassets.entity.AssetComponent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des composants d'immobilisation (IAS 16).
 
 *
 * @author jo@Dev


*/
public interface AssetComponentRepository extends JpaRepository<AssetComponent, UUID> {

 /** Tous les composants d'une immobilisation, triés par code. */
 List<AssetComponent> findByAssetIdOrderByCode(UUID assetId);

 /** Composant d'un asset par son code — utilisé pour vérifier l'unicité du code. */
 Optional<AssetComponent> findByAssetIdAndCode(UUID assetId, String code);

 /** Supprime tous les composants d'un asset — utilisé lors de la regénération d'échéancier. */
 void deleteByAssetId(UUID assetId);

 /** Compte le nombre de composants d'un asset — utilisé pour décider du mode de calcul
 * d'amortissement (par composant vs global). */
 long countByAssetId(UUID assetId);
}

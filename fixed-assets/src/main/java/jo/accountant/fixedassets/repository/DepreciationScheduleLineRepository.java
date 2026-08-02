package jo.accountant.fixedassets.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.fixedassets.entity.DepreciationScheduleLine;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des lignes d'échéancier d'amortissement.
 
 *
 * @author jo@Dev


*/
public interface DepreciationScheduleLineRepository
    extends JpaRepository<DepreciationScheduleLine, UUID> {

    /** Toutes les lignes d'un actif, triées par date de période. */
    List<DepreciationScheduleLine> findByAssetIdOrderByPeriodDate(UUID assetId);

    /** Ligne pour un (actif, période) — utilisé pour vérifier l'unicité au postage. */
    Optional<DepreciationScheduleLine> findByAssetIdAndPeriodId(UUID assetId, UUID periodId);

    /** Lignes non postées d'un actif, triées par date. */
    List<DepreciationScheduleLine> findByAssetIdAndJournalEntryIdIsNullOrderByPeriodDate(UUID assetId);
}

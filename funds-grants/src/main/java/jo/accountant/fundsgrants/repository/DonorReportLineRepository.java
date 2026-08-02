package jo.accountant.fundsgrants.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.CostCategory;
import jo.accountant.fundsgrants.entity.DonorReportLine;
import jo.accountant.fundsgrants.entity.DonorType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des lignes de rapport bailleur (v6-3 — formats bailleurs structurés).
 *
 * <p>Les méthodes de recherche sont par (companyId, ...) et non par (id, ...) pour
 * garantir le cloisonnement multi-tenant — un appelant ne peut jamais récupérer les
 * lignes d'un autre tenant même en connaissance d'ID.
 
 *
 * @author jo@Dev


*/
public interface DonorReportLineRepository extends JpaRepository<DonorReportLine, UUID> {

    /**
     * Toutes les lignes d'une subvention pour une année donnée (toutes quarters confondus).
     * Utilisé par les exports annuels (EU PRAG) et comme base pour les exports trimestriels.
     */
    List<DonorReportLine> findByCompanyIdAndGrantIdAndPeriodYear(UUID companyId,
                                                                  UUID grantId,
                                                                  int periodYear);

    /**
     * Toutes les lignes d'un type de bailleur pour une année donnée (vue consolidée
     * multi-subventions — utile pour un rapport global bailleur, pas utilisé par les
     * exports actuels mais prévu pour v7).
     */
    List<DonorReportLine> findByCompanyIdAndDonorTypeAndPeriodYear(UUID companyId,
                                                                    DonorType donorType,
                                                                    int periodYear);

    /**
     * Toutes les lignes d'un type de bailleur pour une année + trimestre donnés.
     * Utilisé par les exports trimestriels (USAID SF-425, Banque Mondiale) si l'on
     * souhaite filtrer par donor plutôt que par grant.
     */
    List<DonorReportLine> findByCompanyIdAndDonorTypeAndPeriodYearAndPeriodQuarter(
        UUID companyId, DonorType donorType, int periodYear, int periodQuarter);

    /**
     * V7-1 — Upsert lookup : trouve la ligne existante pour un (grant, year, quarter, category).
     * Utilisé par {@code DonorReportFeedingService} pour upsert (création si absente,
     * mise à jour actual_amount si présente — budgetAmount préservé).
     */
    Optional<DonorReportLine> findByCompanyIdAndGrantIdAndPeriodYearAndPeriodQuarterAndCostCategory(
        UUID companyId, UUID grantId, int periodYear, Integer periodQuarter, CostCategory costCategory);

    /**
     * V7-1 — Toutes les lignes d'un (grant, year, quarter) pour re-sommer après refresh.
     */
    List<DonorReportLine> findByCompanyIdAndGrantIdAndPeriodYearAndPeriodQuarter(
        UUID companyId, UUID grantId, int periodYear, Integer periodQuarter);
}

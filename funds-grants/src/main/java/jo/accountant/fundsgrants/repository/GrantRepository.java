package jo.accountant.fundsgrants.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.Grant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository JPA Grant.
 *
 * @author jo@Dev


 */

public interface GrantRepository extends JpaRepository<Grant, UUID> {
    List<Grant> findByCompanyId(UUID companyId);
    List<Grant> findByDonorThirdPartyId(UUID donorThirdPartyId);

    /**
     * V7-1 — Identifiants distincts des companies ayant au moins une subvention active.
     * Utilisé par {@code DonorReportFeedingService.refreshMonthly} pour ne scanner que
     * les tenants pertinents lors du refresh mensuel cron.
     */
    @Query("SELECT DISTINCT g.companyId FROM Grant g WHERE g.companyId IS NOT NULL")
    List<UUID> findDistinctCompanyIdsWithActiveGrants();

    /**
     * V7-1 — Subventions actives d'une company (non expirées à la date donnée).
     * Utilisé pour ne rafraîchir que les lignes pertinentes.
     */
    @Query("SELECT g FROM Grant g WHERE g.companyId = :companyId " +
           "AND (g.endDate IS NULL OR g.endDate >= CURRENT_DATE)")
    List<Grant> findActiveByCompanyId(@Param("companyId") UUID companyId);
}

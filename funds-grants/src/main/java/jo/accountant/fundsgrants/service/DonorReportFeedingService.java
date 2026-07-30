package jo.accountant.fundsgrants.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.CostCategory;
import jo.accountant.fundsgrants.entity.DonorReportLine;
import jo.accountant.fundsgrants.entity.DonorType;
import jo.accountant.fundsgrants.repository.DonorReportLineRepository;
import jo.accountant.fundsgrants.repository.GrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V7-1 — Alimentation automatique de {@code donor_report_line}.
 *
 * <p>Lit la vue matérialisée {@code donor_report_actuals_mv} (créée par la migration V72)
 * et upsert les lignes dans {@code donor_report_line} pour chaque
 * (grant, period_year, period_quarter, cost_category).
 *
 * <p>La MV agrège par mois (period_month), on consolide ensuite par quarter côté Java
 * (somme des 3 mois du quarter). Cela permet de garder la MV simple (une seule granularité)
 * tout en exposant les deux niveaux (annuel pour EU PRAG, trimestriel pour USAID/BM).
 *
 * <p>Planification :
 * <ul>
 *   <li>Cron ShedLock mensuel — 1er du mois à 02:00 UTC (rafraîchit l'année courante).</li>
 *   <li>Endpoint manuel — {@code POST /donor-reports/refresh?year=&quarter=} pour rafraîchir
 *       une période précise (tests, catch-up après import rétroactif).</li>
 * </ul>
 *
 * <p>Politique d'upsert : si la ligne existe déjà, on écrase {@code actual_amount} mais on
 * préserve {@code budget_amount} (saisi manuellement via endpoint séparé). {@code cost_share_amount}
 * est également préservé.
 */
@Service
public class DonorReportFeedingService {

    private static final Logger LOG = LoggerFactory.getLogger(DonorReportFeedingService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DonorReportLineRepository donorReportLineRepository;
    private final GrantRepository grantRepository;

    public DonorReportFeedingService(JdbcTemplate jdbcTemplate,
                                      DonorReportLineRepository donorReportLineRepository,
                                      GrantRepository grantRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.donorReportLineRepository = donorReportLineRepository;
        this.grantRepository = grantRepository;
    }

    /**
     * Cron mensuel — 1er du mois à 02:00 UTC. Rafraîchit la MV puis upsert les lignes
     * pour l'année courante (tous les quarters 1-4).
     *
     * <p>Note : l'annotation {@code @SchedulerLock} ShedLock est ajoutée via déclaration
     * manuelle si ShedLock est activé sur le projet. Sinon, le cron est simplement
     * redondant sur les instances multiples (sans gravité — upsert est idempotent).
     */
    @Scheduled(cron = "0 0 2 1 * *")
    @Transactional
    public void refreshMonthly() {
        LOG.info("V7-1 — Refresh mensuel donor_report_line démarré");
        try {
            refreshMaterializedView();
            int year = LocalDate.now().getYear();
            int total = 0;
            for (int q = 1; q <= 4; q++) {
                List<UUID> companyIds = grantRepository.findDistinctCompanyIdsWithActiveGrants();
                for (UUID companyId : companyIds) {
                    total += upsertDonorReportLines(companyId, year, q);
                }
            }
            LOG.info("V7-1 — Refresh mensuel donor_report_line terminé — {} lignes upserted", total);
        } catch (Exception e) {
            LOG.error("V7-1 — Échec refresh mensuel donor_report_line", e);
            // Ne pas propager — un échec cron ne doit pas crasher l'app
        }
    }

    /**
     * Endpoint manuel — rafraîchissement on-demand (pour tests ou catch-up).
     *
     * @param companyId tenant
     * @param year      année fiscale (ex: 2026)
     * @param quarter   trimestre 1-4
     * @return nombre de lignes upserted
     */
    @Transactional
    public int refreshForPeriod(UUID companyId, int year, int quarter) {
        LOG.info("V7-1 — Refresh manuel companyId={} year={} quarter={}", companyId, year, quarter);
        refreshMaterializedView();
        return upsertDonorReportLines(companyId, year, quarter);
    }

    /**
     * V7-1 — Met à jour les budgets saisis manuellement pour un (grant, year, quarter).
     *
     * @param companyId        tenant
     * @param grantId          subvention
     * @param year             année fiscale
     * @param quarter          trimestre (1-4) ou null pour annuel
     * @param budgetsByCategory map cost_category → montant budget
     */
    @Transactional
    public int updateBudget(UUID companyId, UUID grantId, int year, Integer quarter,
                             Map<CostCategory, BigDecimal> budgetsByCategory) {
        int updated = 0;
        for (Map.Entry<CostCategory, BigDecimal> entry : budgetsByCategory.entrySet()) {
            DonorReportLine line = donorReportLineRepository
                .findByCompanyIdAndGrantIdAndPeriodYearAndPeriodQuarterAndCostCategory(
                    companyId, grantId, year, quarter, entry.getKey())
                .orElseGet(() -> {
                    // Si la ligne n'existe pas encore (pas encore alimentée par actual),
                    // on la crée avec actual_amount = 0. Le donor_type est résolu depuis le grant.
                    DonorReportLine newLine = new DonorReportLine();
                    newLine.setGrantId(grantId);
                    newLine.setDonorType(resolveDonorType(companyId, grantId));
                    newLine.setPeriodYear(year);
                    newLine.setPeriodQuarter(quarter);
                    newLine.setCostCategory(entry.getKey());
                    newLine.setActualAmount(BigDecimal.ZERO);
                    newLine.setCostShareAmount(BigDecimal.ZERO);
                    return newLine;
                });
            line.setBudgetAmount(entry.getValue());
            donorReportLineRepository.save(line);
            updated++;
        }
        LOG.info("V7-1 — {} budgets mis à jour pour companyId={} grantId={} year={} quarter={}",
            updated, companyId, grantId, year, quarter);
        return updated;
    }

    private void refreshMaterializedView() {
        // CONCURRENTLY requiert un index unique (créé en V72).
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY donor_report_actuals_mv");
        } catch (Exception e) {
            // Fallback : si la MV n'existe pas encore ou si CONCURRENTLY échoue (ex: première
            // exécution), on retente sans CONCURRENTLY (bloquant mais fonctionne à froid).
            LOG.warn("V7-1 — REFRESH CONCURRENTLY a échoué, fallback non-concurrent : {}", e.getMessage());
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW donor_report_actuals_mv");
        }
    }

    /**
     * Upsert des lignes pour un (company, year, quarter) — lit la MV et consolide les
     * months du quarter en une seule ligne par cost_category.
     */
    private int upsertDonorReportLines(UUID companyId, int year, int quarter) {
        // Lecture depuis la MV — filtre company + year + quarter
        // La MV expose period_month ; on filtre par les 3 mois du quarter.
        int startMonth = (quarter - 1) * 3 + 1;
        int endMonth = startMonth + 2;

        List<MvRow> rows = jdbcTemplate.query(
            "SELECT grant_id, donor_type, period_year, period_quarter, cost_category, SUM(actual_amount) AS actual_amount " +
            "FROM donor_report_actuals_mv " +
            "WHERE company_id = ? AND period_year = ? AND period_quarter = ? " +
            "GROUP BY grant_id, donor_type, period_year, period_quarter, cost_category",
            (rs, rowNum) -> new MvRow(
                UUID.fromString(rs.getString("grant_id")),
                DonorType.valueOf(rs.getString("donor_type")),
                rs.getInt("period_year"),
                rs.getInt("period_quarter"),
                CostCategory.valueOf(rs.getString("cost_category")),
                rs.getBigDecimal("actual_amount") != null
                    ? rs.getBigDecimal("actual_amount")
                    : BigDecimal.ZERO
            ),
            companyId, year, quarter
        );

        int upserted = 0;
        for (MvRow row : rows) {
            DonorReportLine existing = donorReportLineRepository
                .findByCompanyIdAndGrantIdAndPeriodYearAndPeriodQuarterAndCostCategory(
                    companyId, row.grantId(), row.periodYear(), row.periodQuarter(), row.costCategory())
                .orElse(null);

            if (existing == null) {
                DonorReportLine newLine = new DonorReportLine();
                newLine.setGrantId(row.grantId());
                newLine.setDonorType(row.donorType());
                newLine.setPeriodYear(row.periodYear());
                newLine.setPeriodQuarter(row.periodQuarter());
                newLine.setCostCategory(row.costCategory());
                newLine.setBudgetAmount(BigDecimal.ZERO);  // à saisir via endpoint séparé
                newLine.setActualAmount(row.actualAmount());
                newLine.setCostShareAmount(BigDecimal.ZERO);
                donorReportLineRepository.save(newLine);
            } else {
                // Préserve budgetAmount et costShareAmount — écrase uniquement actualAmount
                existing.setActualAmount(row.actualAmount());
                donorReportLineRepository.save(existing);
            }
            upserted++;
        }

        LOG.info("V7-1 — {} lignes upserted pour companyId={} year={} quarter={}",
            upserted, companyId, year, quarter);
        return upserted;
    }

    private DonorType resolveDonorType(UUID companyId, UUID grantId) {
        // Récupère le donor_type depuis le grant via le repository.
        return grantRepository.findById(grantId)
            .map(g -> {
                // Le grant ne stocke pas directement donor_type — il pointe vers un ThirdParty DONOR.
                // On dérive le DonorType depuis le code du grant (préfixe) ou on default à OTHER.
                // Pour éviter une dépendance circulaire avec ThirdParties, on utilise une requête
                // JDBC simple.
                try {
                    String donorType = jdbcTemplate.queryForObject(
                        "SELECT CASE " +
                        "  WHEN code LIKE 'USAID%' THEN 'USAID' " +
                        "  WHEN code LIKE 'EU%' THEN 'EU' " +
                        "  WHEN code LIKE 'BM%' OR code LIKE 'WB%' OR code LIKE 'WORLD%' THEN 'WORLD_BANK' " +
                        "  WHEN code LIKE 'CRS%' THEN 'CRS' " +
                        "  ELSE 'OTHER' END " +
                        "FROM fg_grant WHERE id = ?",
                        String.class, grantId);
                    return DonorType.valueOf(donorType);
                } catch (Exception e) {
                    LOG.warn("V7-1 — Impossible de résoudre donor_type pour grant {}, défaut OTHER", grantId);
                    return DonorType.OTHER;
                }
            })
            .orElse(DonorType.OTHER);
    }

    /** Row interne — une agrégation lue depuis la MV. */
    private record MvRow(
        UUID grantId, DonorType donorType, int periodYear, int periodQuarter,
        CostCategory costCategory, BigDecimal actualAmount
    ) {}
}

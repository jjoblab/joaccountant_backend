package jo.accountant.reporting.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Tableau de bord de synthèse (§13 Phase 17).
 *
 * <p>Position de trésorerie, balance âgée clients/fournisseurs, principales charges.
 *
 * <p><b>Analytics Dashboard</b> : 5 nouveaux champs
 * optionnels ont été ajoutés en bas du record pour exposer les données
 * analytiques (ratios financiers, top tiers, alertes, comparaison de
 * période). Tous sont {@code nullable} pour ne pas casser les clients
 * existants qui construisent le DTO avec le constructeur backward-compat à
 * 8 champs (les champs analytiques seront alors à {@code null} côté
 * backend, et le mobile affichera "Données indisponibles").
 *
 * <p>Le contrôleur {@code ReportingController.getDashboard} renvoie
 * désormais le DTO complet avec analytics peuplés. Les tests existants qui
 * construisent un {@code Dashboard} via le constructeur 8-args sont
 * rétro-compatibles : les nouveaux champs seront simplement à null.
 */
public record Dashboard(
 UUID companyId,
 BigDecimal cashPosition,
 BigDecimal totalReceivables,
 BigDecimal totalPayables,
 List<CategoryAmount> topExpenses,
 List<CategoryAmount> topRevenues,
 int pendingApprovals,
 int overdueInvoices,
 List<AnalyticsRatio> ratios,
 List<AnalyticsTopEntity> topClients,
 List<AnalyticsTopEntity> topSuppliers,
 List<AnalyticsAlert> alerts,
 AnalyticsPeriodComparison periodComparison
) {
 public record CategoryAmount(String category, BigDecimal amount) {}

 /**
 * Constructeur backward-compat (v5.5) — 8 champs, sans analytics.
 *
 * <p>Les 5 champs analytiques sont positionnés à {@code null}. Le mobile
 * gère ce cas en affichant "Données indisponibles" dans les sections
 * analytiques sans bloquer le reste du dashboard.
 */
 public Dashboard(UUID companyId,
 BigDecimal cashPosition,
 BigDecimal totalReceivables,
 BigDecimal totalPayables,
 List<CategoryAmount> topExpenses,
 List<CategoryAmount> topRevenues,
 int pendingApprovals,
 int overdueInvoices) {
 this(companyId, cashPosition, totalReceivables, totalPayables,
 topExpenses, topRevenues, pendingApprovals, overdueInvoices,
 null, null, null, null, null);
 }
}

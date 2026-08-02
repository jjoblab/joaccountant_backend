package jo.accountant.fundsgrants.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.fundsgrants.entity.CostCategory;
import jo.accountant.fundsgrants.entity.DonorReportLine;
import jo.accountant.fundsgrants.entity.Grant;
import jo.accountant.fundsgrants.repository.DonorReportLineRepository;
import jo.accountant.fundsgrants.repository.GrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Export des rapports bailleurs aux formats structurés (v6-3 — formats bailleurs).
 *
 * <p>Implémente trois formats conformes aux exigences des bailleurs institutionnels :
 * <ul>
 * <li>{@link #exportUsaidSf425(UUID, UUID, int, int)} — USAID SF-425 Federal Financial Report
 * (trimestriel, format CSV structuré Section A + Section B par cost category).</li>
 * <li>{@link #exportEuPrag(UUID, UUID, int)} — EU PRAG Annual Financial Report (annuel,
 * format CSV avec co-financing et EU contribution).</li>
 * <li>{@link #exportWorldBank(UUID, UUID, int, int)} — Banque Mondiale Quarterly Financial
 * Report (trimestriel, format CSV avec overheads et contingencies).</li>
 * </ul>
 *
 * <p><b>ÉTAT D'AVANCEMENT</b> : squelette. Le service agrège les
 * {@link DonorReportLine} alimentées par (grant, year, quarter, cost_category). En
 * l'absence de lignes (cas actuel — l'alimentation réelle via tagging comptable est à
 * implémenter en v7), l'export produit un CSV structurellement valide avec des zéros :
 * les équipes finance peuvent déjà valider le format auprès des bailleurs.
 *
 * <p><b>Encodage</b> : UTF-8 avec BOM (pour compatibilité Excel français — sinon les
 * caractères accentués sont mal interprétés), séparateur point-virgule, fins de ligne CRLF.
 
 *
 * @author jo@Dev


*/
@Service
public class DonorReportExporter {

    private static final Logger LOG = LoggerFactory.getLogger(DonorReportExporter.class);
    private static final String LINE_SEP = "\r\n";
    /** BOM UTF-8 — trois octets prefixant le stream pour Excel français. */
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final DonorReportLineRepository reportLineRepository;
    private final GrantRepository grantRepository;
    private final CompanyRepository companyRepository;

    public DonorReportExporter(DonorReportLineRepository reportLineRepository,
                                GrantRepository grantRepository,
                                CompanyRepository companyRepository) {
        this.reportLineRepository = reportLineRepository;
        this.grantRepository = grantRepository;
        this.companyRepository = companyRepository;
    }

    // ======================================================================
    // === USAID SF-425 (Federal Financial Report) — trimestriel ===========
    // ======================================================================

    /**
     * Génère le CSV au format USAID SF-425 (Federal Financial Report) pour le trimestre
     * et l'année donnés.
     *
     * <p>Structure :
     * <ul>
     * <li>En-tête identifiant (Grant ID, Reporting Period, Recipient Name).</li>
     * <li>Section A — Status of Federal Funding (lignes 10a à 10i).</li>
     * <li>Section B — Expenditures by Cost Category (8 catégories + total).</li>
     * </ul>
     *
     * @param companyId ID du tenant (sécurité multi-tenant)
     * @param grantId ID de la subvention
     * @param year Année fiscale (ex: 2026)
     * @param quarter Trimestre 1-4
     * @return CSV UTF-8 BOM, séparateur point-virgule, CRLF
     */
    public byte[] exportUsaidSf425(UUID companyId, UUID grantId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("Quarter must be 1-4, got: " + quarter);
        }
        LOG.debug("Export USAID SF-425: companyId={}, grantId={}, year={}, quarter={}",
            companyId, grantId, year, quarter);

        Grant grant = loadGrant(companyId, grantId);
        String recipientName = loadCompanyName(companyId);
        Map<CostCategory, CategoryTotals> byCategory = aggregateLines(
            companyId, grantId, year, quarter, true);

        CategoryTotals totals = CategoryTotals.sum(byCategory.values());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(UTF8_BOM, 0, UTF8_BOM.length);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            // === En-tête ===
            pw.print("USAID SF-425 Federal Financial Report" + LINE_SEP);
            pw.print("Grant ID;" + grant.getId() + LINE_SEP);
            pw.print("Grant Code;" + safe(grant.getCode()) + LINE_SEP);
            pw.print("Reporting Period;Q" + quarter + " FY" + year + LINE_SEP);
            pw.print("Recipient Name;" + recipientName + LINE_SEP);
            pw.print("Currency;" + safe(grant.getCurrency()) + LINE_SEP);
            pw.print(LINE_SEP);

            // === Section A — Status of Federal Funding ===
            pw.print("SECTION A - Status of Federal Funding" + LINE_SEP);
            // 10a — Total Federal funds authorized (montant total du grant)
            pw.print("Line 10a. Total Federal funds authorized;"
                + money(grant.getTotalAmount()) + LINE_SEP);
            // 10b — Federal funds authorized for this period (sum budget)
            pw.print("Line 10b. Federal funds authorized for this period;"
                + money(totals.budget) + LINE_SEP);
            // 10c — Total Federal funds drawn (sum actual)
            pw.print("Line 10c. Total Federal funds drawn;"
                + money(totals.actual) + LINE_SEP);
            // 10d — Federal share of expenditures (sum actual)
            pw.print("Line 10d. Federal share of expenditures;"
                + money(totals.actual) + LINE_SEP);
            // 10e — Unliquidated obligations (skeleton — 0, sera calculé en v7)
            pw.print("Line 10e. Federal share of unliquidated obligations;"
                + money(BigDecimal.ZERO) + LINE_SEP);
            // 10f — Total Federal share = 10d + 10e
            BigDecimal federalShareTotal = totals.actual.add(BigDecimal.ZERO);
            pw.print("Line 10f. Total Federal share (sum of 10d + 10e);"
                + money(federalShareTotal) + LINE_SEP);
            // 10g — Unobligated balance = budget - actual (sum variance)
            BigDecimal varianceTotal = totals.budget.subtract(totals.actual);
            pw.print("Line 10g. Unobligated balance of Federal funds;"
                + money(varianceTotal) + LINE_SEP);
            // 10h — Recipient share (cost share)
            pw.print("Line 10h. Recipient share;"
                + money(totals.costShare) + LINE_SEP);
            // 10i — Total recipient share (cumulative — skeleton = same as 10h)
            pw.print("Line 10i. Total recipient share;"
                + money(totals.costShare) + LINE_SEP);
            pw.print(LINE_SEP);

            // === Section B — Expenditures by Cost Category ===
            pw.print("SECTION B - Expenditures by Cost Category" + LINE_SEP);
            pw.print("Cost Category;Budget;Actual;Variance" + LINE_SEP);
            for (CostCategory cat : CostCategory.values()) {
                CategoryTotals t = byCategory.getOrDefault(cat, CategoryTotals.ZERO);
                pw.print(cat.name() + ";"
                    + money(t.budget) + ";"
                    + money(t.actual) + ";"
                    + money(t.variance()) + LINE_SEP);
            }
            pw.print("TOTAL;"
                + money(totals.budget) + ";"
                + money(totals.actual) + ";"
                + money(totals.variance()) + LINE_SEP);
        }

        return baos.toByteArray();
    }

    // ======================================================================
    // === EU PRAG (Annual Financial Report) — annuel ======================
    // ======================================================================

    /**
     * Génère le CSV au format EU PRAG Annual Financial Report pour l'année donnée.
     *
     * <p>Structure :
     * <ul>
     * <li>En-tête (Grant Agreement, Beneficiary, Reporting Period).</li>
     * <li>Expenditures by Cost Category (8 catégories + total, avec pourcentage).</li>
     * <li>Co-financing, Total eligible expenditures, EU contribution.</li>
     * </ul>
     *
     * <p><b>Note sur le cofinancing rate</b> : calculé ici comme
     * {@code 1 − costShare / actualTotal}. En v7, ce taux sera configurable par grant
     * (colonne dédiée dans {@code fg_grant}) — pour l'instant, il est dérivé des montants
     * constatés. Si actualTotal = 0, on affiche 0% pour éviter une division par zéro.
     *
     * @param companyId ID du tenant
     * @param grantId ID de la subvention
     * @param year Année (ex: 2026)
     * @return CSV UTF-8 BOM, séparateur point-virgule, CRLF
     */
    public byte[] exportEuPrag(UUID companyId, UUID grantId, int year) {
        LOG.debug("Export EU PRAG: companyId={}, grantId={}, year={}", companyId, grantId, year);

        Grant grant = loadGrant(companyId, grantId);
        String beneficiary = loadCompanyName(companyId);
        Map<CostCategory, CategoryTotals> byCategory = aggregateLines(
            companyId, grantId, year, null, false);

        CategoryTotals totals = CategoryTotals.sum(byCategory.values());

        // Co-financing rate dérivé (placeholder v6-3 — config par grant en v7).
        BigDecimal cofinancingRate;
        BigDecimal euContribution;
        if (totals.actual.signum() > 0) {
            // EU contribution = actual − cost share
            euContribution = totals.actual.subtract(totals.costShare)
                .max(BigDecimal.ZERO);
            cofinancingRate = euContribution.divide(totals.actual, 4, RoundingMode.HALF_UP);
        } else {
            euContribution = BigDecimal.ZERO;
            cofinancingRate = BigDecimal.ZERO;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(UTF8_BOM, 0, UTF8_BOM.length);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            // === En-tête ===
            pw.print("EU PRAG - Annual Financial Report" + LINE_SEP);
            pw.print("Grant Agreement;" + safe(grant.getCode()) + LINE_SEP);
            pw.print("Grant Label;" + safe(grant.getLabel()) + LINE_SEP);
            pw.print("Beneficiary;" + beneficiary + LINE_SEP);
            pw.print("Reporting Period;FY" + year + LINE_SEP);
            pw.print("Currency;" + safe(grant.getCurrency()) + LINE_SEP);
            pw.print("Total Grant Amount;" + money(grant.getTotalAmount()) + LINE_SEP);
            pw.print(LINE_SEP);

            // === Expenditures by Cost Category ===
            pw.print("Expenditures by Cost Category" + LINE_SEP);
            pw.print("Cost Category;Budget;Actual;Variance;% of Total Actual" + LINE_SEP);
            for (CostCategory cat : CostCategory.values()) {
                CategoryTotals t = byCategory.getOrDefault(cat, CategoryTotals.ZERO);
                String pct = percentage(t.actual, totals.actual);
                pw.print(cat.name() + ";"
                    + money(t.budget) + ";"
                    + money(t.actual) + ";"
                    + money(t.variance()) + ";"
                    + pct + LINE_SEP);
            }
            pw.print("TOTAL;"
                + money(totals.budget) + ";"
                + money(totals.actual) + ";"
                + money(totals.variance()) + ";"
                + "100.00" + LINE_SEP);
            pw.print(LINE_SEP);

            // === Synthèse financière ===
            pw.print("Co-financing (cost share);" + money(totals.costShare) + LINE_SEP);
            pw.print("Total eligible expenditures;" + money(totals.actual) + LINE_SEP);
            pw.print("EU contribution;" + money(euContribution) + LINE_SEP);
            pw.print("Co-financing rate (derived);" + percentage(cofinancingRate, BigDecimal.ONE) + LINE_SEP);
        }

        return baos.toByteArray();
    }

    // ======================================================================
    // === Banque Mondiale (Quarterly Financial Report) — trimestriel =====
    // ======================================================================

    /**
     * Génère le CSV au format Banque Mondiale Quarterly Financial Report pour le
     * trimestre et l'année donnés.
     *
     * <p>Structure similaire à USAID SF-425 (Section A + Section B par catégorie) avec
     * les spécificités BM :
     * <ul>
     * <li>Terminologie : "Borrower/Recipient", "Grant No", "Withdrawal Applications".</li>
     * <li>Section B inclut une ligne "Contingencies" (skeleton = 0 — pas de
     * CostCategory dédiée, gérée via OTHER en v7 si nécessaire).</li>
     * <li>La catégorie {@link CostCategory#INDIRECT_COST} est libellée
     * "Overhead/Indirect Costs" (alignée sur la nomenclature BM).</li>
     * </ul>
     *
     * @param companyId ID du tenant
     * @param grantId ID de la subvention
     * @param year Année fiscale (ex: 2026)
     * @param quarter Trimestre 1-4
     * @return CSV UTF-8 BOM, séparateur point-virgule, CRLF
     */
    public byte[] exportWorldBank(UUID companyId, UUID grantId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("Quarter must be 1-4, got: " + quarter);
        }
        LOG.debug("Export World Bank QFR: companyId={}, grantId={}, year={}, quarter={}",
            companyId, grantId, year, quarter);

        Grant grant = loadGrant(companyId, grantId);
        String borrower = loadCompanyName(companyId);
        Map<CostCategory, CategoryTotals> byCategory = aggregateLines(
            companyId, grantId, year, quarter, true);

        CategoryTotals totals = CategoryTotals.sum(byCategory.values());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(UTF8_BOM, 0, UTF8_BOM.length);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            // === En-tête ===
            pw.print("World Bank - Quarterly Financial Report" + LINE_SEP);
            pw.print("Grant No;" + safe(grant.getCode()) + LINE_SEP);
            pw.print("Project Name;" + safe(grant.getLabel()) + LINE_SEP);
            pw.print("Borrower/Recipient;" + borrower + LINE_SEP);
            pw.print("Reporting Period;Q" + quarter + " FY" + year + LINE_SEP);
            pw.print("Currency;" + safe(grant.getCurrency()) + LINE_SEP);
            pw.print(LINE_SEP);

            // === Section A — Withdrawal Applications ===
            pw.print("SECTION A - Withdrawal Applications" + LINE_SEP);
            pw.print("Total grant amount;" + money(grant.getTotalAmount()) + LINE_SEP);
            pw.print("Total cumulative withdrawals (actual);" + money(totals.actual) + LINE_SEP);
            BigDecimal unliquidated = totals.budget.subtract(totals.actual);
            pw.print("Unliquidated balance (variance);" + money(unliquidated) + LINE_SEP);
            pw.print("Borrower contribution (cost share);" + money(totals.costShare) + LINE_SEP);
            pw.print(LINE_SEP);

            // === Section B — Expenditures by Category ===
            pw.print("SECTION B - Expenditures by Category" + LINE_SEP);
            pw.print("Category;Budget;Actual;Variance" + LINE_SEP);
            pw.print("Personnel;"
                + money(cat(byCategory, CostCategory.PERSONNEL).budget) + ";"
                + money(cat(byCategory, CostCategory.PERSONNEL).actual) + ";"
                + money(cat(byCategory, CostCategory.PERSONNEL).variance()) + LINE_SEP);
            pw.print("Fringe Benefits;"
                + money(cat(byCategory, CostCategory.FRINGE).budget) + ";"
                + money(cat(byCategory, CostCategory.FRINGE).actual) + ";"
                + money(cat(byCategory, CostCategory.FRINGE).variance()) + LINE_SEP);
            pw.print("Travel;"
                + money(cat(byCategory, CostCategory.TRAVEL).budget) + ";"
                + money(cat(byCategory, CostCategory.TRAVEL).actual) + ";"
                + money(cat(byCategory, CostCategory.TRAVEL).variance()) + LINE_SEP);
            pw.print("Equipment;"
                + money(cat(byCategory, CostCategory.EQUIPMENT).budget) + ";"
                + money(cat(byCategory, CostCategory.EQUIPMENT).actual) + ";"
                + money(cat(byCategory, CostCategory.EQUIPMENT).variance()) + LINE_SEP);
            pw.print("Supplies;"
                + money(cat(byCategory, CostCategory.SUPPLIES).budget) + ";"
                + money(cat(byCategory, CostCategory.SUPPLIES).actual) + ";"
                + money(cat(byCategory, CostCategory.SUPPLIES).variance()) + LINE_SEP);
            pw.print("Contractual Services;"
                + money(cat(byCategory, CostCategory.CONTRACTUAL).budget) + ";"
                + money(cat(byCategory, CostCategory.CONTRACTUAL).actual) + ";"
                + money(cat(byCategory, CostCategory.CONTRACTUAL).variance()) + LINE_SEP);
            pw.print("Other Direct Costs;"
                + money(cat(byCategory, CostCategory.OTHER).budget) + ";"
                + money(cat(byCategory, CostCategory.OTHER).actual) + ";"
                + money(cat(byCategory, CostCategory.OTHER).variance()) + LINE_SEP);
            pw.print("Overhead/Indirect Costs;"
                + money(cat(byCategory, CostCategory.INDIRECT_COST).budget) + ";"
                + money(cat(byCategory, CostCategory.INDIRECT_COST).actual) + ";"
                + money(cat(byCategory, CostCategory.INDIRECT_COST).variance()) + LINE_SEP);
            // Contingencies — pas de CostCategory dédiée, skeleton = 0.
            // En v7, ajout éventuel d'une catégorie CONTINGENCIES ou mapping via OTHER.
            BigDecimal contingenciesBudget = BigDecimal.ZERO;
            BigDecimal contingenciesActual = BigDecimal.ZERO;
            BigDecimal contingenciesVariance = contingenciesBudget.subtract(contingenciesActual);
            pw.print("Contingencies;"
                + money(contingenciesBudget) + ";"
                + money(contingenciesActual) + ";"
                + money(contingenciesVariance) + LINE_SEP);
            // Total
            pw.print("TOTAL;"
                + money(totals.budget) + ";"
                + money(totals.actual) + ";"
                + money(totals.variance()) + LINE_SEP);
        }

        return baos.toByteArray();
    }

    // ======================================================================
    // === Helpers ==========================================================
    // ======================================================================

    /**
     * Charge une subvention et vérifie qu'elle appartient bien au tenant companyId
     * (défense en profondeur — Section A,.
     */
    private Grant loadGrant(UUID companyId, UUID grantId) {
        Grant grant = grantRepository.findById(grantId)
            .orElseThrow(() -> new NotFoundException("Grant", grantId));
        if (!companyId.equals(grant.getCompanyId())) {
            // Defense-in-depth : ne pas révéler l'existence du grant à un autre tenant.
            throw new NotFoundException("Grant", grantId);
        }
        return grant;
    }

    private String loadCompanyName(UUID companyId) {
        return companyRepository.findById(companyId)
            .map(Company::getName)
            .orElse("(unknown company)");
    }

    /**
     * Agrège les {@link DonorReportLine} par {@link CostCategory}.
     *
     * @param companyId ID du tenant
     * @param grantId ID du grant
     * @param year Année
     * @param quarter Trimestre (1-4) ou null pour annual
     * @param quarterlyFallbackInclNull si true, inclut aussi les lignes où period_quarter
     * est NULL (annual lines) — utile quand le job v7 n'a pas encore
     * peuplé les lignes trimestrielles mais a peuplé l'annual.
     */
    private Map<CostCategory, CategoryTotals> aggregateLines(UUID companyId, UUID grantId,
                                                              int year, Integer quarter,
                                                              boolean quarterlyFallbackInclNull) {
        List<DonorReportLine> all = reportLineRepository
            .findByCompanyIdAndGrantIdAndPeriodYear(companyId, grantId, year);

        Map<CostCategory, CategoryTotals> byCategory = new EnumMap<>(CostCategory.class);
        for (DonorReportLine line : all) {
            // Filtre par quarter pour les exports trimestriels
            if (quarter != null) {
                boolean matchesQuarter = quarter.equals(line.getPeriodQuarter());
                boolean isAnnualLine = line.getPeriodQuarter() == null;
                if (!matchesQuarter && !(quarterlyFallbackInclNull && isAnnualLine)) {
                    continue;
                }
            }
            // Pour les exports annuels (quarter == null), on inclut toutes les lignes
            // de l'année (annual + quarterly cumulées) — c'est le comportement attendu
            // d'un rapport annuel consolidé.

            CategoryTotals t = byCategory.computeIfAbsent(
                line.getCostCategory(), k -> new CategoryTotals());
            t.budget = t.budget.add(safe(line.getBudgetAmount()));
            t.actual = t.actual.add(safe(line.getActualAmount()));
            t.costShare = t.costShare.add(safe(line.getCostShareAmount()));
            // variance est calculée (pas lue depuis la DB — robustesse si la colonne
            // GENERATED n'est pas encore synchronisée dans l'entity après insert).
        }
        return byCategory;
    }

    private CategoryTotals cat(Map<CostCategory, CategoryTotals> map, CostCategory key) {
        return map.getOrDefault(key, CategoryTotals.ZERO);
    }

    /** Format monétaire : 2 décimales, séparateur point (locale US). */
    private static String money(BigDecimal amount) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        return String.format(Locale.US, "%.2f", v);
    }

    /** Format pourcentage : 2 décimales + signe %, ou "0.00" si dénominateur nul. */
    private static String percentage(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return "0.00";
        }
        BigDecimal pct = numerator.divide(denominator, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.US, "%.2f", pct);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Agrégat par cost category (mutable — utilisé pendant l'agrégation).
     */
    private static final class CategoryTotals {
        static final CategoryTotals ZERO = new CategoryTotals();

        BigDecimal budget = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal costShare = BigDecimal.ZERO;

        BigDecimal variance() {
            return budget.subtract(actual);
        }

        static CategoryTotals sum(Iterable<CategoryTotals> all) {
            CategoryTotals t = new CategoryTotals();
            for (CategoryTotals c : all) {
                t.budget = t.budget.add(c.budget);
                t.actual = t.actual.add(c.actual);
                t.costShare = t.costShare.add(c.costShare);
            }
            return t;
        }
    }
}

package jo.accountant.fundsgrants.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.fundsgrants.entity.CostCategory;
import jo.accountant.fundsgrants.service.DonorReportExporter;
import jo.accountant.fundsgrants.service.DonorReportFeedingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints d'export des rapports bailleurs aux formats structurés (v6-3).
 *
 * <p>Trois formats couverts par les bailleurs institutionnels les plus exigeants :
 * <ul>
 *   <li><b>USAID SF-425</b> — Federal Financial Report trimestriel.</li>
 *   <li><b>EU PRAG</b> — Annual Financial Report annuel.</li>
 *   <li><b>Banque Mondiale</b> — Quarterly Financial Report.</li>
 * </ul>
 *
 * <p>Tous les exports retournent un CSV UTF-8 avec BOM (compatibilité Excel français),
 * séparateur point-virgule, fins de ligne CRLF. Le {@code Content-Disposition} force le
 * téléchargement avec un nom de fichier prédéfini.
 *
 * <p><b>ÉTAT D'AVANCEMENT v6-3</b> : squelette — l'alimentation réelle des
 * {@code donor_report_line} (depuis les écritures comptables taguées par grant +
 * cost_category) sera implémentée en v7. En attendant, les exports retournent un CSV
 * valide structurellement avec des zéros, permettant aux équipes finance de valider
 * le format auprès des bailleurs.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/funds-grants")
@Tag(name = "DonorReportFormats",
     description = "Export des rapports bailleurs aux formats structurés (USAID SF-425, EU PRAG, Banque Mondiale) — v6-3")
public class DonorReportController {

    private final DonorReportExporter exporter;
    private final DonorReportFeedingService feedingService;
    private final RoleChecker roleChecker;
    private final ModuleAccessGuard moduleAccessGuard;

    public DonorReportController(DonorReportExporter exporter,
                                  DonorReportFeedingService feedingService,
                                  RoleChecker roleChecker,
                                  ModuleAccessGuard moduleAccessGuard) {
        this.exporter = exporter;
        this.feedingService = feedingService;
        this.roleChecker = roleChecker;
        this.moduleAccessGuard = moduleAccessGuard;
    }

    @Operation(summary = "Export USAID SF-425 (Federal Financial Report trimestriel)",
        description = "Génère un CSV structuré au format USAID SF-425 (Section A — Status of " +
                      "Federal Funding, Section B — Expenditures by Cost Category). " +
                      "UTF-8 BOM, séparateur point-virgule, CRLF. " +
                      "Téléchargement forcé via Content-Disposition: attachment.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "text/csv",
                schema = @Schema(type = "string", format = "binary"),
                examples = @ExampleObject(name = "USAID SF-425 Q1 FY2026", value = """
                    USAID SF-425 Federal Financial Report
                    Grant ID;0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd
                    Grant Code;USAID-2026-WASH
                    Reporting Period;Q1 FY2026
                    Recipient Name;Espwa pou Ayiti
                    Currency;USD

                    SECTION A - Status of Federal Funding
                    Line 10a. Total Federal funds authorized;500000.00
                    Line 10b. Federal funds authorized for this period;125000.00
                    Line 10c. Total Federal funds drawn;31000.00
                    Line 10d. Federal share of expenditures;31000.00
                    Line 10e. Federal share of unliquidated obligations;0.00
                    Line 10f. Total Federal share (sum of 10d + 10e);31000.00
                    Line 10g. Unobligated balance of Federal funds;94000.00
                    Line 10h. Recipient share;5000.00
                    Line 10i. Total recipient share;5000.00

                    SECTION B - Expenditures by Cost Category
                    Cost Category;Budget;Actual;Variance
                    PERSONNEL;50000.00;12500.00;37500.00
                    FRINGE;15000.00;3750.00;11250.00
                    TRAVEL;8000.00;2000.00;6000.00
                    EQUIPMENT;20000.00;0.00;20000.00
                    SUPPLIES;12000.00;3500.00;8500.00
                    CONTRACTUAL;10000.00;5000.00;5000.00
                    OTHER;5000.00;2000.00;3000.00
                    INDIRECT_COST;5000.00;2250.00;2750.00
                    TOTAL;125000.00;31000.00;94000.00
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum) ou module FUNDS_GRANTS désactivé",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Subvention introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/grants/{grantId}/donor-reports/usaid-sf425")
    public ResponseEntity<byte[]> exportUsaidSf425(@PathVariable UUID companyId,
                                                    @PathVariable UUID grantId,
                                                    @RequestParam int year,
                                                    @RequestParam int quarter,
                                                    @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        byte[] csv = exporter.exportUsaidSf425(companyId, grantId, year, quarter);
        String filename = String.format("usaid-sf425_%s_Q%d-FY%d.csv", grantId, quarter, year);
        return csvResponse(csv, filename);
    }

    @Operation(summary = "Export EU PRAG (Annual Financial Report annuel)",
        description = "Génère un CSV structuré au format EU PRAG Annual Financial Report " +
                      "(Expenditures by Cost Category avec pourcentages, Co-financing, " +
                      "Total eligible expenditures, EU contribution). " +
                      "UTF-8 BOM, séparateur point-virgule, CRLF.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "text/csv",
                schema = @Schema(type = "string", format = "binary"),
                examples = @ExampleObject(name = "EU PRAG FY2026", value = """
                    EU PRAG - Annual Financial Report
                    Grant Agreement;EU-2026-HEALTH
                    Grant Label;Programme santé communautaire
                    Beneficiary;Espwa pou Ayiti
                    Reporting Period;FY2026
                    Currency;EUR
                    Total Grant Amount;800000.00

                    Expenditures by Cost Category
                    Cost Category;Budget;Actual;Variance;% of Total Actual
                    PERSONNEL;300000.00;75000.00;225000.00;50.00
                    FRINGE;90000.00;22500.00;67500.00;15.00
                    TRAVEL;50000.00;12500.00;37500.00;8.33
                    EQUIPMENT;120000.00;30000.00;90000.00;20.00
                    SUPPLIES;70000.00;7500.00;62500.00;5.00
                    CONTRACTUAL;50000.00;0.00;50000.00;0.00
                    OTHER;10000.00;2500.00;7500.00;1.67
                    INDIRECT_COST;10000.00;0.00;10000.00;0.00
                    TOTAL;700000.00;150000.00;550000.00;100.00

                    Co-financing (cost share);30000.00
                    Total eligible expenditures;150000.00
                    EU contribution;120000.00
                    Co-financing rate (derived);80.00
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum) ou module FUNDS_GRANTS désactivé",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Subvention introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/grants/{grantId}/donor-reports/eu-prag")
    public ResponseEntity<byte[]> exportEuPrag(@PathVariable UUID companyId,
                                                @PathVariable UUID grantId,
                                                @RequestParam int year,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        byte[] csv = exporter.exportEuPrag(companyId, grantId, year);
        String filename = String.format("eu-prag_%s_FY%d.csv", grantId, year);
        return csvResponse(csv, filename);
    }

    @Operation(summary = "Export Banque Mondiale (Quarterly Financial Report trimestriel)",
        description = "Génère un CSV structuré au format Banque Mondiale Quarterly Financial " +
                      "Report (Section A — Withdrawal Applications, Section B — Expenditures " +
                      "by Category avec Overhead/Indirect Costs et Contingencies). " +
                      "UTF-8 BOM, séparateur point-virgule, CRLF.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "text/csv",
                schema = @Schema(type = "string", format = "binary"),
                examples = @ExampleObject(name = "World Bank Q1 FY2026", value = """
                    World Bank - Quarterly Financial Report
                    Grant No;BM-2026-EDUC
                    Project Name;Programme éducation rurale
                    Borrower/Recipient;Espwa pou Ayiti
                    Reporting Period;Q1 FY2026
                    Currency;USD

                    SECTION A - Withdrawal Applications
                    Total grant amount;1200000.00
                    Total cumulative withdrawals (actual);85000.00
                    Unliquidated balance (variance);215000.00
                    Borrower contribution (cost share);15000.00

                    SECTION B - Expenditures by Category
                    Category;Budget;Actual;Variance
                    Personnel;150000.00;40000.00;110000.00
                    Fringe Benefits;45000.00;12000.00;33000.00
                    Travel;30000.00;5000.00;25000.00
                    Equipment;100000.00;15000.00;85000.00
                    Supplies;40000.00;8000.00;32000.00
                    Contractual Services;50000.00;5000.00;45000.00
                    Other Direct Costs;10000.00;0.00;10000.00
                    Overhead/Indirect Costs;20000.00;0.00;20000.00
                    Contingencies;0.00;0.00;0.00
                    TOTAL;300000.00;85000.00;215000.00
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum) ou module FUNDS_GRANTS désactivé",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Subvention introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/grants/{grantId}/donor-reports/world-bank")
    public ResponseEntity<byte[]> exportWorldBank(@PathVariable UUID companyId,
                                                   @PathVariable UUID grantId,
                                                   @RequestParam int year,
                                                   @RequestParam int quarter,
                                                   @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        byte[] csv = exporter.exportWorldBank(companyId, grantId, year, quarter);
        String filename = String.format("world-bank_%s_Q%d-FY%d.csv", grantId, quarter, year);
        return csvResponse(csv, filename);
    }

    /** Construit la réponse HTTP avec headers CSV (Content-Type + Content-Disposition). */
    private ResponseEntity<byte[]> csvResponse(byte[] content, String filename) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"")
            .body(content);
    }

    // ======================================================================
    // === V7-1 — Alimentation automatique de donor_report_line ============
    // ======================================================================

    @Operation(summary = "V7-1 — Refresh manuel des actuals par période",
        description = "Rafraîchit la vue matérialisée donor_report_actuals_mv puis upsert " +
                      "les lignes donor_report_line pour la période demandée. " +
                      "Réservé ADMIN (le cron mensuel tourne automatiquement le 1er du mois à 02:00 UTC).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = Integer.class))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)")
    })
    @PostMapping("/donor-reports/refresh")
    public ResponseEntity<Integer> refreshDonorReportLines(
            @PathVariable UUID companyId,
            @RequestParam int year,
            @RequestParam int quarter,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        int count = feedingService.refreshForPeriod(companyId, year, quarter);
        return ResponseEntity.ok(count);
    }

    @Operation(summary = "V7-1 — Saisie manuelle du budget par cost category",
        description = "Permet à l'ACCOUNTANT de saisir les montants budget pour chaque " +
                      "cost_category d'un (grant, year, quarter). Les actuals sont alimentés " +
                      "automatiquement par refresh ; les budgets sont saisis manuellement " +
                      "ici (prévisionnel).")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Budgets mis à jour"),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ACCOUNTANT requis)")
    })
    @PutMapping("/grants/{grantId}/donor-reports/budget")
    public ResponseEntity<Void> updateBudget(
            @PathVariable UUID companyId,
            @PathVariable UUID grantId,
            @RequestParam int year,
            @RequestParam(required = false) Integer quarter,
            @RequestBody BudgetRequest req,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        feedingService.updateBudget(companyId, grantId, year, quarter, req.budgetsByCategory());
        return ResponseEntity.noContent().build();
    }

    /** V7-1 — Payload de saisie budget par cost_category. */
    public record BudgetRequest(
        Map<CostCategory, BigDecimal> budgetsByCategory
    ) {}
}

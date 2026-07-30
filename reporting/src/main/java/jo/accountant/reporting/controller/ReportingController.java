package jo.accountant.reporting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.reporting.dto.AgedBalance;
import jo.accountant.reporting.dto.Dashboard;
import jo.accountant.reporting.dto.ExportResult;
import jo.accountant.reporting.service.ReportingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de reporting (§13 Phase 17 — dernière phase).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/reporting")
@Tag(name = "Reporting", description = "Exports PDF/Excel, tableaux de bord (§13 Phase 17)")
public class ReportingController {

    private final ReportingService service;
    private final RoleChecker roleChecker;

    public ReportingController(ReportingService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Exporter un état financier ou comptable",
        description = "PDF : bilan, compte de résultat, rapport bailleur (via document-generation). " +
                      "CSV : grand livre, balance générale. " +
                      "Formats réglementaires (TAFIRE, liasse fiscale) : hors périmètre v1.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Export PDF/CSV binaire (attachment)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "422", description = "Statement inconnu ou format non supporté",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/exports/{statement}")
    public ResponseEntity<byte[]> export(
        @PathVariable UUID companyId,
        @PathVariable String statement,
        @CurrentUser UUID userId,
        @Parameter(description = "Format d'export : `pdf` (bilan, CR) ou `csv` (grand livre, balance)", example = "pdf")
        @RequestParam(defaultValue = "pdf") String format,
        @Parameter(description = "Date de début (incluse)", example = "2026-01-01")
        @RequestParam(required = false) LocalDate from,
        @Parameter(description = "Date de fin (incluse)", example = "2026-12-31")
        @RequestParam(required = false) LocalDate to,
        @Parameter(description = "ID optionnel d'une ressource (compte, tiers, projet) pour filtrer l'export")
        @RequestParam(required = false) UUID resourceId) {

        roleChecker.ensureRole(companyId, "VIEWER");
        ExportResult result = service.export(companyId, statement, format, from, to, resourceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, result.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + result.filename() + "\"")
            .body(result.content());
    }

    @Operation(summary = "Tableau de bord de synthèse",
        description = "Position de trésorerie, balance âgée clients/fournisseurs, " +
                      "principales charges, factures échues.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = Dashboard.class),
                examples = @ExampleObject(name = "Dashboard synthétique", value = """
                    {
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "cashPosition": 250000.00,
                      "outstandingInvoices": 85000.00,
                      "outstandingBills": 42000.00,
                      "nextPayrollRun": "2026-04-30",
                      "overdueInvoicesCount": 3
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/dashboard")
    public Dashboard getDashboard(@PathVariable UUID companyId,
                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getDashboard(companyId);
    }

    @Operation(summary = "Balance âgée clients (audit M5)",
        description = "Ventile le solde dû des factures ISSUED/PARTIALLY_PAID par tranche " +
                      "d'âge depuis la date d'échéance : current, 0-30, 31-60, 61-90, 90+ jours.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AgedBalance.class),
                examples = @ExampleObject(name = "Balance âgée clients", value = """
                    {
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "current": 15000.00,
                      "d0_30": 8000.00,
                      "d31_60": 3000.00,
                      "d61_90": 1500.00,
                      "d90_plus": 500.00,
                      "totalBalanceDue": 28000.00,
                      "invoiceCount": 12
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/aged-balance")
    public AgedBalance getAgedBalance(@PathVariable UUID companyId,
                                      @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getAgedBalance(companyId);
    }

    @Operation(summary = "Balance âgée fournisseurs (Part D1)",
        description = "Ventile le solde dû des factures d'achat RECEIVED/PARTIALLY_PAID par " +
                      "tranche d'âge depuis la date d'échéance : current, 0-30, 31-60, 61-90, " +
                      "90+ jours. Symétrique de la balance âgée clients. Note : le module " +
                      ":purchasing doit être activé pour cette société (gate appliquée au " +
                      "niveau du statement CSV `aged_balance_suppliers` ; cet endpoint JSON " +
                      "est laissé accessible aux VIEWER pour permettre l'affichage côté UI " +
                      "même si PURCHASING n'est pas encore activé — le résultat sera vide).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AgedBalance.class),
                examples = @ExampleObject(name = "Balance âgée fournisseurs", value = """
                    {
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "current": 12000.00,
                      "d0_30": 6000.00,
                      "d31_60": 2000.00,
                      "d61_90": 0,
                      "d90_plus": 0,
                      "totalBalanceDue": 20000.00,
                      "invoiceCount": 7
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/aged-balance-suppliers")
    public AgedBalance getSupplierAgedBalance(@PathVariable UUID companyId,
                                              @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getSupplierAgedBalance(companyId);
    }
}

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.documentgeneration.entity.DocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.reporting.dto.AgedBalance;
import jo.accountant.reporting.dto.Dashboard;
import jo.accountant.reporting.dto.ExportResult;
import jo.accountant.reporting.service.ReportingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(ReportingController.class);

    private final ReportingService service;
    private final RoleChecker roleChecker;
    private final DocumentGenerationService documentGenerationService;

    public ReportingController(ReportingService service, RoleChecker roleChecker,
                                DocumentGenerationService documentGenerationService) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.documentGenerationService = documentGenerationService;
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

    // ======================================================================
    // step2-backend — Reports Hub v2.4.0 : endpoints PDF dédiés pour les
    // balances âgées clients et fournisseurs. Un seul endpoint PDF avec
    // ?type=receivables|payables qui dispatch vers le bon service métier.
    // ======================================================================

    @Operation(summary = "Générer la balance âgée en PDF (Reports Hub v2.4.0)",
        description = "Rendu PDF de la balance âgée via :document-generation. " +
                      "<code>?type=receivables</code> (clients — template AGED_BALANCE_RECEIVABLES_REPORT) " +
                      "ou <code>?type=payables</code> (fournisseurs — template AGED_BALANCE_PAYABLES_REPORT). " +
                      "Délègue au même service métier que GET /aged-balance ou GET /aged-balance-suppliers.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "PDF binaire (balance âgée)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Type invalide (doit être 'receivables' ou 'payables')",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/aged-balance/pdf")
    public ResponseEntity<byte[]> getAgedBalancePdf(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Parameter(description = "Type de balance âgée : 'receivables' (clients) ou 'payables' (fournisseurs)",
            required = true, example = "receivables")
        @RequestParam String type) {
        roleChecker.ensureRole(companyId, "VIEWER");
        String normalized = type == null ? "" : type.trim().toLowerCase();
        DocumentType docType;
        AgedBalance balance;
        String label;
        switch (normalized) {
            case "receivables", "receivable", "clients", "client" -> {
                docType = DocumentType.AGED_BALANCE_RECEIVABLES_REPORT;
                balance = service.getAgedBalance(companyId);
                label = "clients";
            }
            case "payables", "payable", "suppliers", "supplier", "fournisseurs", "fournisseur" -> {
                docType = DocumentType.AGED_BALANCE_PAYABLES_REPORT;
                balance = service.getSupplierAgedBalance(companyId);
                label = "fournisseurs";
            }
            default -> {
                return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                    .header("X-Error-Reason", "INVALID_TYPE")
                    .body(null);
            }
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("companyName", "");
        variables.put("asOf", LocalDate.now().toString());
        variables.put("generationDate", LocalDate.now().toString());
        variables.put("current", balance.current() != null ? balance.current().toString() : "0");
        variables.put("d0_30", balance.d0_30() != null ? balance.d0_30().toString() : "0");
        variables.put("d31_60", balance.d31_60() != null ? balance.d31_60().toString() : "0");
        variables.put("d61_90", balance.d61_90() != null ? balance.d61_90().toString() : "0");
        variables.put("d90_plus", balance.d90_plus() != null ? balance.d90_plus().toString() : "0");
        variables.put("totalBalanceDue", balance.totalBalanceDue() != null ? balance.totalBalanceDue().toString() : "0");
        variables.put("invoiceCount", balance.invoiceCount());

        UUID resourceId = UUID.randomUUID();
        documentGenerationService.generateDocument(companyId, docType, resourceId, variables);
        byte[] pdf = documentGenerationService.getDocumentContent(companyId, resourceId);
        String filename = "balance-agee-" + label + "-" + companyId + ".pdf";
        LOG.info("[PDF] Balance âgée {} générée pour companyId={} ({} factures, {} octets)",
            label, companyId, balance.invoiceCount(), pdf.length);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }
}

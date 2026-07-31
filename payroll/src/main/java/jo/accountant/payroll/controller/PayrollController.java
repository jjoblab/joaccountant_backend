package jo.accountant.payroll.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.documentgeneration.entity.DocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.documentgeneration.util.PdfEndpointHelper;
import jo.accountant.payroll.dto.CnssReturnResponse;
import jo.accountant.payroll.dto.CreatePayrollRunRequest;
import jo.accountant.payroll.dto.PayrollRunResponse;
import jo.accountant.payroll.dto.PayslipResponse;
import jo.accountant.payroll.service.PayrollService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de paie (restructuration 2026-07-24 — module :payroll).
 *
 * <p>Le module est <strong>toujours-actif</strong> (always-on — voir
 * `BusinessTypeModuleService.alwaysOnModules`). Pas de `ModuleAccessGuard` requise.
 */
@RestController
@RequestMapping({
    "/api/v1/companies/{companyId}/payroll-runs",
    "/api/v1/companies/{companyId}/payroll"
})
@Tag(name = "Payroll", description = "Paie consolidée, calcul brut→net, bulletin PDF (restructuration 2026-07-24)")
public class PayrollController {

    private static final Logger LOG = LoggerFactory.getLogger(PayrollController.class);

    private final PayrollService service;
    private final RoleChecker roleChecker;
    private final DocumentGenerationService documentGenerationService;

    public PayrollController(PayrollService service, RoleChecker roleChecker,
                              DocumentGenerationService documentGenerationService) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.documentGenerationService = documentGenerationService;
    }

    @Operation(summary = "Créer une campagne de paie pour une période (mois/année)",
        description = "Crée une campagne au statut DRAFT. Les bulletins seront générés au calcul via POST /{id}/calculate.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "Campagne Mars 2026 créée", value = """
                    {
                      "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodMonth": 3,
                      "periodYear": 2026,
                      "status": "DRAFT",
                      "totalGross": 0,
                      "totalNet": 0,
                      "totalEmployerContributions": 0,
                      "journalEntryId": null,
                      "payslipCount": 0,
                      "createdAt": "2026-04-01T08:00:00Z",
                      "updatedAt": "2026-04-01T08:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Campagne déjà existante pour ce mois/année",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PayrollRunResponse> create(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreatePayrollRunRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(companyId, req));
    }

    @Operation(summary = "Lister les campagnes de paie",
        description = "Retourne les campagnes triées par <code>periodYear</code> DESC puis " +
                      "<code>periodMonth</code> DESC. <strong>Par défaut</strong> (restructuration " +
                      "2026-07-25 suite 4), seules les <strong>12 dernières campagnes</strong> " +
                      "sont retournées — la paie n'est pas rattachée à un exercice fiscal (les " +
                      "campagnes sont identifiées par année+mois). Le paramètre optionnel " +
                      "<code>?limit=</code> permet de demander plus (ou moins) de campagnes. " +
                      "Une valeur <code>&lt;= 0</code> retombe sur le défaut 12.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "2 campagnes (1 CALCULATED + 1 DRAFT)", value = """
                    [
                      {
                        "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "periodMonth": 3,
                        "periodYear": 2026,
                        "status": "CALCULATED",
                        "totalGross": 100000.00,
                        "totalNet": 75000.00,
                        "totalEmployerContributions": 14000.00,
                        "journalEntryId": null,
                        "payslipCount": 2,
                        "createdAt": "2026-04-01T08:00:00Z",
                        "updatedAt": "2026-04-02T09:30:00Z"
                      },
                      {
                        "id": "0192c0f5-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "periodMonth": 2,
                        "periodYear": 2026,
                        "status": "DRAFT",
                        "totalGross": 0,
                        "totalNet": 0,
                        "totalEmployerContributions": 0,
                        "journalEntryId": null,
                        "payslipCount": 0,
                        "createdAt": "2026-03-01T08:00:00Z",
                        "updatedAt": "2026-03-01T08:00:00Z"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping
    public List<PayrollRunResponse> list(@PathVariable UUID companyId,
                                          @CurrentUser UUID userId,
                                          @Parameter(description = "Nombre max de campagnes retournées (défaut 12)", example = "12")
                                          @RequestParam(required = false) Integer limit) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listRuns(companyId, limit);
    }

    @Operation(summary = "Détail d'une campagne",
        description = "Retourne une campagne avec son statut, ses totaux (brut/net/charges patronales) et son écriture comptable si approuvée.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "Campagne Mars 2026 CALCULATED", value = """
                    {
                      "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodMonth": 3,
                      "periodYear": 2026,
                      "status": "CALCULATED",
                      "totalGross": 100000.00,
                      "totalNet": 75000.00,
                      "totalEmployerContributions": 14000.00,
                      "journalEntryId": null,
                      "payslipCount": 2,
                      "createdAt": "2026-04-01T08:00:00Z",
                      "updatedAt": "2026-04-02T09:30:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Campagne introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public PayrollRunResponse get(@PathVariable UUID companyId,
                                   @PathVariable UUID id,
                                   @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getRun(companyId, id);
    }

    @Operation(summary = "Calculer la campagne (DRAFT → CALCULATED)",
        description = "Génère un Payslip par employé ACTIVE, calcule brut→net via les " +
                      "WithholdingRule applicables aux EMPLOYEE. Paramètre optionnel " +
                      "`employerContributionRate` (taux de charges patronales, ex. 14 = 14%).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "Campagne Mars 2026 calculée", value = """
                    {
                      "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodMonth": 3,
                      "periodYear": 2026,
                      "status": "CALCULATED",
                      "totalGross": 100000.00,
                      "totalNet": 75000.00,
                      "totalEmployerContributions": 14000.00,
                      "journalEntryId": null,
                      "payslipCount": 2,
                      "createdAt": "2026-04-01T08:00:00Z",
                      "updatedAt": "2026-04-02T09:30:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Campagne non DRAFT",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/{id}/calculate")
    public PayrollRunResponse calculate(@PathVariable UUID companyId,
                                         @PathVariable UUID id,
                                         @CurrentUser UUID userId,
                                         @Parameter(description = "Taux de charges patronales (ex. 14 = 14%)", example = "14")
                                         @RequestParam(value = "employerContributionRate",
                                                       required = false) BigDecimal rate) {
        roleChecker.ensureRole(companyId, "ADMIN");
        if (rate != null) {
            return service.calculate(companyId, id, rate);
        }
        return service.calculate(companyId, id);
    }

    @Operation(summary = "Approuver la campagne (CALCULATED → APPROVED)",
        description = "Génère l'écriture comptable consolidée (Débit Charges de personnel " +
                      "/ Crédit Salaires à payer / Crédit Organismes sociaux / Crédit État).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "Campagne approuvée + écriture générée", value = """
                    {
                      "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodMonth": 3,
                      "periodYear": 2026,
                      "status": "APPROVED",
                      "totalGross": 100000.00,
                      "totalNet": 75000.00,
                      "totalEmployerContributions": 14000.00,
                      "journalEntryId": "0192c0f6-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "payslipCount": 2
                    }
                    """))),
        @ApiResponse(responseCode = "409", description = "Campagne non CALCULATED",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/{id}/approve")
    public PayrollRunResponse approve(@PathVariable UUID companyId,
                                       @PathVariable UUID id,
                                       @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return service.approve(companyId, id);
    }

    @Operation(summary = "Marquer la campagne comme payée (APPROVED → PAID)",
        description = "Marquage manuel au MVP — pas de génération de fichier de virement.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "Campagne payée", value = """
                    {
                      "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodMonth": 3,
                      "periodYear": 2026,
                      "status": "PAID",
                      "totalGross": 100000.00,
                      "totalNet": 75000.00,
                      "payslipCount": 2
                    }
                    """))),
        @ApiResponse(responseCode = "409", description = "Campagne non APPROVED",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/{id}/pay")
    public PayrollRunResponse pay(@PathVariable UUID companyId,
                                   @PathVariable UUID id,
                                   @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return service.pay(companyId, id);
    }

    @Operation(summary = "Clôturer la campagne (PAID → CLOSED)",
        description = "Verrouille la campagne — plus aucune modification possible des bulletins.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayrollRunResponse.class),
                examples = @ExampleObject(name = "Campagne clôturée", value = """
                    {
                      "id": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodMonth": 3,
                      "periodYear": 2026,
                      "status": "CLOSED",
                      "totalGross": 100000.00,
                      "totalNet": 75000.00,
                      "payslipCount": 2
                    }
                    """))),
        @ApiResponse(responseCode = "409", description = "Campagne non PAID",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/{id}/close")
    public PayrollRunResponse close(@PathVariable UUID companyId,
                                     @PathVariable UUID id,
                                     @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return service.close(companyId, id);
    }

    @Operation(summary = "Lister les bulletins de paie d'une campagne",
        description = "Retourne les bulletins générés lors du calcul, avec détail des cotisations (DeductionLine code/label/rate/amount). " +
                      "Les codes de cotisation suivent le formalisme C. trav. R3243-1 (URSSAF_RETRAITE_TA, CSG_DEDUCTIBLE, etc.).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PayslipResponse.class),
                examples = @ExampleObject(name = "2 bulletins avec détail des cotisations", value = """
                    [
                      {
                        "id": "0192c0f7-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "runId": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "employeeId": "0192a8d7-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "employeeName": "Jean Dupont",
                        "employeeNumber": "EMP-0001",
                        "grossSalary": 4500.00,
                        "deductions": [
                          {"code": "URSSAF_RETRAITE_TA", "label": "Retraite Tranche A (capped)", "rate": 0.0690, "amount": 310.50},
                          {"code": "CSG_DEDUCTIBLE", "label": "CSG déductible (abattue 98.25%)", "rate": 0.0240, "amount": 108.00},
                          {"code": "MEDICAL", "label": "Assurance maladie", "rate": 0.0075, "amount": 33.75}
                        ],
                        "employerContributions": [
                          {"code": "URSSAF_RETRAITE_TA", "label": "Retraite Tranche A (patronal)", "rate": 0.0855, "amount": 384.75}
                        ],
                        "netPay": 4047.75,
                        "payslipNumber": "BS-2026-03-0001",
                        "createdAt": "2026-04-02T09:30:00Z",
                        "updatedAt": "2026-04-02T09:30:00Z"
                      },
                      {
                        "id": "0192c0f7-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "runId": "0192c0f5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "employeeId": "0192a8d7-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "employeeName": "Marie Lefèvre",
                        "employeeNumber": "EMP-0002",
                        "grossSalary": 5500.00,
                        "deductions": [
                          {"code": "URSSAF_RETRAITE_TA", "label": "Retraite Tranche A (capped)", "rate": 0.0690, "amount": 379.50},
                          {"code": "CSG_DEDUCTIBLE", "label": "CSG déductible", "rate": 0.0240, "amount": 132.00}
                        ],
                        "employerContributions": [],
                        "netPay": 4988.50,
                        "payslipNumber": "BS-2026-03-0002",
                        "createdAt": "2026-04-02T09:30:00Z",
                        "updatedAt": "2026-04-02T09:30:00Z"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "404", description = "Campagne introuvable",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/{id}/payslips")
    public List<PayslipResponse> listPayslips(@PathVariable UUID companyId,
                                                @PathVariable UUID id,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listPayslips(companyId, id);
    }

    @Operation(summary = "Générer le PDF d'un bulletin de paie",
        description = "Généré via document-generation (template PAYSLIP). Sert le PDF directement.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "PDF binaire (bulletin de paie)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "404", description = "Bulletin introuvable",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/not-found",
                      "title": "Bulletin introuvable",
                      "status": 404,
                      "detail": "Aucun bulletin avec l'id 0192c0f7-1c2d-3e4f-5a6b-7c8d9e0fabcd pour cette entreprise.",
                      "properties": {"code": "PAYSLIP_NOT_FOUND"}
                    }
                    """)))
    })
    @GetMapping("/payslips/{payslipId}/pdf")
    public ResponseEntity<byte[]> getPayslipPdf(@PathVariable UUID companyId,
                                                  @PathVariable UUID payslipId,
                                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] pdf = service.getPayslipPdf(companyId, payslipId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"payslip-" + payslipId + ".pdf\"")
            .body(pdf);
    }

    // ======================================================================
    // V75 — v7-4 : 13e mois (Code du Travail Haïti art. 153)
    // ======================================================================

    @Operation(summary = "V75 — v7-4 : Lancer le calcul du 13e mois pour décembre",
        description = "Crée une campagne de type THIRTEENTH_MONTH pour décembre de l'année " +
                      "donnée, calcule le 13e mois brut + net (après ITS) pour chaque employé " +
                      "éligible (thirteenthMonthEligible=true ET hireDate ≤ 31/12/year), génère " +
                      "un bulletin par employé, et laisse la campagne en statut CALCULATED. " +
                      "L'utilisateur doit approuver via POST /{id}/approve pour déclencher le postage.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = PayrollRunResponse.class))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)"),
        @ApiResponse(responseCode = "409", description = "Une campagne 13e mois existe déjà pour cette année")
    })
    @PostMapping("/thirteenth-month")
    public ResponseEntity<PayrollRunResponse> launchThirteenthMonthRun(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @RequestParam int year) {
        roleChecker.ensureRole(companyId, "ADMIN");
        PayrollRunResponse response = service.launchThirteenthMonthRun(companyId, year, userId);
        return ResponseEntity.ok(response);
    }

    // ======================================================================
    // step2-backend — Reports Hub v2.4.0 : endpoint PDF de synthèse de paie
    // agrégée sur une période. L'URL /payroll/summary/pdf est rendue
    // accessible via le second préfixe de classe /api/v1/companies/{cid}/payroll
    // (déclaré ci-dessus). L'endpoint /payroll-runs/summary/pdf reste également
    // accessible pour cohérence avec les autres URLs du controller.
    // ======================================================================

    @Operation(summary = "Générer la synthèse de paie en PDF (Reports Hub v2.4.0)",
        description = "Rendu PDF de la synthèse de paie agrégée sur une période via :document-generation " +
                      "(template PAYROLL_SUMMARY_REPORT). Agrège toutes les campagnes dont le mois de période " +
                      "tombe dans [from, to] : somme des masses brutes/nettes/charges patronales + détail par campagne.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "PDF binaire (synthèse de paie)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/summary/pdf")
    public ResponseEntity<byte[]> getPayrollSummaryPdf(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Parameter(description = "Date de début (incluse) — si null, début d'historique", example = "2026-01-01")
        @RequestParam(required = false) LocalDate from,
        @Parameter(description = "Date de fin (incluse) — si null, fin d'historique", example = "2026-12-31")
        @RequestParam(required = false) LocalDate to) {
        roleChecker.ensureRole(companyId, "VIEWER");

        // Récupérer les campagnes — on demande un cap large (120 = 10 ans) pour couvrir tout l'historique.
        List<PayrollRunResponse> allRuns = service.listRuns(companyId, 120);

        // Filtrer par période (comparaison sur le premier jour du mois de la campagne).
        LocalDate fromMonth = from != null ? from.withDayOfMonth(1) : null;
        LocalDate toMonth = to != null ? to.withDayOfMonth(1) : null;
        List<PayrollRunResponse> filtered = new java.util.ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalEmployerContributions = BigDecimal.ZERO;
        int payslipCount = 0;
        for (PayrollRunResponse run : allRuns) {
            LocalDate periodStart = LocalDate.of(run.periodYear(), run.periodMonth(), 1);
            if (fromMonth != null && periodStart.isBefore(fromMonth)) continue;
            if (toMonth != null && periodStart.isAfter(toMonth)) continue;
            filtered.add(run);
            if (run.totalGross() != null) totalGross = totalGross.add(run.totalGross());
            if (run.totalNet() != null) totalNet = totalNet.add(run.totalNet());
            if (run.totalEmployerContributions() != null)
                totalEmployerContributions = totalEmployerContributions.add(run.totalEmployerContributions());
            payslipCount += run.payslipCount();
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("companyName", "");
        variables.put("from", from != null ? from.toString() : "debut");
        variables.put("to", to != null ? to.toString() : "fin");
        variables.put("generationDate", LocalDate.now().toString());
        variables.put("runCount", filtered.size());
        variables.put("payslipCount", payslipCount);
        variables.put("totalGross", totalGross.toString());
        variables.put("totalNet", totalNet.toString());
        variables.put("totalEmployerContributions", totalEmployerContributions.toString());
        variables.put("runs", filtered);

        String periodLabel = (from != null ? from.toString() : "debut") + "_" + (to != null ? to.toString() : "fin");
        String filename = "synthese-paie-" + companyId + "-" + periodLabel + ".pdf";
        ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
            documentGenerationService, companyId, DocumentType.PAYROLL_SUMMARY_REPORT, variables, filename);
        LOG.info("[PDF] Synthèse paie générée pour companyId={} période={} ({} campagnes, {} bulletins, {} octets)",
            companyId, periodLabel, filtered.size(), payslipCount, response.getBody().length);
        return response;
    }

    // ======================================================================
    // step7-backend — Reports Hub v2.5.0 : endpoint JSON + PDF pour le
    // rapport CNSS_RETURN (bordereau CNSS/OFATMA/AST agrégé par employé
    // sur une période). L'URL /payroll/cnss-return (GET) retourne le JSON ;
    // /payroll/cnss-return/pdf génère un PDF binaire via DocumentGenerationService
    // (template CNSS_RETURN_REPORT seedé par V89).
    // ======================================================================

    @Operation(summary = "Bordereau CNSS/OFATMA/AST agrégé par employé (Reports Hub v2.5.0)",
        description = "Agrège sur la période [from, to] toutes les cotisations sociales des bulletins " +
                      "de paie dont le code commence par CNSS_HT / OFATMA_HT / AST_HT (V57 ContributionRule). " +
                      "Retourne une ligne par employé avec totalGross / assiette imposable / cotisations " +
                      "salariales + patronales, plus les totaux globaux.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CnssReturnResponse.class))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/cnss-return")
    public CnssReturnResponse getCnssReturn(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Parameter(description = "Date de début (incluse) — si null, début d'historique", example = "2026-01-01")
        @RequestParam(required = false) LocalDate from,
        @Parameter(description = "Date de fin (incluse) — si null, fin d'historique", example = "2026-12-31")
        @RequestParam(required = false) LocalDate to) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getCnssReturn(companyId, from, to);
    }

    @Operation(summary = "Générer le bordereau CNSS/OFATMA/AST en PDF (Reports Hub v2.5.0)",
        description = "Rendu PDF du bordereau CNSS_RETURN via :document-generation (template CNSS_RETURN_REPORT). " +
                      "Sert un PDF binaire en attachment. Délègue au même service métier que GET /cnss-return.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "PDF binaire (bordereau CNSS)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/cnss-return/pdf")
    public ResponseEntity<byte[]> getCnssReturnPdf(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Parameter(description = "Date de début (incluse) — si null, début d'historique", example = "2026-01-01")
        @RequestParam(required = false) LocalDate from,
        @Parameter(description = "Date de fin (incluse) — si null, fin d'historique", example = "2026-12-31")
        @RequestParam(required = false) LocalDate to) {
        roleChecker.ensureRole(companyId, "VIEWER");

        CnssReturnResponse data = service.getCnssReturn(companyId, from, to);

        Map<String, Object> variables = new HashMap<>();
        variables.put("companyName", data.companyName() != null ? data.companyName() : "");
        variables.put("period", data.period() != null ? data.period() : "");
        variables.put("fiscalYearLabel", data.fiscalYearLabel() != null ? data.fiscalYearLabel() : "");
        variables.put("currency", data.currency() != null ? data.currency() : "");
        variables.put("from", from != null ? from.toString() : "debut");
        variables.put("to", to != null ? to.toString() : "fin");
        variables.put("generationDate", LocalDate.now().toString());
        variables.put("totalGross", data.totalGross() != null ? data.totalGross().toString() : "0");
        variables.put("totalTaxableBase", data.totalTaxableBase() != null ? data.totalTaxableBase().toString() : "0");
        variables.put("totalEmployeeContribution",
            data.totalEmployeeContribution() != null ? data.totalEmployeeContribution().toString() : "0");
        variables.put("totalEmployerContribution",
            data.totalEmployerContribution() != null ? data.totalEmployerContribution().toString() : "0");
        variables.put("lines", data.lines() != null ? data.lines() : List.of());

        String filename = "bordereau-cnss-" + companyId + "-" + data.period() + ".pdf";
        ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
            documentGenerationService, companyId, DocumentType.CNSS_RETURN_REPORT, variables, filename);
        LOG.info("[PDF] Bordereau CNSS généré pour companyId={} période={} ({} employés, {} octets)",
            companyId, data.period(), data.lines().size(), response.getBody().length);
        return response;
    }
}

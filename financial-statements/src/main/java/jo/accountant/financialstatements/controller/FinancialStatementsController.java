package jo.accountant.financialstatements.controller;

import io.swagger.v3.oas.annotations.Operation;
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
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.documentgeneration.util.PdfEndpointHelper;
import jo.accountant.financialstatements.dto.BalanceSheet;
import jo.accountant.financialstatements.dto.CashFlowStatement;
import jo.accountant.financialstatements.dto.CreateSnapshotRequest;
import jo.accountant.financialstatements.dto.IncomeStatement;
import jo.accountant.financialstatements.dto.PresentationCurrencyRequest;
import jo.accountant.financialstatements.dto.SnapshotResponse;
import jo.accountant.financialstatements.dto.StatementOfChangesInEquity;
import jo.accountant.financialstatements.service.FinancialStatementsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints des états financiers (§13 Phase 6).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/financial-statements/...}.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/financial-statements")
@Tag(name = "FinancialStatements", description = "Bilan, compte de résultat, snapshots figés (§13 Phase 6)")
public class FinancialStatementsController {

 private static final Logger LOG = LoggerFactory.getLogger(FinancialStatementsController.class);

 private final FinancialStatementsService service;
 private final RoleChecker roleChecker;
 private final DocumentGenerationService documentGenerationService;

 public FinancialStatementsController(FinancialStatementsService service,
 RoleChecker roleChecker,
 DocumentGenerationService documentGenerationService) {
 this.service = service;
 this.roleChecker = roleChecker;
 this.documentGenerationService = documentGenerationService;
 }

 @Operation(summary = "Générer le bilan à une date donnée",
 description = "Calcule Actif = Passif + Capitaux propres à partir des écritures POSTED. " +
 "Utilise uniquement reportingClass/reportingSubcategory des comptes (§4) — " +
 "jamais de logique par référentiel. Le flag 'balanced' indique si " +
 "totalAssets == totalLiabilities + totalEquity (peut être false si " +
 "l'exercice n'est pas encore clôturé).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(schema = @Schema(implementation = BalanceSheet.class),
 examples = @ExampleObject(value = """
 {"companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","asOf":"2026-12-31","assets":[{"reportingClass":"ACTIF","reportingSubcategory":"COURANT","lines":[{"accountId":"...","accountCode":"411000","accountLabel":"Clients","amount":11500.00}],"subtotal":11500.00}],"liabilities":[],"equity":[],"totalAssets":11500.00,"totalLiabilities":0,"totalEquity":0,"balanced":false}
 """))),
 @ApiResponse(responseCode = "422", description = "asOf manquant",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/balance-sheet")
 public BalanceSheet getBalanceSheet(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate asOf,
 @RequestParam(required = false) String presentationCurrency,
 @RequestParam(required = false) BigDecimal closingRate) {
 roleChecker.ensureRole(companyId, "VIEWER");
 PresentationCurrencyRequest pcr = (presentationCurrency != null)
 ? new PresentationCurrencyRequest(presentationCurrency, asOf, closingRate, null)
 : null;
 return service.getBalanceSheet(companyId, asOf, pcr);
 }

 @Operation(summary = "Générer le compte de résultat sur une plage de dates",
 description = "Calcule Produits − Charges = Résultat net à partir des écritures POSTED. " +
 "Utilise uniquement reportingClass/reportingSubcategory des comptes (§4).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(schema = @Schema(implementation = IncomeStatement.class),
 examples = @ExampleObject(value = """
 {"companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","from":"2026-01-01","to":"2026-12-31","products":[{"reportingClass":"PRODUITS","reportingSubcategory":"COURANT","lines":[{"accountCode":"701000","accountLabel":"Ventes","amount":10000.00}],"subtotal":10000.00}],"charges":[],"totalProducts":10000.00,"totalCharges":0,"netResult":10000.00}
 """))),
 @ApiResponse(responseCode = "422", description = "Dates invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/income-statement")
 public IncomeStatement getIncomeStatement(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
 @RequestParam(required = false) String presentationCurrency,
 @RequestParam(required = false) BigDecimal averageRate) {
 roleChecker.ensureRole(companyId, "VIEWER");
 PresentationCurrencyRequest pcr = (presentationCurrency != null)
 ? new PresentationCurrencyRequest(presentationCurrency, null, null, averageRate)
 : null;
 return service.getIncomeStatement(companyId, from, to, pcr);
 }

 @Operation(summary = "Générer le tableau de flux de trésorerie (IAS 7 / SYSCOHADA TAFIRE)",
 description = "Audit v4.7 §3.1 Méthode indirecte : résultat net ± amortissements ± variations BFR ± cessions + investissements + financements. " +
 "Limitation v4.7.1 : distinction investissement/financement basée sur codes de compte (à affiner en v4.8).")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "Tableau de flux de trésorerie (méthode indirecte IAS 7 / SYSCOHADA TAFIRE)",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = jo.accountant.financialstatements.dto.CashFlowStatement.class),
 examples = @ExampleObject(name = "Flux annuel 2026 équilibré", value = """
 {
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "from": "2026-01-01",
 "to": "2026-12-31",
 "netIncome": 250000.00,
 "operating": {
 "netIncome": 250000.00,
 "depreciationAmortization": 45000.00,
 "accountsReceivableVariation": -12000.00,
 "inventoryVariation": -5000.00,
 "accountsPayableVariation": 8000.00,
 "otherWorkingCapitalVariation": 0,
 "total": 286000.00
 },
 "investing": {
 "fixedAssetsAcquisitions": -150000.00,
 "fixedAssetsDisposals": 0,
 "otherInvestingFlows": 0,
 "total": -150000.00
 },
 "financing": {
 "capitalVariation": 0,
 "loansVariation": 100000.00,
 "dividendsPaid": -50000.00,
 "otherFinancingFlows": 0,
 "total": 50000.00
 },
 "netCashFlow": 186000.00,
 "openingCash": 64000.00,
 "closingCash": 250000.00,
 "balanced": true
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Dates invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/cash-flow-statement")
 public jo.accountant.financialstatements.dto.CashFlowStatement getCashFlowStatement(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
 @RequestParam(required = false) String presentationCurrency,
 @RequestParam(required = false) BigDecimal averageRate) {
 roleChecker.ensureRole(companyId, "VIEWER");
 PresentationCurrencyRequest pcr = (presentationCurrency != null)
 ? new PresentationCurrencyRequest(presentationCurrency, null, null, averageRate)
 : null;
 return service.getCashFlowStatement(companyId, from, to, pcr);
 }

 @Operation(summary = "Créer un snapshot figé",
 description = "Figé le contenu d'un état financier pour une période. Une fois figé, " +
 "le snapshot est immuable. 409 si un snapshot existe déjà pour le même " +
 "(type, periodId).")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 description = "Snapshot figé créé (immuable)",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = SnapshotResponse.class),
 examples = @ExampleObject(name = "Snapshot bilan 2026", value = """
 {
 "id": "0192c0f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "type": "BALANCE_SHEET",
 "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "generatedAt": "2026-12-31T23:59:00Z",
 "frozen": true,
 "asOfDate": "2026-12-31",
 "fromDate": null,
 "toDate": null,
 "contentJson": "{\"totalAssets\":1250000,\"totalLiabilities\":750000,\"totalEquity\":500000,\"balanced\":true}"
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Période introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "Snapshot déjà existant — code `SNAPSHOT_ALREADY_EXISTS`",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/snapshot-already-exists",
 "title": "Snapshot déjà existant",
 "status": 409,
 "detail": "Un snapshot BALANCE_SHEET existe déjà pour la période 0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd.",
 "properties": {"code": "SNAPSHOT_ALREADY_EXISTS"}
 }
 """)))
 })
 @PostMapping(value = "/snapshots", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<SnapshotResponse> createSnapshot(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateSnapshotRequest req) {
 roleChecker.ensureRole(companyId, "ACCOUNTANT");
 SnapshotResponse snapshot = service.createSnapshot(companyId, req);
 return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
 }

 @Operation(summary = "Créer automatiquement les snapshots de clôture (bilan + CR) pour une période",
 description = "Audit v4.7 §3.1 À appeler après POST /fiscal-years/{id}/close. " +
 "Crée (idempotent) les snapshots figés BALANCE_SHEET + INCOME_STATEMENT " +
 "pour la période de clôture. Sans ces snapshots, le plan comptable peut " +
 "être modifié après clôture et les états financiers générés ultérieurement " +
 "peuvent différer de ceux valables à la clôture — défaut de piste d'audit.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "Snapshots de clôture (idempotent — retourne les snapshots existants s'ils existent déjà)",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = SnapshotResponse.class),
 examples = @ExampleObject(name = "2 snapshots de clôture (bilan + CR)", value = """
 [
 {
 "id": "0192c0f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "type": "BALANCE_SHEET",
 "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "generatedAt": "2026-12-31T23:59:00Z",
 "frozen": true,
 "asOfDate": "2026-12-31",
 "fromDate": null,
 "toDate": null,
 "contentJson": "{\"totalAssets\":1250000,\"totalLiabilities\":750000,\"totalEquity\":500000,\"balanced\":true}"
 },
 {
 "id": "0192c0f0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "type": "INCOME_STATEMENT",
 "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "generatedAt": "2026-12-31T23:59:00Z",
 "frozen": true,
 "asOfDate": null,
 "fromDate": "2026-01-01",
 "toDate": "2026-12-31",
 "contentJson": "{\"totalProducts\":850000,\"totalCharges\":600000,\"netResult\":250000}"
 }
 ]
 """))),
 @ApiResponse(responseCode = "404", description = "Période introuvable",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping("/snapshots/closing/periods/{periodId}")
 public List<SnapshotResponse> createClosingSnapshots(
 @PathVariable UUID companyId,
 @PathVariable UUID periodId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "ACCOUNTANT");
 return service.createClosingSnapshots(companyId, periodId);
 }

 @Operation(summary = "Lister les snapshots figés",
 description = "Retourne tous les snapshots figés de l'entreprise, triés par date de génération décroissante.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = SnapshotResponse.class),
 examples = @ExampleObject(name = "Liste de 2 snapshots", value = """
 [
 {
 "id": "0192c0f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "type": "BALANCE_SHEET",
 "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "generatedAt": "2026-12-31T23:59:00Z",
 "frozen": true,
 "asOfDate": "2026-12-31",
 "fromDate": null,
 "toDate": null
 },
 {
 "id": "0192c0f0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "type": "INCOME_STATEMENT",
 "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "generatedAt": "2026-12-31T23:59:00Z",
 "frozen": true,
 "asOfDate": null,
 "fromDate": "2026-01-01",
 "toDate": "2026-12-31"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/snapshots")
 public List<SnapshotResponse> listSnapshots(@PathVariable UUID companyId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 return service.listSnapshots(companyId);
 }

 @Operation(summary = "Récupérer un snapshot figé par ID")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = SnapshotResponse.class),
 examples = @ExampleObject(name = "Snapshot bilan récupéré", value = """
 {
 "id": "0192c0f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "type": "BALANCE_SHEET",
 "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "generatedAt": "2026-12-31T23:59:00Z",
 "frozen": true,
 "asOfDate": "2026-12-31",
 "fromDate": null,
 "toDate": null,
 "contentJson": "{\"totalAssets\":1250000,\"totalLiabilities\":750000,\"totalEquity\":500000,\"balanced\":true}"
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Snapshot introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/not-found",
 "title": "Snapshot introuvable",
 "status": 404,
 "detail": "Aucun snapshot avec l'id 0192c0f0-1c2d-3e4f-5a6b-7c8d9e0fabcd pour cette entreprise.",
 "properties": {"code": "SNAPSHOT_NOT_FOUND"}
 }
 """)))
 })
 @GetMapping("/snapshots/{snapshotId}")
 public SnapshotResponse getSnapshot(@PathVariable UUID companyId,
 @PathVariable UUID snapshotId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 return service.getSnapshot(companyId, snapshotId);
 }

 // ======================================================================
 // V84 — v7-2 : Statement of Changes in Equity (IAS 1.106)
 // ======================================================================

 @Operation(summary = "V84 — v7-2 : Statement of Changes in Equity (IAS 1.106)",
 description = "Tableau de variation des capitaux propres entre deux dates. " +
 "Conforme IAS 1.106-110. Obligatoire en IFRS_FULL. " +
 "Conversion optionnelle vers une devise de présentation (taux de clôture, IAS 21).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(schema = @Schema(implementation = StatementOfChangesInEquity.class))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum)"),
 @ApiResponse(responseCode = "422", description = "Dates invalides ou taux de clôture manquant")
 })
 @GetMapping("/statement-of-changes-in-equity")
 public ResponseEntity<StatementOfChangesInEquity> getStatementOfChangesInEquity(
 @PathVariable UUID companyId,
 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
 @RequestParam(required = false) String presentationCurrency,
 @RequestParam(required = false) BigDecimal closingRate,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 PresentationCurrencyRequest pcr = presentationCurrency != null
 ? new PresentationCurrencyRequest(presentationCurrency, to, closingRate, null)
 : null;
 return ResponseEntity.ok(service.getStatementOfChangesInEquity(companyId, from, to, pcr));
 }

 // ======================================================================
 // step2-backend — Reports Hub v2.4.0 : endpoints PDF dédiés
 // (4 endpoints — bilan, compte de résultat, flux de trésorerie, variation
 // des capitaux propres). Chaque endpoint :
 // 1. appelle le service métier existant pour obtenir le DTO,
 // 2. construit une map de variables Thymeleaf (DTO + dates + companyName),
 // 3. délègue à DocumentGenerationService.generateDocument(...) pour le rendu HTML→PDF,
 // 4. retourne ResponseEntity<byte[]> avec Content-Type application/pdf et
 // Content-Disposition: attachment; filename="<report>-<companyId>-<period>.pdf".
 //
 // Règle d'immuabilité contournée : on passe un UUID aléatoire comme resourceId
 // pour forcer la régénération à chaque appel (les données sous-jacentes
 // peuvent changer tant que l'exercice n'est pas clôturé — voir commentaire
 // exportBalanceSheetPdf dans ReportingService).
 // ======================================================================

 @Operation(summary = "Générer le bilan en PDF (Reports Hub v2.4.0)",
 description = "Rendu PDF du bilan via :document-generation (template BALANCE_SHEET_REPORT). " +
 "Sert un PDF binaire en attachment. Délègue au même service métier que GET /balance-sheet.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (bilan)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/balance-sheet/pdf")
 public ResponseEntity<byte[]> getBalanceSheetPdf(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate asOf) {
 roleChecker.ensureRole(companyId, "VIEWER");
 BalanceSheet bs = service.getBalanceSheet(companyId, asOf, null);
 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("asOf", bs.asOf() != null ? bs.asOf().toString() : (asOf != null ? asOf.toString() : LocalDate.now().toString()));
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("totalAssets", bs.totalAssets() != null ? bs.totalAssets().toString() : "0");
 variables.put("totalLiabilities", bs.totalLiabilities() != null ? bs.totalLiabilities().toString() : "0");
 variables.put("totalEquity", bs.totalEquity() != null ? bs.totalEquity().toString() : "0");
 variables.put("balanced", bs.balanced());
 variables.put("assets", bs.assets() != null ? bs.assets() : List.of());
 variables.put("liabilities", bs.liabilities() != null ? bs.liabilities() : List.of());
 variables.put("equity", bs.equity() != null ? bs.equity() : List.of());

 String period = bs.asOf() != null ? bs.asOf().toString() : "now";
 String filename = "bilan-" + companyId + "-" + period + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, GeneratedDocumentType.BALANCE_SHEET_REPORT, variables, filename);
 LOG.info("[PDF] Bilan généré pour companyId={} asOf={} ({} octets)", companyId, period, response.getBody().length);
 return response;
 }

 @Operation(summary = "Générer le compte de résultat en PDF (Reports Hub v2.4.0)",
 description = "Rendu PDF du compte de résultat via :document-generation (template INCOME_STATEMENT_REPORT). " +
 "Délègue au même service métier que GET /income-statement.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (compte de résultat)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/income-statement/pdf")
 public ResponseEntity<byte[]> getIncomeStatementPdf(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to) {
 roleChecker.ensureRole(companyId, "VIEWER");
 IncomeStatement is = service.getIncomeStatement(companyId, from, to, null);
 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("from", is.from() != null ? is.from().toString() : (from != null ? from.toString() : ""));
 variables.put("to", is.to() != null ? is.to().toString() : (to != null ? to.toString() : ""));
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("totalProducts", is.totalProducts() != null ? is.totalProducts().toString() : "0");
 variables.put("totalCharges", is.totalCharges() != null ? is.totalCharges().toString() : "0");
 variables.put("netResult", is.netResult() != null ? is.netResult().toString() : "0");
 // Aplatir les sections en une liste de lignes pour le template (qui itère sur productsLines/chargesLines)
 variables.put("productsLines", flattenSections(is.products()));
 variables.put("chargesLines", flattenSections(is.charges()));

 String period = (is.from() != null ? is.from() : from) + "_" + (is.to() != null ? is.to() : to);
 String filename = "compte-resultat-" + companyId + "-" + period + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, GeneratedDocumentType.INCOME_STATEMENT_REPORT, variables, filename);
 LOG.info("[PDF] Compte de résultat généré pour companyId={} période={}→{} ({} octets)",
 companyId, is.from(), is.to(), response.getBody().length);
 return response;
 }

 @Operation(summary = "Générer le tableau de flux de trésorerie en PDF (Reports Hub v2.4.0)",
 description = "Rendu PDF du tableau de flux (IAS 7 / SYSCOHADA TAFIRE) via :document-generation " +
 "(template CASH_FLOW_STATEMENT_REPORT). Délègue au même service que GET /cash-flow-statement.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (tableau de flux de trésorerie)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/cash-flow-statement/pdf")
 public ResponseEntity<byte[]> getCashFlowStatementPdf(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
 @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to) {
 roleChecker.ensureRole(companyId, "VIEWER");
 CashFlowStatement cf = service.getCashFlowStatement(companyId, from, to, null);
 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("from", cf.from() != null ? cf.from().toString() : (from != null ? from.toString() : ""));
 variables.put("to", cf.to() != null ? cf.to().toString() : (to != null ? to.toString() : ""));
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("netIncome", cf.netIncome() != null ? cf.netIncome().toString() : "0");
 variables.put("operating", cf.operating());
 variables.put("investing", cf.investing());
 variables.put("financing", cf.financing());
 variables.put("netCashFlow", cf.netCashFlow() != null ? cf.netCashFlow().toString() : "0");
 variables.put("openingCash", cf.openingCash() != null ? cf.openingCash().toString() : "0");
 variables.put("closingCash", cf.closingCash() != null ? cf.closingCash().toString() : "0");
 variables.put("balanced", cf.balanced());

 String period = (cf.from() != null ? cf.from() : from) + "_" + (cf.to() != null ? cf.to() : to);
 String filename = "flux-tresorerie-" + companyId + "-" + period + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, GeneratedDocumentType.CASH_FLOW_STATEMENT_REPORT, variables, filename);
 LOG.info("[PDF] Flux de trésorerie généré pour companyId={} période={}→{} ({} octets)",
 companyId, cf.from(), cf.to(), response.getBody().length);
 return response;
 }

 @Operation(summary = "Générer le tableau de variation des capitaux propres en PDF (Reports Hub v2.4.0)",
 description = "Rendu PDF du tableau IAS 1.106 via :document-generation " +
 "(template STATEMENT_OF_CHANGES_IN_EQUITY_REPORT). Délègue au même service que GET /statement-of-changes-in-equity.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (tableau de variation des capitaux propres)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/statement-of-changes-in-equity/pdf")
 public ResponseEntity<byte[]> getStatementOfChangesInEquityPdf(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
 roleChecker.ensureRole(companyId, "VIEWER");
 StatementOfChangesInEquity stmt = service.getStatementOfChangesInEquity(companyId, from, to, null);
 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("from", from.toString());
 variables.put("to", to.toString());
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("openingEquity", stmt.openingEquity() != null ? stmt.openingEquity().toString() : "0");
 variables.put("netIncome", stmt.netIncome() != null ? stmt.netIncome().toString() : "0");
 variables.put("otherComprehensiveIncome", stmt.otherComprehensiveIncome() != null ? stmt.otherComprehensiveIncome().toString() : "0");
 variables.put("capitalIssued", stmt.capitalIssued() != null ? stmt.capitalIssued().toString() : "0");
 variables.put("treasurySharesPurchased", stmt.treasurySharesPurchased() != null ? stmt.treasurySharesPurchased().toString() : "0");
 variables.put("dividendsDistributed", stmt.dividendsDistributed() != null ? stmt.dividendsDistributed().toString() : "0");
 variables.put("otherMovements", stmt.otherMovements() != null ? stmt.otherMovements().toString() : "0");
 variables.put("closingEquity", stmt.closingEquity() != null ? stmt.closingEquity().toString() : "0");
 variables.put("movements", stmt.movements() != null ? stmt.movements() : List.of());

 String filename = "variation-capitaux-propres-" + companyId + "-" + from + "_" + to + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, GeneratedDocumentType.STATEMENT_OF_CHANGES_IN_EQUITY_REPORT, variables, filename);
 LOG.info("[PDF] Variation des capitaux propres générée pour companyId={} période={}→{} ({} octets)",
 companyId, from, to, response.getBody().length);
 return response;
 }

 /**
 * Aplatit une liste de sections (Bilan ou Compte de résultat) en une liste plate de lignes.
 * Utilisé par les templates Thymeleaf qui itèrent sur une seule liste (ex. productsLines).
 */
 private static List<Map<String, Object>> flattenSections(List<? extends Object> sections) {
 if (sections == null || sections.isEmpty()) return List.of();
 List<Map<String, Object>> flat = new java.util.ArrayList<>();
 for (Object secObj : sections) {
 // Réflexion légère pour supporter BalanceSheet.Section et IncomeStatement.Section
 // (mêmes noms de champs : lines, accountCode, accountLabel, amount).
 try {
 java.lang.reflect.Method getLines = secObj.getClass().getMethod("lines");
 Object lines = getLines.invoke(secObj);
 if (lines instanceof List<?> lineList) {
 for (Object line : lineList) {
 Map<String, Object> flatLine = new HashMap<>();
 flatLine.put("accountCode", invokeGetter(line, "accountCode"));
 flatLine.put("accountLabel", invokeGetter(line, "accountLabel"));
 flatLine.put("amount", invokeGetter(line, "amount"));
 flat.add(flatLine);
 }
 }
 } catch (Exception e) {
 // ignore — la section n'a pas la structure attendue
 }
 }
 return flat;
 }

 private static Object invokeGetter(Object target, String property) {
 try {
 java.lang.reflect.Method m = target.getClass().getMethod(property);
 return m.invoke(target);
 } catch (Exception e) {
 return null;
 }
 }
}

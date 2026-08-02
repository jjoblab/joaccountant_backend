package jo.accountant.tax.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.tax.dto.CreateTaxRuleRequest;
import jo.accountant.tax.dto.CreateWithholdingRuleRequest;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.entity.TaxRule;
import jo.accountant.tax.entity.WithholdingRule;
import jo.accountant.tax.service.TaxExportService;
import jo.accountant.tax.service.TaxService;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.documentgeneration.util.PdfEndpointHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 * Endpoints fiscaux (§13.
 
 *
 *

 *

 *

 *

 *

 *

 *
 * <p>Endpoints exposés :
 * <ul>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 * </ul>

 * @author jo@Dev


*/
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tax")
@Tag(name = "Tax", description = "Règles fiscales locales, TVA, retenues à la source (§13")
public class TaxController {

 private static final Logger LOG = LoggerFactory.getLogger(TaxController.class);

 private final TaxService service;
 private final TaxExportService exportService;
 private final RoleChecker roleChecker;
 private final ModuleAccessGuard moduleAccessGuard;
 private final DocumentGenerationService documentGenerationService;

 public TaxController(TaxService service, TaxExportService exportService,
 RoleChecker roleChecker, ModuleAccessGuard moduleAccessGuard,
 DocumentGenerationService documentGenerationService) {
 this.service = service;
 this.exportService = exportService;
 this.roleChecker = roleChecker;
 this.moduleAccessGuard = moduleAccessGuard;
 this.documentGenerationService = documentGenerationService;
 }

 @Operation(summary = "Créer une règle de TVA",
 description = "Crée une règle de TVA pour l'entreprise. Le `vatMode` détermine l'exigibilité : " +
 "`DEBIT` (régime des débits, défaut) ou `ENCAISSEMENT` (art. 289 II CGI).")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = TaxRule.class),
 examples = @ExampleObject(name = "TVA 20% France créée", value = """
 {
 "id": "0192a8e1-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "TVA_FR_20",
 "label": "TVA France 20%",
 "rate": 20.00,
 "payableAccountId": "0192a8c0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "receivableAccountId": "0192a8c0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "applicableFrom": "2026-01-01",
 "applicableTo": null,
 "vatMode": "DEBIT"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "Taux négatif ou code vide",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping(value = "/rules", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<TaxRule> createTaxRule(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateTaxRuleRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return ResponseEntity.status(HttpStatus.CREATED).body(service.createTaxRule(companyId, req));
 }

 @Operation(summary = "Lister les règles de TVA",
 description = "Retourne les règles de TVA actives de l'entreprise (et les règles globales par pays).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = TaxRule.class),
 examples = @ExampleObject(name = "2 règles de TVA", value = """
 [
 {
 "id": "0192a8e1-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "TVA_FR_20",
 "label": "TVA France 20%",
 "rate": 20.00,
 "vatMode": "DEBIT",
 "applicableFrom": "2026-01-01"
 },
 {
 "id": "0192a8e1-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "TVA_FR_5_5",
 "label": "TVA France 5,5% (produits alimentaires)",
 "rate": 5.50,
 "vatMode": "DEBIT",
 "applicableFrom": "2026-01-01"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/rules")
 public List<TaxRule> listTaxRules(@PathVariable UUID companyId, @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return service.listTaxRules(companyId);
 }

 @Operation(summary = "Créer une règle de retenue à la source",
 description = "Crée une règle de retenue à la source. Par défaut `bracketType=FLAT` (taux unique). " +
 "Pour un barème progressif (PAS FR), passer `bracketType=PROGRESSIVE` + `brackets`.")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = WithholdingRule.class),
 examples = @ExampleObject(name = "Retenue 2% Haïti créée", value = """
 {
 "id": "0192a8e2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "RHS_HT_2",
 "label": "Retenue à la source 2% Haïti (prestations)",
 "rate": 2.00,
 "applicableThirdPartyTypes": ["SUPPLIER"],
 "bracketType": "FLAT",
 "bracketsJson": null
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "Taux négatif ou brackets invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping(value = "/withholding-rules", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<WithholdingRule> createWithholdingRule(
 @PathVariable UUID companyId, @CurrentUser UUID userId,
 @Valid @RequestBody CreateWithholdingRuleRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return ResponseEntity.status(HttpStatus.CREATED)
 .body(service.createWithholdingRule(companyId, req));
 }

 @Operation(summary = "Lister les règles de retenue à la source",
 description = "Retourne les règles de retenue à la source actives de l'entreprise.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = WithholdingRule.class),
 examples = @ExampleObject(name = "2 règles de retenue", value = """
 [
 {
 "id": "0192a8e2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "RHS_HT_2",
 "label": "Retenue à la source 2% Haïti (prestations)",
 "rate": 2.00,
 "applicableThirdPartyTypes": ["SUPPLIER"],
 "bracketType": "FLAT"
 },
 {
 "id": "0192a8e2-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "PAS_FR",
 "label": "Prélèvement à la source (PAS FR)",
 "rate": 0.00,
 "applicableThirdPartyTypes": ["SUPPLIER"],
 "bracketType": "PROGRESSIVE"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/withholding-rules")
 public List<WithholdingRule> listWithholdingRules(@PathVariable UUID companyId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return service.listWithholdingRules(companyId);
 }

 @Operation(summary = "Déclaration fiscale par période",
 description = "Agrégation par taux de TVA des factures émises (TVA collectée) ET reçues " +
 "(TVA déductible) sur la période. La TVA due = TVA collectée − TVA déductible " +
 "+ crédit de TVA reporté (art. 286 CGI).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = TaxDeclaration.class),
 examples = @ExampleObject(name = "Déclaration TVA Janvier 2026", value = """
 {
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "from": "2026-01-01",
 "to": "2026-01-31",
 "collectedLines": [
 {"taxCode": "TVA_FR_20", "taxLabel": "TVA France 20%", "rate": 0.20, "taxableBase": 50000.00, "taxAmount": 10000.00},
 {"taxCode": "TVA_FR_5_5", "taxLabel": "TVA France 5,5%", "rate": 0.055, "taxableBase": 10000.00, "taxAmount": 550.00}
 ],
 "deductibleLines": [
 {"taxCode": "TVA_FR_20", "taxLabel": "TVA France 20%", "rate": 0.20, "taxableBase": 20000.00, "taxAmount": 4000.00}
 ],
 "totalTaxCollected": 10550.00,
 "totalTaxDeductible": 4000.00,
 "taxCreditCarriedForward": 0,
 "taxDue": 6550.00,
 "taxCreditToCarryForward": 0
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Dates invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/declarations")
 public TaxDeclaration getDeclaration(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Date de début (incluse)", required = true, example = "2026-01-01")
 @RequestParam LocalDate from,
 @Parameter(description = "Date de fin (incluse)", required = true, example = "2026-01-31")
 @RequestParam LocalDate to,
 @Parameter(description = "Type de taxe à filtrer (optionnel) : VAT, TCA, TURNOVER_TAX, EXCISE. Si null, agrège toutes les taxes (comportement historique).",
 example = "VAT")
 @RequestParam(required = false) String taxType) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 // Reports Hub : exposer le paramètre taxType optionnel.
 // Comportement backward-compatible : si taxType est null/blank, on délègue à
 // getDeclaration(cid, from, to) qui agrège toutes les taxes comme avant.
 if (taxType == null || taxType.isBlank()) {
 return service.getDeclaration(companyId, from, to);
 }
 return service.getDeclaration(companyId, from, to, taxType);
 }

 @Operation(summary = "Déclaration RS sur ventes par période (R-F-validation v6-2)",
 description = "Agrégation par taux de retenue à la source (RS) des factures de ventes " +
 "émises sur la période (Code Fiscal art. 156-1 Haïti). Une ligne par taux " +
 "(2% prestations locales, 10% royalties, 30% non-résidents, 10% loyers). " +
 "Les avoirs (CREDIT_NOTE) sont traités en négatif (ils inversent la RS de " +
 "la facture originale). La RS due = total RS retenue par les clients, " +
 "plancher 0 (si avoirs > factures sur la période → crédit à reporter M+1).")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "Déclaration RS agrégée par taux",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = TaxDeclaration.class),
 examples = @ExampleObject(name = "Déclaration RS Janvier 2026 — RS 2% Haïti", value = """
 {
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "from": "2026-01-01",
 "to": "2026-01-31",
 "collectedLines": [
 {"taxCode": "RS-2.00%", "taxLabel": "RS 2.00% — ventes (art. 156-1 Code Fiscal)", "rate": 2.00, "taxableBase": 500000.00, "taxAmount": 10000.00}
 ],
 "deductibleLines": [],
 "totalTaxCollected": 10000.00,
 "totalTaxDeductible": 0,
 "taxCreditCarriedForward": 0,
 "taxDue": 10000.00,
 "taxCreditToCarryForward": 0
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Dates invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/withholding-declarations")
 public ResponseEntity<TaxDeclaration> getWithholdingDeclaration(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Date de début (incluse)", required = true, example = "2026-01-01")
 @RequestParam LocalDate from,
 @Parameter(description = "Date de fin (incluse)", required = true, example = "2026-01-31")
 @RequestParam LocalDate to) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return ResponseEntity.ok(service.getWithholdingDeclaration(companyId, from, to));
 }

 @Operation(summary = "Projeter l'Impôt sur les Sociétés (IS) pour un exercice",
 description = "Calcule : résultat comptable → résultat fiscal " +
 "(+ réintégrations Charasse, − déductions LTPE) → IS brut (15% PME ou 25%) → " +
 "IS net (− crédits d'impôt) → 4 acomptes + solde au 15 mai N+1. " +
 "Si aucune règle d'IS configurée, utilise les valeurs par défaut France 2026.")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "Projection IS — résultat fiscal, IS brut/net, acomptes + solde",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = jo.accountant.tax.dto.CorporateTaxProjection.class),
 examples = @ExampleObject(name = "IS 2026 — PME au taux réduit 15%", value = """
 {
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "from": "2026-01-01",
 "to": "2026-12-31",
 "accountingResult": 250000.00,
 "adjustments": {
 "charasseAddition": 0,
 "otherAdditions": 0,
 "longTermCapitalGainDeduction": 0,
 "otherDeductions": 0,
 "totalAdditions": 0,
 "totalDeductions": 0
 },
 "taxableResult": 250000.00,
 "appliedRate": 0.25,
 "corporateTaxBrut": 62500.00,
 "taxCredits": 0,
 "corporateTaxNet": 62500.00,
 "installments": [
 {"dueDate": "2026-03-15", "amount": 15625.00, "label": "Acompte 1er trimestre 2026"},
 {"dueDate": "2026-06-15", "amount": 15625.00, "label": "Acompte 2e trimestre 2026"},
 {"dueDate": "2026-09-15", "amount": 15625.00, "label": "Acompte 3e trimestre 2026"},
 {"dueDate": "2026-12-15", "amount": 15625.00, "label": "Acompte 4e trimestre 2026"}
 ],
 "balanceDue": 0,
 "rule": {
 "standardRate": 0.25,
 "reducedRate": 0.15,
 "reducedRateThreshold": 42500.00,
 "eligibility": "LARGE"
 }
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Dates invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/corporate-tax/projection")
 public jo.accountant.tax.dto.CorporateTaxProjection projectCorporateTax(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Date de début d'exercice", required = true, example = "2026-01-01")
 @RequestParam LocalDate from,
 @Parameter(description = "Date de fin d'exercice", required = true, example = "2026-12-31")
 @RequestParam LocalDate to) {
 roleChecker.ensureRole(companyId, "ACCOUNTANT");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return service.projectCorporateTax(companyId, from, to);
 }

 // ════════════════════════════════════════════════════════════════════════
 // V6-5 — Acompte IS 1% mensuel sur encaissements (Code Fiscal Haïti art. 5)
 // ════════════════════════════════════════════════════════════════════════
 @Operation(summary = "Acompte IS 1% mensuel sur encaissements bruts (Code Fiscal Haïti art. 5)",
 description = "Calcule l'acompte IS mensuel de 1% sur les encaissements bruts du mois " +
 "(SUM des factures émises/partiellement payées/payées). " +
 "Conforme au Code Fiscal Haïtien art. 5 — échéance le 15 du mois M+1. " +
 "V6-5validation PME/expert) : non applicable pour les entreprises non-HT " +
 "(retourne montant=0, type=NOT_APPLICABLE).")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200", description = "Acompte calculé",
 content = @Content(schema = @Schema(implementation = jo.accountant.tax.service.TaxService.MonthlyInstallmentHT.class))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant ou module TAX non activé",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/corporate-tax/installments/{year}/{month}")
 public jo.accountant.tax.service.TaxService.MonthlyInstallmentHT computeMonthlyInstallmentHT(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Année de la période", required = true, example = "2026")
 @PathVariable int year,
 @Parameter(description = "Mois de la période (1-12)", required = true, example = "7")
 @PathVariable int month) {
 roleChecker.ensureRole(companyId, "ACCOUNTANT");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 return service.computeMonthlyInstallmentHT(companyId, year, month);
 }

 @Operation(summary = "Échéancier des déclarations fiscales (audit mobile #7)",
 description = "Retourne le planning annuel des échéances fiscales françaises pour l'année demandée : " +
 "TVA mensuelle (12 échéances, le 19 du mois M+1) OU trimestrielle (4 échéances), " +
 "IS acomptes (15 mars/juin/sept/déc) + solde (15 mai N+1, art. 1668 CGI), " +
 "DES mensuelle (10 du mois M+1, art. 289 B CGI). " +
 "Le paramètre ?year= est optionnel (défaut : année courante). " +
 "Limitation v1 : ne tient pas compte des reports de weekend/jour férié (art. A. 40 A LPF).")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "Échéancier annuel des déclarations fiscales françaises",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = jo.accountant.tax.dto.TaxDeclarationSchedule.class),
 examples = @ExampleObject(name = "Échéancier 2026 (TVA mensuelle + IS + DES)", value = """
 {
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "year": 2026,
 "vatRegime": "MENSUEL",
 "deadlines": [
 {"date": "2026-01-19", "type": "VAT_MONTHLY", "label": "TVA Décembre 2025"},
 {"date": "2026-01-10", "type": "DES_MONTHLY", "label": "DES Décembre 2025"},
 {"date": "2026-02-19", "type": "VAT_MONTHLY", "label": "TVA Janvier 2026"},
 {"date": "2026-02-10", "type": "DES_MONTHLY", "label": "DES Janvier 2026"},
 {"date": "2026-03-15", "type": "CORPORATE_TAX_INSTALLMENT", "label": "Acompte IS 1er trimestre 2026"},
 {"date": "2026-03-19", "type": "VAT_MONTHLY", "label": "TVA Février 2026"},
 {"date": "2026-05-15", "type": "CORPORATE_TAX_BALANCE", "label": "Solde IS 2025 (art. 1668 CGI)"},
 {"date": "2026-06-15", "type": "CORPORATE_TAX_INSTALLMENT", "label": "Acompte IS 2e trimestre 2026"},
 {"date": "2026-09-15", "type": "CORPORATE_TAX_INSTALLMENT", "label": "Acompte IS 3e trimestre 2026"},
 {"date": "2026-12-15", "type": "CORPORATE_TAX_INSTALLMENT", "label": "Acompte IS 4e trimestre 2026"}
 ]
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/declaration-schedule")
 public jo.accountant.tax.dto.TaxDeclarationSchedule getDeclarationSchedule(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Année fiscale (défaut : année courante)", example = "2026")
 @RequestParam(required = false) Integer year) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 int effectiveYear = year != null ? year : java.time.LocalDate.now().getYear();
 return service.getDeclarationSchedule(companyId, effectiveYear);
 }

 @Operation(summary = "Exporter une déclaration fiscale (CA3 / DES / EFI) — audit mobile #8",
 description = "Génère un fichier d'export pour la déclaration fiscale française sur la période. " +
 "Formats supportés : <ul>" +
 "<li><b>ca3</b> — formulaire 3517-S-SD (TVA mensuelle/trimestrielle). CSV UTF-8 BOM, " +
 "séparateur point-virgule, colonnes : base_ht, taux_tva, tva_collectee, tva_deductible, tva_due. " +
 "Compatible copier-coller dans le formulaire CA3 sur impots.gouv.fr.</li>" +
 "<li><b>des</b> — Déclaration d'Échanges de Services intra-UE B2B (art. 289 B CGI). " +
 "Non implémenté : agrégation par pays UE du tiers.</li>" +
 "<li><b>efi</b> — XML EDI pour télédéclaration directe via le PPF. " +
 "Non implémenté : génération du XML EDI (requires échange de formulaires informatisé).</li>" +
 "</ul>" +
 "Pour l'instant, seul le format <code>ca3</code> est implémenté ; les autres " +
 "retournent 501 Not Implemented.")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "Export CSV CA3 (formulaire 3517-S-SD) — encodé UTF-8 BOM, séparateur point-virgule",
 content = @Content(mediaType = "text/csv",
 schema = @Schema(type = "string", format = "binary"),
 examples = @ExampleObject(name = "CSV CA3 Janvier 2026", value = """
 base_ht;taux_tva;tva_collectee;tva_deductible;tva_due
 50000.00;0.20;10000.00;4000.00;6550.00
 10000.00;0.055;550.00;0.00;0.00
 """))),
 @ApiResponse(responseCode = "422", description = "Dates invalides ou format inconnu",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/unsupported-export-format",
 "title": "Format d'export inconnu",
 "status": 422,
 "detail": "Format d'export inconnu: 'csv'. Formats supportés: ca3, des, efi.",
 "properties": {"code": "UNSUPPORTED_EXPORT_FORMAT"}
 }
 """))),
 @ApiResponse(responseCode = "501", description = "Format non implémenté (DES, EFI) — Non implémenté : ",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/not-implemented",
 "title": "Export non implémenté",
 "status": 501,
 "detail": "Export DES non implémenté (Non implémenté : agrégation par pays UE du tiers, art. 289 B CGI).",
 "properties": {"code": "DES_EXPORT_NOT_IMPLEMENTED"}
 }
 """)))
 })
 @GetMapping("/declarations/export")
 public ResponseEntity<byte[]> exportDeclaration(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Format d'export : `ca3` (France), `dgi-tva`/`dgi-tca`/`dgi-rs` (Haïti mensuel), `dgi-dcr` (Haïti annuel), `des`/`efi` (Non implémenté : )",
 required = true, example = "ca3")
 @RequestParam String format,
 @Parameter(description = "Date de début (incluse) — requis pour ca3", required = false, example = "2026-01-01")
 @RequestParam(required = false) LocalDate from,
 @Parameter(description = "Date de fin (incluse) — requis pour ca3", required = false, example = "2026-01-31")
 @RequestParam(required = false) LocalDate to,
 @Parameter(description = "Année fiscale — requis pour formats dgi-*", required = false, example = "2026")
 @RequestParam(required = false) Integer year,
 @Parameter(description = "Mois (1-12) — requis pour formats dgi-tva/tca/rs", required = false, example = "7")
 @RequestParam(required = false) Integer month) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);

 String normalized = format == null ? "" : format.trim().toLowerCase();
 return switch (normalized) {
 case "ca3" -> {
 if (from == null || to == null) {
 yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "DATES_REQUIRED")
 .body(null);
 }
 byte[] csv = exportService.exportCa3(companyId, from, to);
 String filename = "ca3-" + companyId + "_" + from + "_" + to + ".csv";
 yield ResponseEntity.ok()
 .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
 .header(HttpHeaders.CONTENT_DISPOSITION,
 "attachment; filename=\"" + filename + "\"")
 .body(csv);
 }
 // V8-3 — Formats DGI Haïti (mensuels : TVA/TCA/RS ; annuel : DCR)
 case "dgi-tva" -> {
 if (year == null || month == null) {
 yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "YEAR_AND_MONTH_REQUIRED")
 .body(null);
 }
 byte[] csv = exportService.exportDgiTva(companyId, year, month);
 String filename = "dgi-tva-" + companyId + "_" + year + "_" + String.format("%02d", month) + ".csv";
 yield ResponseEntity.ok()
 .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
 .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
 .body(csv);
 }
 case "dgi-tca" -> {
 if (year == null || month == null) {
 yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "YEAR_AND_MONTH_REQUIRED")
 .body(null);
 }
 byte[] csv = exportService.exportDgiTca(companyId, year, month);
 String filename = "dgi-tca-" + companyId + "_" + year + "_" + String.format("%02d", month) + ".csv";
 yield ResponseEntity.ok()
 .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
 .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
 .body(csv);
 }
 case "dgi-rs" -> {
 if (year == null || month == null) {
 yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "YEAR_AND_MONTH_REQUIRED")
 .body(null);
 }
 byte[] csv = exportService.exportDgiRs(companyId, year, month);
 String filename = "dgi-rs-" + companyId + "_" + year + "_" + String.format("%02d", month) + ".csv";
 yield ResponseEntity.ok()
 .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
 .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
 .body(csv);
 }
 case "dgi-dcr" -> {
 if (year == null) {
 yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "YEAR_REQUIRED")
 .body(null);
 }
 byte[] csv = exportService.exportDgiDcr(companyId, year);
 String filename = "dgi-dcr-" + companyId + "_" + year + ".csv";
 yield ResponseEntity.ok()
 .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
 .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
 .body(csv);
 }
 case "des" -> {
 // Non implémenté : agrégation par pays UE du tiers (art. 289 B CGI)
 yield ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
 .header("X-Error-Reason", "DES_EXPORT_NOT_IMPLEMENTED")
 .header(HttpHeaders.WARNING,
 "199 - \"Export DES non implémenté (Non implémenté : agrégation par pays UE du tiers).\"")
 .body(null);
 }
 case "efi" -> {
 // Non implémenté : génération du XML EDI pour télédéclaration PPF
 yield ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
 .header("X-Error-Reason", "EFI_EXPORT_NOT_IMPLEMENTED")
 .header(HttpHeaders.WARNING,
 "199 - \"Export EFI non implémenté (Non implémenté : XML EDI pour PPF).\"")
 .body(null);
 }
 default -> {
 yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "UNSUPPORTED_EXPORT_FORMAT")
 .header(HttpHeaders.WARNING,
 "199 - \"Format d'export inconnu: '" + format
 + "'. Formats supportés: ca3, des, efi.\"")
 .body(null);
 }
 };
 }

 // ======================================================================
 // Reports Hub : endpoints PDF dédiés
 // (déclaration TVA, déclaration TCA, projection d'IS).
 // ======================================================================

 @Operation(summary = "Générer une déclaration fiscale en PDF (Reports Hub)",
 description = "Rendu PDF d'une déclaration TVA ou TCA via :document-generation. " +
 "<code>?taxType=VAT</code> (template VAT_DECLARATION_REPORT) ou " +
 "<code>?taxType=TCA</code> (template TCA_DECLARATION_REPORT). " +
 "Délègue au même service métier que GET /tax/declarations?taxType=...")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (déclaration fiscale)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant ou module TAX non activé",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "taxType invalide ou dates manquantes",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/declarations/pdf")
 public ResponseEntity<byte[]> getDeclarationPdf(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Type de taxe : 'VAT' ou 'TCA'", required = true, example = "VAT")
 @RequestParam String taxType,
 @Parameter(description = "Date de début (incluse)", required = true, example = "2026-01-01")
 @RequestParam LocalDate from,
 @Parameter(description = "Date de fin (incluse)", required = true, example = "2026-01-31")
 @RequestParam LocalDate to) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 String normalized = taxType == null ? "" : taxType.trim().toUpperCase();
 GeneratedDocumentType docType;
 switch (normalized) {
 case "VAT" -> docType = GeneratedDocumentType.VAT_DECLARATION_REPORT;
 case "TCA" -> docType = GeneratedDocumentType.TCA_DECLARATION_REPORT;
 default -> {
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "INVALID_TAX_TYPE")
 .body(null);
 }
 }

 // Appeler le service avec filtre taxType (déjà supporté — v6-1-multi-tax-invoice-line)
 TaxDeclaration declaration = service.getDeclaration(companyId, from, to, normalized);

 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("from", from.toString());
 variables.put("to", to.toString());
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("collectedLines", declaration.collectedLines() != null ? declaration.collectedLines() : List.of());
 variables.put("deductibleLines", declaration.deductibleLines() != null ? declaration.deductibleLines() : List.of());
 variables.put("totalTaxCollected", declaration.totalTaxCollected() != null ? declaration.totalTaxCollected().toString() : "0");
 variables.put("totalTaxDeductible", declaration.totalTaxDeductible() != null ? declaration.totalTaxDeductible().toString() : "0");
 variables.put("taxDue", declaration.taxDue() != null ? declaration.taxDue().toString() : "0");
 variables.put("taxCreditCarriedForward", declaration.taxCreditCarriedForward() != null ? declaration.taxCreditCarriedForward().toString() : "0");
 variables.put("taxCreditToCarryForward", declaration.taxCreditToCarryForward() != null ? declaration.taxCreditToCarryForward().toString() : "0");

 String filename = "declaration-" + normalized.toLowerCase() + "-" + companyId + "-" + from + "_" + to + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, docType, variables, filename);
 LOG.info("[PDF] Déclaration {} générée pour companyId={} période={}→{} ({} octets)",
 normalized, companyId, from, to, response.getBody().length);
 return response;
 }

 @Operation(summary = "Générer la projection d'IS en PDF (Reports Hub)",
 description = "Rendu PDF de la projection d'Impôt sur les Sociétés via :document-generation " +
 "(template CORPORATE_TAX_PROJECTION_REPORT). " +
 "Délègue au même service métier que GET /tax/corporate-tax/projection.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (projection d'IS)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant ou module TAX non activé",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/corporate-tax/projection/pdf")
 public ResponseEntity<byte[]> getCorporateTaxProjectionPdf(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Date de début d'exercice", required = true, example = "2026-01-01")
 @RequestParam LocalDate from,
 @Parameter(description = "Date de fin d'exercice", required = true, example = "2026-12-31")
 @RequestParam LocalDate to) {
 roleChecker.ensureRole(companyId, "ACCOUNTANT");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 jo.accountant.tax.dto.CorporateTaxProjection projection = service.projectCorporateTax(companyId, from, to);

 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("from", from.toString());
 variables.put("to", to.toString());
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("accountingResult", projection.accountingResult() != null ? projection.accountingResult().toString() : "0");
 variables.put("adjustments", projection.adjustments());
 variables.put("taxableResult", projection.taxableResult() != null ? projection.taxableResult().toString() : "0");
 variables.put("appliedRate", projection.appliedRate() != null ? projection.appliedRate().toString() : "0");
 variables.put("corporateTaxBrut", projection.corporateTaxBrut() != null ? projection.corporateTaxBrut().toString() : "0");
 variables.put("taxCredits", projection.taxCredits() != null ? projection.taxCredits().toString() : "0");
 variables.put("corporateTaxNet", projection.corporateTaxNet() != null ? projection.corporateTaxNet().toString() : "0");
 variables.put("installments", projection.installments() != null ? projection.installments() : List.of());
 variables.put("balanceDue", projection.balanceDue() != null ? projection.balanceDue().toString() : "0");

 String filename = "projection-is-" + companyId + "-" + from + "_" + to + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, GeneratedDocumentType.CORPORATE_TAX_PROJECTION_REPORT, variables, filename);
 LOG.info("[PDF] Projection IS générée pour companyId={} exercice={}→{} ({} octets)",
 companyId, from, to, response.getBody().length);
 return response;
 }
}

package jo.accountant.expenses.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.expenses.dto.CreateExpenseReportRequest;
import jo.accountant.expenses.dto.ExpenseReportResponse;
import jo.accountant.expenses.service.ExpensesService;
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
 * Endpoints des notes de frais (module :expenses).
 *
 * <p>Le module est <strong>toujours-actif</strong> (always-on — voir
 * `BusinessTypeModuleService.alwaysOnModules`). Pas de `ModuleAccessGuard` requise sur
 * ses endpoints (au même titre qu'`:invoicing`).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/expense-reports")
@Tag(name = "Expenses", description = "Notes de frais")
public class ExpensesController {

 private final ExpensesService service;
 private final RoleChecker roleChecker;

 public ExpensesController(ExpensesService service, RoleChecker roleChecker) {
 this.service = service;
 this.roleChecker = roleChecker;
 }

 @Operation(summary = "Créer une note de frais (DRAFT)",
 description = "Crée une note de frais au statut DRAFT. Doit être soumise via POST /{id}/submit " +
 "puis approuvée par un ADMIN avant génération de l'écriture comptable.")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Note de frais mission Paris créée", value = """
 {
 "id": "0192c0f2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
 "status": "DRAFT",
 "expenseDate": "2026-03-15",
 "currency": "EUR",
 "description": "Mission client Paris 15-16 mars",
 "totalAmount": 215.00,
 "paidDirectly": false,
 "journalEntryId": null,
 "lines": [
 {"id": "0192c0f3-1c2d-3e4f-5a6b-7c8d9e0fabcd", "category": "MISSION_PARIS", "description": "Transport SNCF aller-retour", "amount": 95.00, "expenseAccountId": "0192a8c0-3e4f-5a6b-7c8d-9e0fa1bcde03"},
 {"id": "0192c0f3-2d3e-4f5a-6b7c-8d9e0fa1bcde", "category": "REPAS_CLIENT", "description": "Repas avec client", "amount": 120.00, "expenseAccountId": "0192a8c0-4f5a-6b7c-8d9e-0fa1bcde04"}
 ],
 "createdAt": "2026-03-17T08:00:00Z",
 "updatedAt": "2026-03-17T08:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "Lignes vides ou amount ≤ 0",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<ExpenseReportResponse> create(
 @PathVariable UUID companyId, @CurrentUser UUID userId,
 @Valid @RequestBody CreateExpenseReportRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 return ResponseEntity.status(HttpStatus.CREATED).body(service.create(companyId, req));
 }

 @Operation(summary = "Lister les notes de frais (paginé)",
 description = "Filtre optionnel <code>?fiscalYearId=</code> (UUID) — si présent, " +
 "résout l'exercice via <code>AccountingEngineService.resolveFiscalYear</code> " +
 "et ne retourne que les notes dont <code>expenseDate</code> est comprise " +
 "entre les bornes start/end de l'exercice. Pagination via <code>?page=&size=</code> " +
 "(défaut 0/20, size capped à 200). remplace la variante List<> " +
 "pour éviter l'OOM sur entreprises matures.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Page de 3 notes de frais", value = """
 {
 "content": [
 {
 "id": "0192c0f2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
 "status": "APPROVED",
 "expenseDate": "2026-03-15",
 "currency": "EUR",
 "description": "Mission client Paris 15-16 mars",
 "totalAmount": 215.00,
 "paidDirectly": false,
 "lines": []
 },
 {
 "id": "0192c0f2-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "thirdPartyName": "Marie Lefèvre",
 "status": "SUBMITTED",
 "expenseDate": "2026-03-20",
 "currency": "EUR",
 "description": "Fournitures bureau",
 "totalAmount": 75.50,
 "paidDirectly": true,
 "lines": []
 },
 {
 "id": "0192c0f2-3e4f-5a6b-7c8d-9e0fa1bcde02",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
 "status": "DRAFT",
 "expenseDate": "2026-03-25",
 "currency": "EUR",
 "description": "Taxi aéroport",
 "totalAmount": 45.00,
 "paidDirectly": false,
 "lines": []
 }
 ],
 "totalElements": 3,
 "totalPages": 1,
 "number": 0,
 "size": 20,
 "first": true,
 "last": true,
 "empty": false
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping
 public org.springframework.data.domain.Page<ExpenseReportResponse> list(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @RequestParam(required = false) UUID fiscalYearId,
 @RequestParam(required = false, defaultValue = "0") int page,
 @RequestParam(required = false, defaultValue = "20") int size) {
 roleChecker.ensureRole(companyId, "VIEWER");
 // PageRequest cappé à 200 (empêche l'OOM si un client demande size=10000).
 org.springframework.data.domain.Pageable pageable =
 org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
 return service.list(companyId, fiscalYearId, pageable);
 }

 @Operation(summary = "Détail d'une note de frais",
 description = "Retourne la note de frais avec toutes ses lignes (catégorie + montant + compte de charge).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Note de frais mission Paris", value = """
 {
 "id": "0192c0f2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
 "status": "APPROVED",
 "expenseDate": "2026-03-15",
 "currency": "EUR",
 "description": "Mission client Paris 15-16 mars",
 "totalAmount": 215.00,
 "paidDirectly": false,
 "journalEntryId": "0192c0f4-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "lines": [
 {"id": "0192c0f3-1c2d-3e4f-5a6b-7c8d9e0fabcd", "category": "MISSION_PARIS", "description": "Transport SNCF aller-retour", "amount": 95.00, "expenseAccountId": "0192a8c0-3e4f-5a6b-7c8d-9e0fa1bcde03"},
 {"id": "0192c0f3-2d3e-4f5a-6b7c-8d9e0fa1bcde", "category": "REPAS_CLIENT", "description": "Repas avec client", "amount": 120.00, "expenseAccountId": "0192a8c0-4f5a-6b7c-8d9e-0fa1bcde04"}
 ],
 "createdAt": "2026-03-17T08:00:00Z",
 "updatedAt": "2026-03-18T14:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Note de frais introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping("/{id}")
 public ExpenseReportResponse get(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 return service.get(companyId, id);
 }

 @Operation(summary = "Soumettre une note (DRAFT → SUBMITTED)",
 description = "Verrouille la note pour validation par un ADMIN. Retourne la note avec status=SUBMITTED.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Note soumise", value = """
 {
 "id": "0192c0f2-3e4f-5a6b-7c8d-9e0fa1bcde02",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "status": "SUBMITTED",
 "expenseDate": "2026-03-25",
 "totalAmount": 45.00,
 "description": "Taxi aéroport"
 }
 """))),
 @ApiResponse(responseCode = "409", description = "Note non DRAFT",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/{id}/submit")
 public ExpenseReportResponse submit(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 return service.submit(companyId, id);
 }

 @Operation(summary = "Approuver une note (SUBMITTED → APPROVED)",
 description = "Génère l'écriture comptable (Débit Charges / Crédit Tiers-Employé " +
 "ou Trésorerie selon paidDirectly).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Note approuvée + écriture générée", value = """
 {
 "id": "0192c0f2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "status": "APPROVED",
 "totalAmount": 215.00,
 "journalEntryId": "0192c0f4-1c2d-3e4f-5a6b-7c8d9e0fabcd"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "Note non SUBMITTED",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/{id}/approve")
 public ExpenseReportResponse approve(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return service.approve(companyId, id);
 }

 @Operation(summary = "Rejeter une note (SUBMITTED → REJECTED)",
 description = "La note revient en DRAFT après correction. Aucune écriture générée.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Note rejetée", value = """
 {
 "id": "0192c0f2-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "status": "REJECTED",
 "totalAmount": 75.50,
 "description": "Fournitures bureau"
 }
 """))),
 @ApiResponse(responseCode = "409", description = "Note non SUBMITTED",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/{id}/reject")
 public ExpenseReportResponse reject(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return service.reject(companyId, id);
 }

 @Operation(summary = "Marquer une note comme payée (APPROVED → PAID)",
 description = "Note payée par trésorerie (virement, chèque) — change le statut à PAID.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseReportResponse.class),
 examples = @ExampleObject(name = "Note payée", value = """
 {
 "id": "0192c0f2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "status": "PAID",
 "totalAmount": 215.00,
 "paidDirectly": false,
 "journalEntryId": "0192c0f4-1c2d-3e4f-5a6b-7c8d9e0fabcd"
 }
 """))),
 @ApiResponse(responseCode = "409", description = "Note non APPROVED",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/{id}/payments")
 public ExpenseReportResponse pay(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 return service.pay(companyId, id);
 }
}

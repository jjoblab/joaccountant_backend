package jo.accountant.employees.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import jo.accountant.employees.dto.CreateEmployeeRequest;
import jo.accountant.employees.dto.EmployeeResponse;
import jo.accountant.employees.dto.UpdateEmployeeRequest;
import jo.accountant.employees.dto.UpdateEmployeeStatusRequest;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.service.EmployeesService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints des employés (module :employees).
 *
 * <p>Le module est <strong>toujours-actif</strong> (always-on — voir
 * `BusinessTypeModuleService.alwaysOnModules`). Pas de `ModuleAccessGuard` requise sur
 * ses endpoints (au même titre qu'`:invoicing`).
 *
 * <p>Endpoints exposés :
 * <ul>
 *   <li>{@code GET  /} — liste (sans pagination, legacy) </li>
 *   <li>{@code GET  /?page=0&size=50} — liste paginée </li>
 *   <li>{@code POST /} — créer un employé </li>
 *   <li>{@code GET  /{id}} — détail </li>
 *   <li>{@code PATCH /{id}} — mise à jour partielle </li>
 *   <li>{@code DELETE /{id}} — soft-delete (status=TERMINATED) </li>
 *   <li>{@code POST /{id}/status} — changer le statut (DTO body) </li>
 * </ul>
 *
 * @author jo@Dev


*/
@RestController
@RequestMapping("/api/v1/companies/{companyId}/employees")
@Tag(name = "Employees", description = "Employés / RH")
public class EmployeesController {

 private final EmployeesService service;
 private final RoleChecker roleChecker;

 public EmployeesController(EmployeesService service, RoleChecker roleChecker) {
 this.service = service;
 this.roleChecker = roleChecker;
 }

 @Operation(summary = "Créer un employé",
 description = "Crée la fiche employé + (optionnel) le tiers EMPLOYEE associé. " +
 "Si thirdPartyId est fourni, on rattache l'employé au tiers existant " +
 "(doit être de type EMPLOYEE). Si thirdPartyName + collectiveAccountId " +
 "sont fournis, le tiers est créé en même temps que l'employé.")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class),
 examples = @ExampleObject(name = "Employé CDI créé avec V60 (HS + absences)", value = """
 {
 "id": "0192a8d7-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
 "firstName": "Jean",
 "lastName": "Dupont",
 "email": "jean.dupont@example.com",
 "jobTitle": "Comptable",
 "employeeNumber": "EMP-0001",
 "position": "Comptable",
 "department": "Finance",
 "hireDate": "2026-01-15",
 "terminationDate": null,
 "baseSalary": 45000.00,
 "salaryCurrency": "EUR",
 "contractType": "PERMANENT",
 "status": "ACTIVE",
 "bankAccountNumber": "FR7630004000011234567890123",
 "overtimeHours25": 8,
 "overtimeHours50": 2,
 "absenceDays": 1.5,
 "paidLeaveDays": 2,
 "createdAt": "2026-01-15T09:00:00Z",
 "updatedAt": "2026-01-15T09:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "employeeNumber vide ou baseSalary ≤ 0",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<EmployeeResponse> create(
 @PathVariable UUID companyId, @CurrentUser UUID userId,
 @Valid @RequestBody CreateEmployeeRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return ResponseEntity.status(HttpStatus.CREATED).body(service.create(companyId, req));
 }

 @Operation(summary = "Lister les employés (sans pagination — legacy)",
 description = "Filtrable par statut via `?status=ACTIVE` — utilisé par :payroll " +
 "pour lister les salariés à payer sur une période. Variante non paginée (cap 500 côté service). " +
 "Pour la pagination, utiliser `?page=0&size=50` qui déclenche la variante paginée.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping(params = "!page")
 public List<EmployeeResponse> list(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Filtrer par statut : ACTIVE, ON_LEAVE, TERMINATED", example = "ACTIVE")
 @RequestParam(value = "status", required = false) String status) {
 roleChecker.ensureRole(companyId, "VIEWER");
 EmployeeStatus statusFilter = status != null && !status.isBlank()
 ? EmployeeStatus.valueOf(status.trim().toUpperCase()) : null;
 return service.list(companyId, statusFilter);
 }

 @Operation(summary = "Lister les employés (paginé)",
 description = "Variante paginée — déclenchée automatiquement quand `page` est présent dans " +
 "la query string. Défaut size=50, capped à 200. Utilisé par le mobile pour charger " +
 "les employés par page (optimisation mémoire).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping(params = "page")
 public org.springframework.data.domain.Page<EmployeeResponse> listPaged(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Filtrer par statut : ACTIVE, ON_LEAVE, TERMINATED", example = "ACTIVE")
 @RequestParam(value = "status", required = false) String status,
 @RequestParam(defaultValue = "0") int page,
 @RequestParam(defaultValue = "50") int size) {
 roleChecker.ensureRole(companyId, "VIEWER");
 EmployeeStatus statusFilter = status != null && !status.isBlank()
 ? EmployeeStatus.valueOf(status.trim().toUpperCase()) : null;
 // Cap size à 200 (empêche OOM si un client demande size=10000).
 org.springframework.data.domain.Pageable pageable = PageRequest.of(page, Math.min(size, 200));
 return service.list(companyId, statusFilter, pageable);
 }

 @Operation(summary = "Détail d'un employé",
 description = "Retourne la fiche complète d'un employé, y compris les champs d'affichage " +
 "(firstName, lastName, email, jobTitle) et les champs V60 (heures sup, absences, congés).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class),
 examples = @ExampleObject(name = "Détail employé Jean Dupont", value = """
 {
 "id": "0192a8d7-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
 "firstName": "Jean",
 "lastName": "Dupont",
 "email": "jean.dupont@example.com",
 "jobTitle": "Comptable",
 "employeeNumber": "EMP-0001",
 "position": "Comptable",
 "department": "Finance",
 "hireDate": "2026-01-15",
 "terminationDate": null,
 "baseSalary": 45000.00,
 "salaryCurrency": "EUR",
 "contractType": "PERMANENT",
 "status": "ACTIVE",
 "bankAccountNumber": "FR7630004000011234567890123",
 "overtimeHours25": 8,
 "overtimeHours50": 2,
 "absenceDays": 1.5,
 "paidLeaveDays": 2,
 "createdAt": "2026-01-15T09:00:00Z",
 "updatedAt": "2026-07-28T10:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Employé introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/not-found",
 "title": "Employé introuvable",
 "status": 404,
 "detail": "Aucun employé avec l'id 0192a8d7-1c2d-3e4f-5a6b-7c8d9e0fabcd pour cette entreprise.",
 "properties": {"code": "EMPLOYEE_NOT_FOUND"}
 }
 """)))
 })
 @GetMapping("/{id}")
 public EmployeeResponse get(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 return service.get(companyId, id);
 }

 @Operation(summary = "Mettre à jour partiellement un employé (PATCH)",
 description = "Sémantique PATCH : seuls les champs non-nuls du corps sont appliqués. " +
 "Les champs à null sont ignorés. employeeNumber, hireDate, thirdPartyId, status " +
 "ne sont pas modifiables via ce endpoint.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(schema = @Schema(implementation = EmployeeResponse.class))),
 @ApiResponse(responseCode = "404", description = "Employé introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "baseSalary ≤ 0 / champ invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
 public EmployeeResponse update(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId,
 @Valid @RequestBody UpdateEmployeeRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return service.updateEmployee(companyId, id, req);
 }

 @Operation(summary = "Supprimer un employé (soft-delete)",
 description = "Passe le statut à TERMINATED avec terminationReason='Deleted by user'. " +
 "L'employé reste en base pour préserver l'historique des paies et écritures comptables. " +
 "N'apparaît plus dans la liste des employés actifs (filtre par défaut du mobile).")
 @ApiResponses({
 @ApiResponse(responseCode = "204", description = "Employé supprimé (soft-delete TERMINATED)"),
 @ApiResponse(responseCode = "404", description = "Employé introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @DeleteMapping("/{id}")
 public ResponseEntity<Void> delete(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "ADMIN");
 service.deleteEmployee(companyId, id);
 return ResponseEntity.noContent().build();
 }

 @Operation(summary = "Changer le statut d'un employé (DTO body)",
 description = "Passe l'employé à ON_LEAVE, ACTIVE ou TERMINATED via un body JSON " +
 "(fix mobile 2026-07-26 — avant le backend attendait ?status= en query param " +
 "alors que le mobile envoyait un body). Si TERMINATED sans terminationDate explicite, " +
 "la date du jour est utilisée. terminationReason est persisté (précédemment ignoré).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class),
 examples = @ExampleObject(name = "Body JSON — passage à TERMINATED", value = """
 {
 "status": "TERMINATED",
 "terminationDate": "2026-09-30",
 "terminationReason": "Fin de CDD"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "Employé introuvable",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "Statut invalide / terminationDate mal formatée",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(value = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
 public EmployeeResponse changeStatus(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId,
 @Valid @RequestBody UpdateEmployeeStatusRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return service.changeStatus(companyId, id, req);
 }
}

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
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.service.EmployeesService;
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
 * Endpoints des employés (module :employees).
 *
 * <p>Le module est <strong>toujours-actif</strong> (always-on — voir
 * `BusinessTypeModuleService.alwaysOnModules`). Pas de `ModuleAccessGuard` requise sur
 * ses endpoints (au même titre qu'`:invoicing`).
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

 @Operation(summary = "Lister les employés",
 description = "Filtrable par statut via `?status=ACTIVE` — utilisé par :payroll " +
 "pour lister les salariés à payer sur une période.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class),
 examples = @ExampleObject(name = "2 employés actifs avec V60", value = """
 [
 {
 "id": "0192a8d7-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyName": "Jean Dupont",
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
 },
 {
 "id": "0192a8d7-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "thirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "thirdPartyName": "Marie Lefèvre",
 "employeeNumber": "EMP-0002",
 "position": "Chef de mission",
 "department": "Audit",
 "hireDate": "2025-09-01",
 "terminationDate": null,
 "baseSalary": 55000.00,
 "salaryCurrency": "EUR",
 "contractType": "PERMANENT",
 "status": "ACTIVE",
 "bankAccountNumber": "FR7630004000019876543210987",
 "overtimeHours25": 0,
 "overtimeHours50": 0,
 "absenceDays": 0,
 "paidLeaveDays": 0,
 "createdAt": "2025-09-01T08:00:00Z",
 "updatedAt": "2026-07-28T10:00:00Z"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping
 public List<EmployeeResponse> list(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "Filtrer par statut : ACTIVE, ON_LEAVE, TERMINATED", example = "ACTIVE")
 @RequestParam(value = "status", required = false) String status) {
 roleChecker.ensureRole(companyId, "VIEWER");
 EmployeeStatus statusFilter = status != null && !status.isBlank()
 ? EmployeeStatus.valueOf(status.trim().toUpperCase()) : null;
 return service.list(companyId, statusFilter);
 }

 @Operation(summary = "Détail d'un employé",
 description = "Retourne la fiche complète d'un employé, y compris les champs V60 (heures sup, absences, congés).")
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

 @Operation(summary = "Changer le statut d'un employé",
 description = "Passe l'employé à ON_LEAVE, ACTIVE ou TERMINATED. Si TERMINATED sans " +
 "terminationDate explicite, la date du jour est utilisée.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = EmployeeResponse.class),
 examples = @ExampleObject(name = "Employé passé à TERMINATED", value = """
 {
 "id": "0192a8d7-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "employeeNumber": "EMP-0001",
 "position": "Comptable",
 "status": "TERMINATED",
 "terminationDate": "2026-09-30",
 "baseSalary": 45000.00,
 "contractType": "PERMANENT",
 "overtimeHours25": 0,
 "overtimeHours50": 0,
 "absenceDays": 0,
 "paidLeaveDays": 0
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "Employé introuvable",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "Statut invalide (valeurs : ACTIVE, ON_LEAVE, TERMINATED)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/{id}/status")
 public EmployeeResponse changeStatus(@PathVariable UUID companyId,
 @PathVariable UUID id,
 @CurrentUser UUID userId,
 @Parameter(description = "Nouveau statut : ACTIVE, ON_LEAVE, TERMINATED", required = true, example = "TERMINATED")
 @RequestParam String status) {
 roleChecker.ensureRole(companyId, "ADMIN");
 EmployeeStatus newStatus = EmployeeStatus.valueOf(status.trim().toUpperCase());
 return service.changeStatus(companyId, id, newStatus);
 }
}

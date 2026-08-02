package jo.accountant.timebilling.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.timebilling.dto.CreateBillableRateRequest;
import jo.accountant.timebilling.dto.CreateProjectRequest;
import jo.accountant.timebilling.dto.CreateTimesheetEntryRequest;
import jo.accountant.timebilling.dto.ProjectResponse;
import jo.accountant.timebilling.dto.TimesheetEntryResponse;
import jo.accountant.timebilling.dto.UnbilledWip;
import jo.accountant.timebilling.dto.UtilizationLine;
import jo.accountant.timebilling.service.TimeBillingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de suivi du temps (§13.
 
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
 *   <li>{@code PATCH /}</li>
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 * </ul>

 * @author jo@Dev


*/
@RestController
@RequestMapping("/api/v1/companies/{companyId}/time-billing")
@Tag(name = "TimeBilling", description = "Temps, projets, WIP (secteur Service, §13")
public class TimeBillingController {

    private final TimeBillingService service;
    private final RoleChecker roleChecker;
    private final ModuleAccessGuard moduleAccessGuard;

    public TimeBillingController(TimeBillingService service, RoleChecker roleChecker,
                                 ModuleAccessGuard moduleAccessGuard) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.moduleAccessGuard = moduleAccessGuard;
    }

    @Operation(summary = "Créer un projet",
        description = "Crée un nouveau projet (time & materials ou fixed fee) pour l'entreprise.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ProjectResponse.class),
                examples = @ExampleObject(name = "Projet PRJ-001 créé", value = """
                    {
                      "id": "0192c0fc-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "clientThirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "code": "PRJ-001",
                      "label": "Audit Boulangerie du Marché 2026",
                      "status": "ACTIVE",
                      "billingType": "TIME_AND_MATERIALS",
                      "createdAt": "2026-02-01T09:00:00Z",
                      "updatedAt": "2026-02-01T09:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(value = "/projects", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectResponse> createProject(@PathVariable UUID companyId,
                                                         @CurrentUser UUID userId,
                                                         @Valid @RequestBody CreateProjectRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createProject(companyId, req));
    }

    @Operation(summary = "Lister les projets",
        description = "Retourne tous les projets de l'entreprise (tous statuts confondus).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ProjectResponse.class),
                examples = @ExampleObject(name = "2 projets", value = """
                    [
                      {
                        "id": "0192c0fc-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "clientThirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "code": "PRJ-001",
                        "label": "Audit Boulangerie du Marché 2026",
                        "status": "ACTIVE",
                        "billingType": "TIME_AND_MATERIALS",
                        "createdAt": "2026-02-01T09:00:00Z",
                        "updatedAt": "2026-02-01T09:00:00Z"
                      },
                      {
                        "id": "0192c0fc-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "clientThirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "code": "PRJ-002",
                        "label": "Mission conseil fiscal",
                        "status": "CLOSED",
                        "billingType": "FIXED_FEE",
                        "createdAt": "2025-09-15T08:00:00Z",
                        "updatedAt": "2026-01-15T17:00:00Z"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/projects")
    public List<ProjectResponse> listProjects(@PathVariable UUID companyId,
                                              @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return service.listProjects(companyId);
    }

    @Operation(summary = "Créer un taux horaire facturable")
    @PostMapping(value = "/billable-rates", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createBillableRate(@PathVariable UUID companyId,
                                                      @CurrentUser UUID userId,
                                                      @Valid @RequestBody CreateBillableRateRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBillableRate(companyId, req));
    }

    @Operation(summary = "Créer une entrée de feuille de temps")
    @PostMapping(value = "/timesheet-entries", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TimesheetEntryResponse> createTimesheetEntry(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateTimesheetEntryRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTimesheetEntry(companyId, req));
    }

    @Operation(summary = "Approuver une entrée de temps",
        description = "Seules les entrées approuvées ET billables sont facturables. " +
                      "<b>V7-9</b> : l'approbateur ne peut pas être le consultant qui a saisi " +
                      "l'entrée (règle des quatre yeux — déontologie cabinet).")
    @PatchMapping("/timesheet-entries/{entryId}/approve")
    public TimesheetEntryResponse approveEntry(@PathVariable UUID companyId,
                                               @PathVariable UUID entryId,
                                               @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return service.approveEntry(companyId, entryId, userId);
    }

    @Operation(summary = "WIP (travail en cours) d'un projet",
        description = "Liste les entrées approuvées, billables, non facturées, avec taux et montant total.")
    @GetMapping("/projects/{projectId}/unbilled")
    public UnbilledWip getUnbilled(@PathVariable UUID companyId,
                                   @PathVariable UUID projectId,
                                   @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return service.getUnbilled(companyId, projectId);
    }

    @Operation(summary = "Taux d'utilisation des consultants par projet (Part E3)",
        description = "Agrège par (projet, consultant) sur la période : heures saisies, " +
                      "heures facturées, heures non facturées (WIP), taux d'utilisation (%). " +
                      "Si {@code from}/{@code to} sont omis, borne inférieure = 1900-01-01 " +
                      "et borne supérieure = aujourd'hui. Le champ {@code consultant} est " +
                      "le {@code resourceUserId} (UUID) — la résolution en nom affichable " +
                      "se fait côté client.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UtilizationLine.class),
                examples = @ExampleObject(name = "3 lignes d'utilisation par projet/consultant", value = """
                    [
                      {
                        "projectId": "0192c0fc-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "projectCode": "PRJ-001",
                        "projectLabel": "Audit Boulangerie du Marché 2026",
                        "consultantId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                        "consultant": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                        "hoursLogged": 120,
                        "hoursBilled": 80,
                        "hoursUnbilled": 32,
                        "utilizationRate": 0.9333
                      },
                      {
                        "projectId": "0192c0fc-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "projectCode": "PRJ-001",
                        "projectLabel": "Audit Boulangerie du Marché 2026",
                        "consultantId": "0192a8d4-8c2d-7e8f-9f02-345678901bcd",
                        "consultant": "0192a8d4-8c2d-7e8f-9f02-345678901bcd",
                        "hoursLogged": 80,
                        "hoursBilled": 60,
                        "hoursUnbilled": 0,
                        "utilizationRate": 0.75
                      },
                      {
                        "projectId": "0192c0fc-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "projectCode": "PRJ-002",
                        "projectLabel": "Mission conseil fiscal",
                        "consultantId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                        "consultant": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                        "hoursLogged": 50,
                        "hoursBilled": 50,
                        "hoursUnbilled": 0,
                        "utilizationRate": 1.00
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/utilization")
    public List<UtilizationLine> getUtilization(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
        return service.getUtilization(companyId, from, to);
    }
}

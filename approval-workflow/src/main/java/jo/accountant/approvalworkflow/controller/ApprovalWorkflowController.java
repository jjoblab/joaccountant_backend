package jo.accountant.approvalworkflow.controller;

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
import jo.accountant.approvalworkflow.dto.CreateRuleRequest;
import jo.accountant.approvalworkflow.dto.DecisionRequest;
import jo.accountant.approvalworkflow.dto.RequestResponse;
import jo.accountant.approvalworkflow.dto.RuleResponse;
import jo.accountant.approvalworkflow.entity.ApprovalRule;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.approvalworkflow.service.ApprovalWorkflowService;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
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
 * Endpoints du workflow d'approbation (§7, §13 Phase 4).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/approval-workflow/...}.
 *
 * <p>Endpoints :
 * <ul>
 *   <li>{@code POST /rules} — créer une règle</li>
 *   <li>{@code GET /rules} — lister les règles</li>
 *   <li>{@code GET /requests?status=} — lister les demandes (filtrées par statut)</li>
 *   <li>{@code POST /requests/{id}/approve} — approuver une demande</li>
 *   <li>{@code POST /requests/{id}/reject} — rejeter une demande</li>
 * </ul>
 *
 * <p>Aucun endpoint {@code evaluate} : la méthode
 * {@link ApprovalWorkflowService#evaluate} est appelée directement par les modules Phase
 * 5/12/14 avant la transition qui rend l'action définitive — pas par l'utilisateur final.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/approval-workflow")
@Tag(name = "ApprovalWorkflow", description = "Seuils d'approbation transverses, mécanisme 'quatre yeux' (§7)")
public class ApprovalWorkflowController {

    private final ApprovalWorkflowService service;
    private final RoleChecker roleChecker;

    public ApprovalWorkflowController(ApprovalWorkflowService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Créer une règle d'approbation",
        description = "Une règle par (companyId, actionType) avec active=true. " +
                      "409 s'il existe déjà une règle active. Pour modifier : désactiver " +
                      "l'ancienne puis créer la nouvelle.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = RuleResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192c0a0-1c2d-3e4f-5a6b-7c8d9e0fabcd","companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","actionType":"JOURNAL_ENTRY_POST","thresholdAmount":50000.0000,"requiredApproverRoles":["ADMIN","OWNER"],"minApprovals":1,"active":true}
                    """))),
        @ApiResponse(responseCode = "409", description = "Règle déjà existante",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Paramètres invalides",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuleResponse> createRule(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateRuleRequest req) {

        roleChecker.ensureRole(companyId, "ADMIN");
        ApprovalRule saved = service.createRule(companyId, req.actionType(),
            req.thresholdAmount(), req.requiredApproverRoles(), req.minApprovals());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved, service));
    }

    @Operation(summary = "Lister les règles")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = RuleResponse.class)))
    @GetMapping("/rules")
    public List<RuleResponse> listRules(@PathVariable UUID companyId,
                                        @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listRules(companyId).stream()
            .map(rule -> toResponse(rule, service))
            .toList();
    }

    @Operation(summary = "Lister les demandes",
        description = "Filtrage optionnel par statut (PENDING, APPROVED, REJECTED, CANCELLED).")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = RequestResponse.class),
            examples = @ExampleObject(value = """
                [
                  {"id":"0192c0a1-2d3e-4f5a-6b7c-8d9e0fabcd10","companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","actionType":"JOURNAL_ENTRY_POST","resourceType":"JournalEntry","resourceId":"0192a8d6-3e4f-5a6b-7c8d-9e0fabcd11","amount":75000.0000,"requestedBy":"00000000-0000-0000-0000-0000000000aa","requestedAt":"2026-07-21T10:00:00Z","status":"PENDING","decidedBy":null,"decidedAt":null,"comment":null}
                ]
                """)))
    @GetMapping("/requests")
    public List<RequestResponse> listRequests(@PathVariable UUID companyId,
                                              @CurrentUser UUID userId,
                                              @RequestParam(name = "status", required = false) ApprovalStatus status) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listRequests(companyId, status).stream()
            .map(ApprovalWorkflowController::toResponse)
            .toList();
    }

    @Operation(summary = "Approuver une demande",
        description = "Règle des quatre yeux (§7) : 403 si le décideur est l'auteur de la demande. " +
                      "409 si la demande a déjà été décidée.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = RequestResponse.class))),
        @ApiResponse(responseCode = "403", description = "Auto-approbation interdite",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {"type":"https://joaccountant.dev/errors/self_approval_forbidden","title":"Forbidden","status":403,"detail":"Vous ne pouvez pas approuver/rejeter une demande que vous avez vous-même créée (règle des quatre yeux, §7).","code":"SELF_APPROVAL_FORBIDDEN"}
                    """))),
        @ApiResponse(responseCode = "404", description = "Demande introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Déjà décidée",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/requests/{requestId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RequestResponse approve(@PathVariable UUID companyId,
                                   @PathVariable UUID requestId,
                                   @CurrentUser UUID userId,
                                   @RequestBody(required = false) DecisionRequest req) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        String comment = req == null ? null : req.comment();
        ApprovalRequest saved = service.approve(companyId, requestId, userId, comment);
        return toResponse(saved);
    }

    @Operation(summary = "Rejeter une demande",
        description = "Le motif (comment) est obligatoire. Règle des quatre yeux (§7) : 403 si " +
                      "le décideur est l'auteur de la demande.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = RequestResponse.class))),
        @ApiResponse(responseCode = "403", description = "Auto-rejet interdit",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Demande introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Déjà décidée",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Motif manquant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/requests/{requestId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RequestResponse reject(@PathVariable UUID companyId,
                                  @PathVariable UUID requestId,
                                  @CurrentUser UUID userId,
                                  @Valid @RequestBody(required = false) DecisionRequest req) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        String comment = req == null ? null : req.comment();
        ApprovalRequest saved = service.reject(companyId, requestId, userId, comment);
        return toResponse(saved);
    }

    private static RuleResponse toResponse(ApprovalRule rule, ApprovalWorkflowService service) {
        return new RuleResponse(
            rule.getId(),
            rule.getCompanyId(),
            rule.getActionType(),
            rule.getThresholdAmount(),
            service.deserializeRoles(rule.getRequiredApproverRoles()),
            rule.getMinApprovals(),
            rule.isActive(),
            rule.getCreatedAt(),
            rule.getUpdatedAt()
        );
    }

    private static RequestResponse toResponse(ApprovalRequest request) {
        return new RequestResponse(
            request.getId(),
            request.getCompanyId(),
            request.getActionType(),
            request.getResourceType(),
            request.getResourceId(),
            request.getAmount(),
            request.getRequestedBy(),
            request.getRequestedAt(),
            request.getStatus(),
            request.getDecidedBy(),
            request.getDecidedAt(),
            request.getComment()
        );
    }
}

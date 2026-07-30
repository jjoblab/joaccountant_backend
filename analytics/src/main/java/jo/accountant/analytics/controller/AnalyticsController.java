package jo.accountant.analytics.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import jo.accountant.analytics.entity.AnalyticalDimensionPlan;
import jo.accountant.analytics.entity.AnalyticalDimensionValue;
import jo.accountant.analytics.service.AnalyticsService;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints des dimensions analytiques (§5, §13 Phase 5).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/analytics")
@Tag(name = "Analytics", description = "Dimensions analytiques transverses (mécanisme multi-secteur, §5)")
public class AnalyticsController {

    private final AnalyticsService service;
    private final RoleChecker roleChecker;

    public AnalyticsController(AnalyticsService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Créer un plan analytique")
    @ApiResponse(responseCode = "201",
        content = @Content(schema = @Schema(implementation = AnalyticalDimensionPlan.class)))
    @PostMapping(value = "/plans", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyticalDimensionPlan> createPlan(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreatePlanRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        AnalyticalDimensionPlan plan = service.createPlan(companyId, req.code(), req.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(plan);
    }

    @Operation(summary = "Lister les plans analytiques")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = AnalyticalDimensionPlan.class)))
    @GetMapping("/plans")
    public List<AnalyticalDimensionPlan> listPlans(@PathVariable UUID companyId,
                                                   @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listPlans(companyId);
    }

    @Operation(summary = "Créer une valeur dans un plan")
    @ApiResponse(responseCode = "201",
        content = @Content(schema = @Schema(implementation = AnalyticalDimensionValue.class)))
    @PostMapping(value = "/plans/{planId}/values", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyticalDimensionValue> createValue(
        @PathVariable UUID companyId,
        @PathVariable UUID planId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateValueRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        AnalyticalDimensionValue value = service.createValue(
            companyId, planId, req.code(), req.label(), req.parentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(value);
    }

    @Operation(summary = "Lister les valeurs d'un plan")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = AnalyticalDimensionValue.class)))
    @GetMapping("/plans/{planId}/values")
    public List<AnalyticalDimensionValue> listValues(@PathVariable UUID companyId,
                                                     @PathVariable UUID planId,
                                                     @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listValues(companyId, planId);
    }

    /** Corps de requête pour créer un plan. */
    public record CreatePlanRequest(@NotBlank String code, @NotBlank String label) {}

    /** Corps de requête pour créer une valeur. */
    public record CreateValueRequest(@NotBlank String code, @NotBlank String label, UUID parentId) {}
}

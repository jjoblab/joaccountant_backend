package jo.accountant.fixedassets.controller;

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
import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.fixedassets.dto.AddAssetComponentRequest;
import jo.accountant.fixedassets.dto.AssetComponentResponse;
import jo.accountant.fixedassets.dto.AssetResponse;
import jo.accountant.fixedassets.dto.CreateAssetRequest;
import jo.accountant.fixedassets.dto.DisposeAssetRequest;
import jo.accountant.fixedassets.dto.ImpairmentTestResult;
import jo.accountant.fixedassets.dto.ScheduleLineResponse;
import jo.accountant.fixedassets.service.FixedAssetsService;
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
 * Endpoints des immobilisations (§13 Phase 8).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/fixed-assets/...}.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/fixed-assets")
@Tag(name = "FixedAssets", description = "Immobilisations, amortissements, cession (§13 Phase 8)")
public class FixedAssetsController {

    private final FixedAssetsService service;
    private final RoleChecker roleChecker;
    private final ModuleAccessGuard moduleAccessGuard;

    public FixedAssetsController(FixedAssetsService service, RoleChecker roleChecker,
                                ModuleAccessGuard moduleAccessGuard) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.moduleAccessGuard = moduleAccessGuard;
    }

    @Operation(summary = "Lister les immobilisations",
        description = "Retourne toutes les immobilisations actives de l'entreprise (statut ACTIVE). " +
                      "Les actifs DISPOSED ne sont pas inclus — voir l'historique de cession pour cela.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AssetResponse.class),
                examples = @ExampleObject(name = "Liste de 2 actifs", value = """
                    [
                      {
                        "id": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "label": "Véhicule Toyota Hilux",
                        "acquisitionDate": "2026-01-15",
                        "acquisitionCost": 5000000.0000,
                        "usefulLifeMonths": 60,
                        "residualValue": 500000.0000,
                        "depreciationMethod": "STRAIGHT_LINE",
                        "status": "ACTIVE",
                        "cumulativeDepreciation": 83333.33,
                        "impairmentAmount": 0,
                        "components": [],
                        "createdAt": "2026-01-15T09:00:00Z",
                        "updatedAt": "2026-02-28T10:00:00Z"
                      },
                      {
                        "id": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "label": "Bâtiment siège social",
                        "acquisitionDate": "2024-07-01",
                        "acquisitionCost": 250000.0000,
                        "usefulLifeMonths": 600,
                        "residualValue": 50000.0000,
                        "depreciationMethod": "STRAIGHT_LINE",
                        "status": "ACTIVE",
                        "cumulativeDepreciation": 15000.00,
                        "impairmentAmount": 0,
                        "components": [
                          {"id": "0192c0b0-1c2d-3e4f-5a6b-7c8d9e0fabcd", "code": "STRUCT", "label": "Structure bâtiment"}
                        ],
                        "createdAt": "2024-07-01T08:00:00Z",
                        "updatedAt": "2026-02-28T10:00:00Z"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public List<AssetResponse> listAssets(@PathVariable UUID companyId,
                                          @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.listAssets(companyId);
    }

    @Operation(summary = "Créer une immobilisation (auto-génère l'échéancier)",
        description = "Génère automatiquement l'échéancier d'amortissement à la création : " +
                      "une ligne par mois pour usefulLifeMonths mois. " +
                      "STRAIGHT_LINE : montant constant = (coût − résiduel) / mois. " +
                      "DECLINING_BALANCE : taux dégressif × solde net.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = AssetResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd","label":"Véhicule Toyota","acquisitionDate":"2026-01-15","acquisitionCost":5000000.0000,"usefulLifeMonths":60,"residualValue":500000.0000,"depreciationMethod":"STRAIGHT_LINE","status":"ACTIVE","cumulativeDepreciation":0}
                    """))),
        @ApiResponse(responseCode = "422", description = "Paramètres invalides",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssetResponse> createAsset(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateAssetRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        AssetResponse asset = service.createAsset(companyId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(asset);
    }

    @Operation(summary = "Récupérer une immobilisation par ID",
        description = "Retourne l'immobilisation avec son échéancier résumé et la liste de ses composants IAS 16.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AssetResponse.class),
                examples = @ExampleObject(name = "Bâtiment avec composants", value = """
                    {
                      "id": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "label": "Bâtiment siège social",
                      "acquisitionDate": "2024-07-01",
                      "acquisitionCost": 250000.0000,
                      "usefulLifeMonths": 600,
                      "residualValue": 50000.0000,
                      "depreciationMethod": "STRAIGHT_LINE",
                      "status": "ACTIVE",
                      "cumulativeDepreciation": 15000.00,
                      "impairmentAmount": 0,
                      "components": [
                        {
                          "id": "0192c0b0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "assetId": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                          "code": "STRUCT",
                          "label": "Structure bâtiment",
                          "acquisitionCost": 250000.00,
                          "usefulLifeYears": 50,
                          "residualValue": 50000.00,
                          "depreciationMethod": "STRAIGHT_LINE",
                          "createdAt": "2024-07-01T08:00:00Z",
                          "updatedAt": "2024-07-01T08:00:00Z"
                        }
                      ],
                      "createdAt": "2024-07-01T08:00:00Z",
                      "updatedAt": "2026-02-28T10:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/not-found",
                      "title": "Ressource introuvable",
                      "status": 404,
                      "detail": "Aucune immobilisation avec l'id 0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde pour cette entreprise.",
                      "properties": {"code": "FIXED_ASSET_NOT_FOUND"}
                    }
                    """)))
    })
    @GetMapping("/{assetId}")
    public AssetResponse getAsset(@PathVariable UUID companyId,
                                  @PathVariable UUID assetId,
                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.getAsset(companyId, assetId);
    }

    @Operation(summary = "Échéancier d'amortissement",
        description = "Liste toutes les lignes de l'échéancier avec leur statut (postée ou non).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ScheduleLineResponse.class),
                examples = @ExampleObject(name = "3 premières lignes (Jan-Mar 2026)", value = """
                    [
                      {
                        "id": "0192c0d0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "assetId": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "componentId": null,
                        "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "periodDate": "2026-01-31",
                        "amount": 75000.00,
                        "cumulativeAmount": 75000.00,
                        "journalEntryId": "0192c0d1-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "postedAt": "2026-02-03T14:00:00Z",
                        "postedBy": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                        "posted": true
                      },
                      {
                        "id": "0192c0d0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "assetId": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "componentId": null,
                        "periodId": "0192a8f0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "periodDate": "2026-02-28",
                        "amount": 75000.00,
                        "cumulativeAmount": 150000.00,
                        "journalEntryId": "0192c0d1-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "postedAt": "2026-03-02T09:30:00Z",
                        "postedBy": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                        "posted": true
                      },
                      {
                        "id": "0192c0d0-3e4f-5a6b-7c8d-9e0fa1bcde02",
                        "assetId": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "componentId": null,
                        "periodId": "0192a8f0-3e4f-5a6b-7c8d-9e0fa1bcde03",
                        "periodDate": "2026-03-31",
                        "amount": 75000.00,
                        "cumulativeAmount": 225000.00,
                        "journalEntryId": null,
                        "postedAt": null,
                        "postedBy": null,
                        "posted": false
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{assetId}/schedule")
    public List<ScheduleLineResponse> getSchedule(@PathVariable UUID companyId,
                                                  @PathVariable UUID assetId,
                                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.getSchedule(companyId, assetId);
    }

    @Operation(summary = "Poster l'amortissement d'une période",
        description = "Génère une écriture avec sourceModule=FIXED_ASSETS (Débit Charge / " +
                      "Crédit Amortissement cumulé). Une seule période à la fois — jamais " +
                      "tout l'échéancier d'un coup. 409 si déjà postée ou si actif DISPOSED.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Ligne d'amortissement postée + écriture comptable générée",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ScheduleLineResponse.class),
                examples = @ExampleObject(name = "Période Janvier 2026 postée", value = """
                    {
                      "id": "0192c0d0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "assetId": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "componentId": null,
                      "periodId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "periodDate": "2026-01-31",
                      "amount": 75000.00,
                      "cumulativeAmount": 75000.00,
                      "journalEntryId": "0192c0d1-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "postedAt": "2026-02-03T14:00:00Z",
                      "postedBy": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "posted": true
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif ou période introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Période déjà postée / actif cédé — code `SCHEDULE_ALREADY_POSTED`",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/schedule-already-posted",
                      "title": "Période déjà postée",
                      "status": 409,
                      "detail": "La période 0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd a déjà été postée pour l'actif 0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd.",
                      "properties": {"code": "SCHEDULE_ALREADY_POSTED"}
                    }
                    """)))
    })
    @PostMapping("/{assetId}/post-period-depreciation")
    public ScheduleLineResponse postPeriodDepreciation(
        @PathVariable UUID companyId,
        @PathVariable UUID assetId,
        @CurrentUser UUID userId,
        @Parameter(description = "ID de la période comptable à poster", required = true,
            example = "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd")
        @RequestParam UUID periodId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.postPeriodDepreciation(companyId, assetId, periodId);
    }

    @Operation(summary = "Céder une immobilisation",
        description = "Calcule la plus/moins-value = prix de cession − (coût − amortissement cumulé). " +
                      "Génère une écriture de cession (sortie actif + reprise amortissement + " +
                      "prix de cession + plus/moins-value). Asset → DISPOSED (immuable).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Actif cédé — statut passé à DISPOSED",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AssetResponse.class),
                examples = @ExampleObject(name = "Cession avec moins-value", value = """
                    {
                      "id": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "label": "Véhicule Toyota Hilux",
                      "acquisitionCost": 5000000.0000,
                      "status": "DISPOSED",
                      "disposalDate": "2026-09-30",
                      "disposalAmount": 3500000.0000,
                      "gainOrLoss": -250000.00,
                      "cumulativeDepreciation": 750000.00
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Actif déjà cédé — code `ASSET_ALREADY_DISPOSED`",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/asset-already-disposed",
                      "title": "Actif déjà cédé",
                      "status": 409,
                      "detail": "L'actif 0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd a déjà été cédé le 2026-09-30.",
                      "properties": {"code": "ASSET_ALREADY_DISPOSED"}
                    }
                    """)))
    })
    @PostMapping(value = "/{assetId}/dispose", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AssetResponse dispose(@PathVariable UUID companyId,
                                 @PathVariable UUID assetId,
                                 @CurrentUser UUID userId,
                                 @Valid @RequestBody DisposeAssetRequest req) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.dispose(companyId, assetId, req);
    }

    // ── Finding #11 — Composants IAS 16 ──────────────────────────────────────────────────

    @Operation(summary = "Lister les composants IAS 16 d'une immobilisation",
        description = "Retourne les composants de l'asset (structure, toiture, installations...). " +
                      "Chaque composant a sa propre durée de vie et méthode d'amortissement.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AssetComponentResponse.class),
                examples = @ExampleObject(name = "3 composants d'un bâtiment", value = """
                    [
                      {
                        "id": "0192c0b0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "assetId": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "code": "STRUCT",
                        "label": "Structure bâtiment",
                        "acquisitionCost": 250000.00,
                        "usefulLifeYears": 50,
                        "residualValue": 50000.00,
                        "depreciationMethod": "STRAIGHT_LINE",
                        "createdAt": "2024-07-01T08:00:00Z",
                        "updatedAt": "2024-07-01T08:00:00Z"
                      },
                      {
                        "id": "0192c0b0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "assetId": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "code": "TOIT",
                        "label": "Toiture (charpente + couverture)",
                        "acquisitionCost": 60000.00,
                        "usefulLifeYears": 25,
                        "residualValue": 0.00,
                        "depreciationMethod": "STRAIGHT_LINE",
                        "createdAt": "2024-07-01T08:00:00Z",
                        "updatedAt": "2024-07-01T08:00:00Z"
                      },
                      {
                        "id": "0192c0b0-3e4f-5a6b-7c8d-9e0fa1bcde02",
                        "assetId": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "code": "INSTALL_ELEC",
                        "label": "Installations électriques",
                        "acquisitionCost": 35000.00,
                        "usefulLifeYears": 20,
                        "residualValue": 0.00,
                        "depreciationMethod": "STRAIGHT_LINE",
                        "createdAt": "2024-07-01T08:00:00Z",
                        "updatedAt": "2024-07-01T08:00:00Z"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{assetId}/components")
    public List<AssetComponentResponse> listComponents(@PathVariable UUID companyId,
                                                       @PathVariable UUID assetId,
                                                       @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.listComponents(companyId, assetId);
    }

    @Operation(summary = "Ajouter un composant IAS 16 à une immobilisation",
        description = "Ajoute un composant (ex. toiture, structure) à un asset existant et " +
                      "regénère l'échéancier par composant. Refusé (409) si l'échéancier a déjà " +
                      "des lignes postées.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            description = "Composant ajouté + échéancier regénéré",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AssetComponentResponse.class),
                examples = @ExampleObject(name = "Composant STRUCT créé", value = """
                    {
                      "id": "0192c0b0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "assetId": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "code": "STRUCT",
                      "label": "Structure bâtiment",
                      "acquisitionCost": 250000.00,
                      "usefulLifeYears": 50,
                      "residualValue": 50000.00,
                      "depreciationMethod": "STRAIGHT_LINE",
                      "createdAt": "2026-03-15T10:00:00Z",
                      "updatedAt": "2026-03-15T10:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Échéancier déjà posté — code `SCHEDULE_ALREADY_POSTED`",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/schedule-already-posted",
                      "title": "Échéancier déjà posté",
                      "status": 409,
                      "detail": "Impossible d'ajouter un composant : l'échéancier de l'actif 0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde a déjà des lignes postées.",
                      "properties": {"code": "SCHEDULE_ALREADY_POSTED"}
                    }
                    """))),
        @ApiResponse(responseCode = "422", description = "Code composant vide ou coût ≤ 0",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/{assetId}/components", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssetComponentResponse> addComponent(
        @PathVariable UUID companyId,
        @PathVariable UUID assetId,
        @CurrentUser UUID userId,
        @Valid @RequestBody AddAssetComponentRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        AssetComponentResponse comp = service.addComponent(companyId, assetId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(comp);
    }

    // ── Finding #11 — Test de dépréciation IAS 36 ────────────────────────────────────────

    @Operation(summary = "Tester la dépréciation IAS 36 d'une immobilisation",
        description = "Compare la VNC (coût − amortissement cumulé − dépréciation antérieure) " +
                      "avec le montant recouvrable fourni. Si VNC > recouvrable, enregistre une " +
                      "dépréciation (D 6816 / C 291) et retourne le montant + l'écriture générée.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Dépréciation enregistrée (VNC > montant recouvrable)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ImpairmentTestResult.class),
                examples = @ExampleObject(name = "Dépréciation de 5000 enregistrée", value = """
                    {
                      "assetId": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "netBookValue": 80000.00,
                      "recoverableAmount": 75000.00,
                      "impairmentAmount": 5000.00,
                      "impaired": true,
                      "journalEntryId": "0192c0e0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "testedAt": "2026-03-31T16:45:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "200",
            description = "Aucune dépréciation (VNC ≤ montant recouvrable)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ImpairmentTestResult.class),
                examples = @ExampleObject(name = "Pas de dépréciation", value = """
                    {
                      "assetId": "0192c0a6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "netBookValue": 180000.00,
                      "recoverableAmount": 200000.00,
                      "impairmentAmount": 0,
                      "impaired": false,
                      "journalEntryId": null,
                      "testedAt": "2026-03-31T16:50:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Actif introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Actif DISPOSED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{assetId}/test-impairment")
    public ImpairmentTestResult testImpairment(
        @PathVariable UUID companyId,
        @PathVariable UUID assetId,
        @CurrentUser UUID userId,
        @Parameter(description = "Montant recouvrable (le plus élevé entre la valeur d'utilité et la juste valeur nette des coûts de cession, IAS 36 §6)",
            required = true, example = "75000")
        @RequestParam BigDecimal recoverableAmount) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
        return service.testImpairment(companyId, assetId, recoverableAmount);
    }
}

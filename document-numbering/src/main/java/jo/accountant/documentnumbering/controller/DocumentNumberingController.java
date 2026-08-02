package jo.accountant.documentnumbering.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.documentnumbering.dto.CreateSequenceRequest;
import jo.accountant.documentnumbering.dto.NextNumberPreview;
import jo.accountant.documentnumbering.dto.SequenceResponse;
import jo.accountant.documentnumbering.entity.DocumentSequenceConfig;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
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
 * Endpoints de numérotation documentaire (§6, §13.
 *
 * <p>Convention d'URL (§3.8) : {@code /api/v1/companies/{companyId}/document-numbering/...}.
 * Le {@code companyId} du path est validé contre le JWT par {@code TenantClaimFilter}.
 *
 * <p>Endpoints :
 * <ul>
 * <li>{@code POST /sequences} — créer une config</li>
 * <li>{@code GET /sequences} — lister les configs</li>
 * <li>{@code GET /sequences/{documentType}/next-preview} — aperçu non consommateur</li>
 * </ul>
 *
 * <p>Aucun endpoint {@code consume} : la consommation effective d'un numéro se fait via
 * {@link DocumentNumberingService#nextNumber}, appelé directement par les modules/12/14
 * au moment de la transition qui rend le document définitif.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/document-numbering")
@Tag(name = "DocumentNumbering", description = "Génération atomique et sans trou des numéros de documents (factures, écritures, reçus)")
/**
 * Contrôleur REST DocumentNumbering.
 *
 *

 *

 *

 *

 *

 *
 * <p>Endpoints exposés :
 * <ul>
 * <li>{@code GET /api/v1/companies/{companyId}/document-numbering/sequences}</li>
 * <li>{@code POST /api/v1/companies/{companyId}/document-numbering/sequences}</li>
 * <li>{@code GET /api/v1/companies/{companyId}/document-numbering/sequences/{documentType}/next-preview}</li>
 * </ul>

 * @author jo@Dev


 */

public class DocumentNumberingController {

    private final DocumentNumberingService service;
    private final RoleChecker roleChecker;

    public DocumentNumberingController(DocumentNumberingService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Créer une configuration de séquence",
        description = "Une config par (companyId, documentType, scopeKey). 409 si elle existe déjà — " +
                      "pas d'édition soft pour éviter une incohérence de format avec les numéros déjà émis. " +
                      "Pour modifier : supprimer et recréer (avec audit).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Config créée",
            content = @Content(schema = @Schema(implementation = SequenceResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192b8a0-1c2d-3e4f-5a6b-7c8d9e0fabcd","companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","documentType":"SALES_INVOICE","scopeKey":"","prefix":"FAC","includeYear":true,"padding":6,"resetPolicy":"YEARLY","createdAt":"2026-07-21T10:00:00Z","updatedAt":"2026-07-21T10:00:00Z"}
                    """))),
        @ApiResponse(responseCode = "409", description = "Config déjà existante",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {"type":"https://joaccountant.dev/errors/sequence_config_already_exists","title":"Conflict","status":409,"detail":"A sequence config already exists for documentType=SALES_INVOICE scopeKey='' in this company","code":"SEQUENCE_CONFIG_ALREADY_EXISTS"}
                    """))),
        @ApiResponse(responseCode = "422", description = "Paramètres invalides",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/sequences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SequenceResponse> createSequence(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateSequenceRequest req) {

        roleChecker.ensureRole(companyId, "ADMIN");
        DocumentSequenceConfig saved = service.createSequence(
            companyId,
            req.documentType(),
            req.scopeKey(),
            req.prefix(),
            req.includeYear(),
            req.padding(),
            req.resetPolicy()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @Operation(summary = "Lister les configurations de séquence du tenant courant")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = SequenceResponse.class),
            examples = @ExampleObject(value = """
                [
                  {"id":"0192b8a0-1c2d-3e4f-5a6b-7c8d9e0fabcd","documentType":"JOURNAL_ENTRY","scopeKey":"VT","prefix":"VT","includeYear":true,"padding":5,"resetPolicy":"YEARLY"},
                  {"id":"0192b8a1-2d3e-4f5a-6b7c-8d9e0fabcd10","documentType":"SALES_INVOICE","scopeKey":"","prefix":"FAC","includeYear":true,"padding":6,"resetPolicy":"YEARLY"}
                ]
                """)))
    @GetMapping("/sequences")
    public List<SequenceResponse> listSequences(@PathVariable UUID companyId,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listSequences(companyId).stream()
            .map(DocumentNumberingController::toResponse)
            .toList();
    }

    @Operation(summary = "Aperçu du prochain numéro (NON consommateur)",
        description = "Calcule le prochain numéro SANS incrémenter le compteur et SANS poser de verrou. " +
                      "L'utilisateur n'a aucune garantie que ce sera le numéro réellement attribué : " +
                      "si une autre émission se produit entre l'aperçu et la validation, le numéro réel sera différent. " +
                      "C'est acceptable : l'aperçu est purement informatif (§6).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = NextNumberPreview.class),
                examples = @ExampleObject(value = """
                    {"companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","documentType":"SALES_INVOICE","scopeKey":"","periodKey":"2026","nextNumber":"FAC-2026-000143","nextValue":143}
                    """))),
        @ApiResponse(responseCode = "404", description = "Aucune config pour ce (documentType, scopeKey)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/sequences/{documentType}/next-preview")
    public NextNumberPreview previewNextNumber(
        @PathVariable UUID companyId,
        @PathVariable DocumentType documentType,
        @RequestParam(name = "scopeKey", required = false, defaultValue = "") String scopeKey,
        @RequestParam(name = "asOf", required = false) String asOfIso,
        @CurrentUser UUID userId) {

        roleChecker.ensureRole(companyId, "VIEWER");
        Instant asOf = asOfIso == null || asOfIso.isBlank()
            ? Instant.now()
            : LocalDate.parse(asOfIso).atStartOfDay(ZoneOffset.UTC).toInstant();
        return service.previewNextNumber(companyId, documentType, scopeKey, asOf);
    }

    private static SequenceResponse toResponse(DocumentSequenceConfig c) {
        return new SequenceResponse(
            c.getId(),
            c.getCompanyId(),
            c.getDocumentType(),
            c.getScopeKey(),
            c.getPrefix(),
            c.isIncludeYear(),
            c.getPadding(),
            c.getResetPolicy(),
            c.getCreatedAt(),
            c.getUpdatedAt()
        );
    }
}

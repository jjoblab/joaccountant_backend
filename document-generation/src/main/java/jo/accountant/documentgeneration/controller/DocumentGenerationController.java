package jo.accountant.documentgeneration.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.documentgeneration.dto.CreateTemplateRequest;
import jo.accountant.documentgeneration.dto.GeneratedDocumentResponse;
import jo.accountant.documentgeneration.dto.TemplateResponse;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import org.springframework.http.HttpHeaders;
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
 * Endpoints de génération de documents PDF (§8, §13 Phase 11).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/document-generation")
@Tag(name = "DocumentGeneration", description = "Rendu PDF partagé via Thymeleaf + openhtmltopdf (§8, §13 Phase 11)")
public class DocumentGenerationController {

    private final DocumentGenerationService service;
    private final RoleChecker roleChecker;

    public DocumentGenerationController(DocumentGenerationService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Créer un gabarit de document PDF")
    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TemplateResponse> createTemplate(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateTemplateRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createTemplate(companyId, req));
    }

    @Operation(summary = "Lister les gabarits")
    @GetMapping("/templates")
    public List<TemplateResponse> listTemplates(@PathVariable UUID companyId,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listTemplates(companyId);
    }

    @Operation(summary = "Récupérer un document PDF déjà généré",
        description = "Sert le contenu PDF directement. Si aucun document n'existe encore " +
                      "pour ce resourceId, 404. Le PDF est immuable — pas de régénération.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "application/pdf")),
    })
    @GetMapping("/documents/{resourceId}")
    public ResponseEntity<byte[]> getDocument(@PathVariable UUID companyId,
                                              @PathVariable UUID resourceId,
                                              @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] pdf = service.getDocumentContent(companyId, resourceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"document-" + resourceId + ".pdf\"")
            .body(pdf);
    }

    @Operation(summary = "Générer un document PDF (synchrone)",
        description = "Génère le PDF en rendant le template Thymeleaf avec les variables " +
                      "fournies, puis le convertit en PDF via openhtmltopdf. Si un PDF existe " +
                      "déjà pour ce resourceId, sert l'existant (immuable).")
    @PostMapping(value = "/documents")
    public ResponseEntity<GeneratedDocumentResponse> generateDocument(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestParam GeneratedDocumentType documentType,
        @RequestParam UUID resourceId,
        @RequestBody(required = false) Map<String, Object> variables) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        GeneratedDocumentResponse doc = service.generateDocument(
            companyId, documentType, resourceId, variables);
        return ResponseEntity.status(HttpStatus.CREATED).body(doc);
    }
}

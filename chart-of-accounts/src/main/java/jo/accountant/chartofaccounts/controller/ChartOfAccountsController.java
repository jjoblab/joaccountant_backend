package jo.accountant.chartofaccounts.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import jo.accountant.chartofaccounts.dto.AccountResponse;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.dto.DescendantsCountResponse;
import jo.accountant.chartofaccounts.dto.InitializeRequest;
import jo.accountant.chartofaccounts.dto.UpdateAccountRequest;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
 * Endpoints du plan comptable (§4, §13 Phase 3).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/chart-of-accounts/...}.
 *
 * <p>Endpoints :
 * <ul>
 *   <li>{@code POST /initialize} — génère les niveaux 1 et 2 verrouillés</li>
 *   <li>{@code GET ?format=tree|flat&search=} — liste (arbre ou à plat, avec filtre)</li>
 *   <li>{@code POST /{parentId}/children} — crée un compte enfant</li>
 *   <li>{@code PATCH /{accountId}} — met à jour un compte (renommage, activation, mapping fiscal)</li>
 *   <li>{@code GET /{accountId}/descendants-count} — nombre de descendants</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/chart-of-accounts")
@Tag(name = "ChartOfAccounts", description = "Plan comptable multi-référentiel (SYSCOHADA, PCG, PCN, PCGR, IFRS)")
public class ChartOfAccountsController {

    private static final Logger LOG = LoggerFactory.getLogger(ChartOfAccountsController.class);

    private final ChartOfAccountsService service;
    private final RoleChecker roleChecker;

    public ChartOfAccountsController(ChartOfAccountsService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Initialiser le plan comptable",
        description = "Génère les niveaux 1 (classes) et 2 (rubriques) verrouillés à partir du " +
                      "référentiel choisi. Pour les référentiels MANDATED (SYSCOHADA, PCG, PCN, " +
                      "PCGR), les classes sont issues du mandatedClassSeed du référentiel. " +
                      "Pour les référentiels FREE (IFRS), un gabarit de numérotation doit être " +
                      "fourni dans le corps de la requête. Idempotent : 409 si déjà initialisé.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = ChartOfAccountsService.InitializeResult.class),
                examples = @ExampleObject(value = """
                    {"accountingFrameworkId":"00000000-0000-0000-0000-000000000003","accountsCreated":8}
                    """))),
        @ApiResponse(responseCode = "404", description = "Référentiel introuvable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Plan déjà initialisé",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Gabarit manquant pour FREE / invalide",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/initialize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChartOfAccountsService.InitializeResult initialize(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody InitializeRequest req) {

        roleChecker.ensureRole(companyId, "ADMIN");
        return service.initialize(companyId, req.accountingFrameworkId(), req.template(), req.businessTypeCode());
    }

    @Operation(summary = "Lister les comptes",
        description = "Retourne tous les comptes du plan, en arbre (format=tree) ou à plat " +
                      "(format=flat, défaut). Le paramètre search filtre par code ou libellé " +
                      "(case-insensitive) et force le format flat.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = AccountResponse.class),
            examples = @ExampleObject(value = """
                [
                  {"id":"0192b8a0-1c2d-3e4f-5a6b-7c8d9e0fabcd","code":"1","label":"Ressources durables","level":1,"reportingClass":"CAPITAUX_PROPRES","normalBalance":"CREDIT","locked":true,"active":true,"path":"1","children":[]}
                ]
                """)))
    @GetMapping
    public List<AccountResponse> list(@PathVariable UUID companyId,
                                      @CurrentUser UUID userId,
                                      @RequestParam(name = "format", required = false, defaultValue = "flat") String format,
                                      @RequestParam(name = "search", required = false) String search) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.list(companyId, format, search);
    }

    @Operation(summary = "Créer un compte enfant",
        description = "Crée un compte enfant sous le parent donné. Le niveau est calculé " +
                      "automatiquement (parent + 1). Si le code est omis, il est auto-généré " +
                      "à la prochaine valeur disponible dans la séquence des enfants du parent.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = AccountResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192b8a1-2d3e-4f5a-6b7c-8d9e0fabcd10","parentId":"0192b8a0-1c2d-3e4f-5a6b-7c8d9e0fabcd","code":"411000","label":"Clients - Ventes de marchandises","level":3,"reportingClass":"ACTIF","normalBalance":"DEBIT","locked":false,"active":true,"isCollective":true,"path":"4.411.411000","children":null}
                    """))),
        @ApiResponse(responseCode = "404", description = "Parent introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Code déjà existant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Niveau > 4 / champs invalides",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/{parentId}/children", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> createChild(
        @PathVariable UUID companyId,
        @PathVariable UUID parentId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateChildRequest req) {

        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        AccountResponse created = service.createChild(companyId, parentId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Mettre à jour un compte",
        description = "Sémantique PATCH : seuls les champs fournis sont modifiés. " +
                      "Un compte verrouillé (locked=true) ne peut pas être modifié (409). " +
                      "La désactivation (active=false) est refusée si le compte a un solde non nul.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(responseCode = "404", description = "Compte introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Compte verrouillé / solde non nul",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping(value = "/{accountId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AccountResponse update(@PathVariable UUID companyId,
                                  @PathVariable UUID accountId,
                                  @CurrentUser UUID userId,
                                  @Valid @RequestBody UpdateAccountRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return service.update(companyId, accountId, req);
    }

    @Operation(summary = "Compter les descendants d'un compte")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = DescendantsCountResponse.class),
                examples = @ExampleObject(value = """
                    {"count":12}
                    """))),
        @ApiResponse(responseCode = "404", description = "Compte introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{accountId}/descendants-count")
    public DescendantsCountResponse descendantsCount(@PathVariable UUID companyId,
                                                     @PathVariable UUID accountId,
                                                     @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.countDescendants(companyId, accountId);
    }

    /**
     * GET /chart-of-accounts/numbering-template — retourne le gabarit de numérotation courant.
     * Stub : retourne un gabarit par défaut (L1=1, L2=2, L3=2, L4=2, spacing="-").
     */
    @Operation(summary = "Récupérer le gabarit de numérotation",
        description = "Retourne le gabarit de numérotation du plan comptable (longueurs L1-L4 + séparateur).")
    @GetMapping("/numbering-template")
    public java.util.Map<String, Object> numberingTemplate(@PathVariable UUID companyId,
                                                             @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return java.util.Map.of(
            "level1Length", 1,
            "level2Length", 2,
            "level3Length", 2,
            "level4Length", 2,
            "separator", "-"
        );
    }

    // ======================================================================
    // step2-backend — Reports Hub v2.4.0 : export CSV du plan comptable
    // ======================================================================

    @Operation(summary = "Exporter le plan comptable en CSV (Reports Hub v2.4.0)",
        description = "Export CSV de tous les comptes du plan comptable (format flat). " +
                      "Format : UTF-8 avec BOM (compatible Excel français), séparateur point-virgule, CRLF. " +
                      "Colonnes : Code;Libelle;Classe;Sous-categorie;Niveau;Sens normal;Verrouille;Actif;Collectif;Chemin;Code fiscal.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "CSV binaire (plan comptable)",
            content = @Content(mediaType = "text/csv",
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCoaCsv(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestParam(name = "format", defaultValue = "csv") String format) {
        roleChecker.ensureRole(companyId, "VIEWER");
        if (!"csv".equalsIgnoreCase(format)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Error-Reason", "UNSUPPORTED_FORMAT")
                .body(null);
        }

        List<AccountResponse> accounts = service.list(companyId, "flat", null);

        // Génération du CSV (UTF-8 BOM, séparateur ';', CRLF)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);  // BOM UTF-8 pour Excel
        String LINE_SEP = "\r\n";
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            pw.print("Code;Libelle;Classe;Sous-categorie;Niveau;Sens normal;Verrouille;Actif;Collectif;Chemin;Code fiscal" + LINE_SEP);
            for (AccountResponse a : accounts) {
                pw.print(safe(a.code()) + ";"
                    + safe(a.label()) + ";"
                    + (a.reportingClass() != null ? a.reportingClass().name() : "") + ";"
                    + (a.reportingSubcategory() != null ? a.reportingSubcategory().name() : "") + ";"
                    + a.level() + ";"
                    + (a.normalBalance() != null ? a.normalBalance().name() : "") + ";"
                    + (a.locked() ? "OUI" : "NON") + ";"
                    + (a.active() ? "OUI" : "NON") + ";"
                    + (a.isCollective() ? "OUI" : "NON") + ";"
                    + safe(a.path()) + ";"
                    + safe(a.taxMappingCode()) + LINE_SEP);
            }
        }
        byte[] csv = baos.toByteArray();
        String filename = "plan-comptable-" + companyId + ".csv";
        LOG.info("[CSV] Export plan comptable généré pour companyId={} ({} comptes, {} octets)",
            companyId, accounts.size(), csv.length);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(csv);
    }

    /** Formate une valeur nullable en chaîne vide (pour CSV). */
    private static String safe(Object o) {
        return o != null ? o.toString() : "";
    }
}

package jo.accountant.thirdparties.controller;

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
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.thirdparties.dto.AgedBalance;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.LettrageRequest;
import jo.accountant.thirdparties.dto.LettrageResponse;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.dto.ThirdPartyStatement;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.service.ThirdPartiesService;
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
 * Endpoints des tiers et du lettrage (§13 Phase 7).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/third-parties/...}.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/third-parties")
@Tag(name = "ThirdParties", description = "Clients/fournisseurs/donateurs, lettrage, balance âgée (§13 Phase 7)")
public class ThirdPartiesController {

    private final ThirdPartiesService service;
    private final RoleChecker roleChecker;

    public ThirdPartiesController(ThirdPartiesService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Lister les tiers (paginé)",
        description = "Filtrage optionnel par type (CLIENT, SUPPLIER, DONOR, EMPLOYEE, OTHER). " +
                      "Pagination via ?page=&size= (défaut 0/20, size capped à 200). " +
                      "Finding #3 — remplace la variante List<> pour éviter l'OOM sur entreprises matures.")
    @ApiResponse(responseCode = "200",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ThirdPartyResponse.class),
            examples = @ExampleObject(name = "Page de 2 tiers", value = """
                {
                  "content": [
                    {
                      "id": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "type": "CLIENT",
                      "name": "Boulangerie du Marché",
                      "collectiveAccountCode": "411000",
                      "dedicatedAccountCode": "411000001",
                      "active": true,
                      "email": "contact@boulangerie-marche.fr",
                      "address": "12 rue du Marché, 75001 Paris",
                      "siret": "12345678900012",
                      "vatNumber": "FR12345678901",
                      "nif": null,
                      "createdAt": "2026-01-15T09:00:00Z",
                      "updatedAt": "2026-02-20T14:30:00Z"
                    },
                    {
                      "id": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "type": "SUPPLIER",
                      "name": "Fournisseur Haïti SA",
                      "collectiveAccountCode": "401000",
                      "dedicatedAccountCode": "401000001",
                      "active": true,
                      "email": "contact@fournisseurht.ht",
                      "address": "Rue Lamarre 25, Pétion-Ville, Haïti",
                      "siret": null,
                      "vatNumber": null,
                      "nif": "HT-2018-12345",
                      "createdAt": "2026-03-01T08:00:00Z",
                      "updatedAt": "2026-03-15T11:00:00Z"
                    }
                  ],
                  "pageable": {"pageNumber": 0, "pageSize": 20},
                  "totalElements": 2,
                  "totalPages": 1,
                  "number": 0,
                  "size": 20,
                  "first": true,
                  "last": true,
                  "empty": false
                }
                """)))
    @GetMapping
    public org.springframework.data.domain.Page<ThirdPartyResponse> listThirdParties(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestParam(name = "type", required = false) ThirdPartyType type,
        @RequestParam(required = false, defaultValue = "0") int page,
        @RequestParam(required = false, defaultValue = "20") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // Finding #3 — PageRequest cappé à 200 (empêche l'OOM si un client demande size=10000).
        org.springframework.data.domain.Pageable pageable =
            org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        return service.listThirdParties(companyId, type, pageable);
    }

    @Operation(summary = "Récupérer un tiers par son ID",
        description = "Correction 2026-07-26 — endpoint nécessaire pour le deep-linking depuis " +
                      "les notifications mobile. Inclut les champs légaux V42 (siret, vatNumber, nif).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ThirdPartyResponse.class),
                examples = @ExampleObject(name = "Tiers client français", value = """
                    {
                      "id": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "type": "CLIENT",
                      "name": "Boulangerie du Marché",
                      "collectiveAccountCode": "411000",
                      "dedicatedAccountCode": "411000001",
                      "active": true,
                      "email": "contact@boulangerie-marche.fr",
                      "address": "12 rue du Marché, 75001 Paris",
                      "siret": "12345678900012",
                      "vatNumber": "FR12345678901",
                      "nif": null,
                      "createdAt": "2026-01-15T09:00:00Z",
                      "updatedAt": "2026-02-20T14:30:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Tiers introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/not-found",
                      "title": "Tiers introuvable",
                      "status": 404,
                      "detail": "Aucun tiers avec l'id 0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd pour cette entreprise.",
                      "properties": {"code": "THIRD_PARTY_NOT_FOUND"}
                    }
                    """)))
    })
    @GetMapping("/{thirdPartyId}")
    public ThirdPartyResponse getThirdParty(@PathVariable UUID companyId,
                                              @PathVariable UUID thirdPartyId,
                                              @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getThirdParty(companyId, thirdPartyId);
    }

    @Operation(summary = "Créer un tiers",
        description = "Si le compte collectif a isCollective=true, un compte dédié de niveau 4 " +
                      "est automatiquement généré sous le compte collectif pour ce tiers.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = ThirdPartyResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192c0a3-1c2d-3e4f-5a6b-7c8d9e0fabcd","type":"CLIENT","name":"Boutique Pétion-Ville","collectiveAccountCode":"411000","dedicatedAccountCode":"411000001","active":true}
                    """))),
        @ApiResponse(responseCode = "422", description = "Compte non collectif / nom manquant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ThirdPartyResponse> createThirdParty(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Valid @RequestBody CreateThirdPartyRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        ThirdPartyResponse tp = service.createThirdParty(companyId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(tp);
    }

    @Operation(summary = "Relevé de compte d'un tiers",
        description = "Liste toutes les écritures POSTED du tiers avec le solde lettré et " +
                      "le solde non lettré. Filtrage optionnel par plage de dates.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = ThirdPartyStatement.class))),
        @ApiResponse(responseCode = "404", description = "Tiers introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{thirdPartyId}/statement")
    public ThirdPartyStatement getStatement(@PathVariable UUID companyId,
                                            @PathVariable UUID thirdPartyId,
                                            @CurrentUser UUID userId,
                                            @RequestParam(name = "from", required = false) LocalDate from,
                                            @RequestParam(name = "to", required = false) LocalDate to) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getStatement(companyId, thirdPartyId, from, to);
    }

    @Operation(summary = "Lettrer des lignes d'écriture",
        description = "Lettrage manuel : associe un ensemble de lignes (facture + règlement, " +
                      "par exemple). FULL si somme débit = somme crédit, PARTIAL sinon. " +
                      "Code de lettrage séquentiel attribué automatiquement (A, B, C, ...).")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            description = "Lettrage créé — statut FULL si débit = crédit, PARTIAL sinon",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = LettrageResponse.class),
                examples = @ExampleObject(name = "Lettrage FULL (facture + règlement)", value = """
                    {
                      "id": "0192c0a4-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "thirdPartyId": "0192a8d3-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "matchCode": "A",
                      "status": "FULL",
                      "matchedAmount": 2000.0000,
                      "journalLineIds": [
                        "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "0192c0a5-2d3e-4f5a-6b7c-8d9e0fa1bcde"
                      ]
                    }
                    """))),
        @ApiResponse(responseCode = "422", description = "Ligne déjà lettrée / mauvais tiers",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/lettrage-conflict",
                      "title": "Ligne déjà lettrée",
                      "status": 422,
                      "detail": "La ligne 0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd est déjà lettrée (code B).",
                      "properties": {"code": "JOURNAL_LINE_ALREADY_LETTERED"}
                    }
                    """)))
    })
    @PostMapping(value = "/lettrage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LettrageResponse> lettrer(@PathVariable UUID companyId,
                                                    @CurrentUser UUID userId,
                                                    @Valid @RequestBody LettrageRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        LettrageResponse response = service.lettrer(companyId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Supprimer un lettrage (dé-lettrage)",
        description = "Les lignes redeviennent non lettrées. Utile pour corriger une erreur de lettrage.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @ApiResponse(responseCode = "204", description = "Lettrage supprimé — les lignes redeviennent non lettrées"),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/forbidden",
                      "title": "Rôle insuffisant",
                      "status": 403,
                      "detail": "La suppression de lettrage nécessite le rôle ADMIN.",
                      "properties": {"code": "INSUFFICIENT_ROLE"}
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Lettrage introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @org.springframework.web.bind.annotation.DeleteMapping("/lettrage/{lettrageId}")
    public ResponseEntity<Void> deleteLettrage(@PathVariable UUID companyId,
                                               @PathVariable UUID lettrageId,
                                               @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        service.deleteLettrage(companyId, lettrageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Suggérer des lettrages automatiques",
        description = "Propose des paires de lignes à lettrer basées sur montant identique " +
                      "et date proche (±7 jours). L'utilisateur valide manuellement via POST /lettrage.")
    @ApiResponse(responseCode = "200",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = jo.accountant.thirdparties.service.ThirdPartiesService.SuggestedMatch.class),
            examples = @ExampleObject(name = "2 suggestions de lettrage", value = """
                [
                  {
                    "line1Id": "0192c0a5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "line2Id": "0192c0a5-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                    "date1": "2026-03-15",
                    "date2": "2026-03-18",
                    "matchedAmount": 2000.0000,
                    "daysDiff": 3
                  },
                  {
                    "line1Id": "0192c0a5-3e4f-5a6b-7c8d-9e0fa1bcde02",
                    "line2Id": "0192c0a5-4f5a-6b7c-8d9e-0fa1bcde03",
                    "date1": "2026-03-20",
                    "date2": "2026-03-25",
                    "matchedAmount": 850.0000,
                    "daysDiff": 5
                  }
                ]
                """)))
    @GetMapping("/{thirdPartyId}/suggested-matches")
    public List<jo.accountant.thirdparties.service.ThirdPartiesService.SuggestedMatch> suggestMatches(
        @PathVariable UUID companyId,
        @PathVariable UUID thirdPartyId,
        @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.suggestMatches(companyId, thirdPartyId);
    }

    @Operation(summary = "Balance âgée d'un tiers",
        description = "Répartition du solde non lettré par tranche d'âge : 0-30, 31-60, 61-90, 90+ jours. " +
                      "L'âge est calculé à partir de la date d'écriture par rapport à 'asOf' (défaut: aujourd'hui).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = AgedBalance.class),
                examples = @ExampleObject(value = """
                    {"thirdPartyId":"0192c0a3-1c2d-3e4f-5a6b-7c8d9e0fabcd","asOf":"2026-12-31","bucket0to30":5000.0000,"bucket31to60":3000.0000,"bucket61to90":0,"bucket90plus":1000.0000,"totalUnlettered":9000.0000}
                    """))),
        @ApiResponse(responseCode = "404", description = "Tiers introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{thirdPartyId}/aged-balance")
    public AgedBalance getAgedBalance(@PathVariable UUID companyId,
                                      @PathVariable UUID thirdPartyId,
                                      @CurrentUser UUID userId,
                                      @RequestParam(name = "asOf", required = false) LocalDate asOf) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getAgedBalance(companyId, thirdPartyId, asOf);
    }
}

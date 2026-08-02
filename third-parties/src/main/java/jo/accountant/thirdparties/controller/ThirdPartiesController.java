package jo.accountant.thirdparties.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.documentgeneration.util.CsvEndpointHelper;
import jo.accountant.documentgeneration.util.PdfEndpointHelper;
import jo.accountant.thirdparties.dto.AgedBalance;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.LettrageListResponse;
import jo.accountant.thirdparties.dto.LettrageRequest;
import jo.accountant.thirdparties.dto.LettrageResponse;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.dto.ThirdPartyStatement;
import jo.accountant.thirdparties.entity.LettrageStatus;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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
 * Endpoints des tiers et du lettrage (§13.
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/third-parties/...}.
 
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
 * </ul>

 * @author jo@Dev


*/
@RestController
@RequestMapping("/api/v1/companies/{companyId}/third-parties")
@Tag(name = "ThirdParties", description = "Clients/fournisseurs/donateurs, lettrage, balance âgée (§13")
public class ThirdPartiesController {

 private static final Logger LOG = LoggerFactory.getLogger(ThirdPartiesController.class);

 private final ThirdPartiesService service;
 private final RoleChecker roleChecker;
 private final DocumentGenerationService documentGenerationService;

 public ThirdPartiesController(ThirdPartiesService service, RoleChecker roleChecker,
 DocumentGenerationService documentGenerationService) {
 this.service = service;
 this.roleChecker = roleChecker;
 this.documentGenerationService = documentGenerationService;
 }

 @Operation(summary = "Lister les tiers (paginé)",
 description = "Filtrage optionnel par type (CLIENT, SUPPLIER, DONOR, EMPLOYEE, OTHER). " +
 "Pagination via ?page=&size= (défaut 0/20, size capped à 200). " +
 "remplace la variante List<> pour éviter l'OOM sur entreprises matures.")
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
 // PageRequest cappé à 200 (empêche l'OOM si un client demande size=10000).
 org.springframework.data.domain.Pageable pageable =
 org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
 return service.listThirdParties(companyId, type, pageable);
 }

 @Operation(summary = "Récupérer un tiers par son ID",
 description = "Correction 2026-07-26 — endpoint nécessaire pour le deep-linking depuis " +
 "les notifications mobile. Inclut les champs légaux V53 (siret, vatNumber, nif).")
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

 // ======================================================================
 // Reports Hub : endpoint liste + PDF pour le
 // rapport LETTERING. L'URL /third-parties/lettrage (GET) retourne une page
 // JSON filtrable ; /third-parties/lettrage/pdf génère un PDF binaire via
 // DocumentGenerationService (template LETTERING_REPORT seedé par V100).
 // ======================================================================

 @Operation(summary = "Lister les lettrages (paginé, filtrable) — Reports Hub",
 description = "Retourne une page de lettrages actifs (FULL + PARTIAL — DELETED exclu) " +
 "avec le nom du tiers et le code compte dédié résolus. " +
 "Filtres optionnels : ?thirdPartyId=&from=&to=&status=&page=&size= (défaut 0/50, size capped à 200).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = LettrageListResponse.class))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/lettrage")
 public org.springframework.data.domain.Page<LettrageListResponse> getLettrageList(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @RequestParam(required = false) UUID thirdPartyId,
 @RequestParam(required = false) LocalDate from,
 @RequestParam(required = false) LocalDate to,
 @RequestParam(required = false) LettrageStatus status,
 @RequestParam(defaultValue = "0") int page,
 @RequestParam(defaultValue = "50") int size) {
 roleChecker.ensureRole(companyId, "VIEWER");
 org.springframework.data.domain.Pageable pageable =
 org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
 return service.listLettrages(companyId, thirdPartyId, from, to, status, pageable);
 }

 @Operation(summary = "Générer la liste des lettrages en PDF (Reports Hub)",
 description = "Rendu PDF de la liste des lettrages via :document-generation (template LETTERING_REPORT). " +
 "Sert un PDF binaire en attachment. Délègue au même service métier que GET /lettrage.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "PDF binaire (liste des lettrages)",
 content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/lettrage/pdf")
 public ResponseEntity<byte[]> getLettragePdf(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @RequestParam(required = false) UUID thirdPartyId,
 @RequestParam(required = false) LocalDate from,
 @RequestParam(required = false) LocalDate to,
 @RequestParam(required = false) LettrageStatus status) {
 roleChecker.ensureRole(companyId, "VIEWER");

 // Paginer large pour le PDF (le PDF agrège tous les lettrages filtrés — on cap à 1000).
 org.springframework.data.domain.Pageable pageable =
 org.springframework.data.domain.PageRequest.of(0, 1000);
 org.springframework.data.domain.Page<LettrageListResponse> result =
 service.listLettrages(companyId, thirdPartyId, from, to, status, pageable);

 // Agréger les totaux pour le résumé du PDF.
 int totalLettrages = (int) result.getTotalElements();
 int totalFull = 0;
 int totalPartial = 0;
 BigDecimal totalMatchedAmount = BigDecimal.ZERO;
 for (LettrageListResponse line : result.getContent()) {
 if (line.status() == LettrageStatus.FULL) totalFull++;
 else if (line.status() == LettrageStatus.PARTIAL) totalPartial++;
 if (line.matchedAmount() != null) totalMatchedAmount = totalMatchedAmount.add(line.matchedAmount());
 }

 Map<String, Object> variables = new HashMap<>();
 variables.put("companyName", "");
 variables.put("from", from != null ? from.toString() : "debut");
 variables.put("to", to != null ? to.toString() : "fin");
 variables.put("generationDate", LocalDate.now().toString());
 variables.put("totalLettrages", totalLettrages);
 variables.put("totalFull", totalFull);
 variables.put("totalPartial", totalPartial);
 variables.put("totalMatchedAmount", totalMatchedAmount.toString());
 variables.put("lines", result.getContent());

 String periodLabel = (from != null ? from.toString() : "debut") + "_" + (to != null ? to.toString() : "fin");
 String filename = "lettrage-" + companyId + "-" + periodLabel + ".pdf";
 ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
 documentGenerationService, companyId, GeneratedDocumentType.LETTERING_REPORT, variables, filename);
 LOG.info("[PDF] Lettrage généré pour companyId={} période={} ({} lettrages, {} octets)",
 companyId, periodLabel, totalLettrages, response.getBody().length);
 return response;
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

 // ======================================================================
 // Reports Hub : export CSV des tiers
 // ======================================================================

 @Operation(summary = "Exporter les tiers en CSV (Reports Hub)",
 description = "Export CSV de tous les tiers (clients, fournisseurs, donateurs, etc.). " +
 "Format : UTF-8 avec BOM (compatible Excel français), séparateur point-virgule, CRLF. " +
 "Colonnes : Type;Nom;Email;Adresse;NIF;SIRET;TVA;Compte collectif;Compte dedie;Actif.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "CSV binaire (tiers)",
 content = @Content(mediaType = "text/csv",
 schema = @Schema(type = "string", format = "binary"))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/export")
 public ResponseEntity<byte[]> exportThirdPartiesCsv(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @RequestParam(name = "format", defaultValue = "csv") String format) {
 roleChecker.ensureRole(companyId, "VIEWER");
 if (!"csv".equalsIgnoreCase(format)) {
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .header("X-Error-Reason", "UNSUPPORTED_FORMAT")
 .body(null);
 }

 // Récupérer tous les tiers — on pagine par 200 jusqu'à épuisement.
 List<ThirdPartyResponse> all = new ArrayList<>();
 int page = 0;
 int size = 200;
 while (true) {
 org.springframework.data.domain.Page<ThirdPartyResponse> p =
 service.listThirdParties(companyId, null, PageRequest.of(page, size));
 all.addAll(p.getContent());
 if (!p.hasNext()) break;
 page++;
 if (page > 5000) break; // safety net — 1_000_000 tiers max
 }

 // Génération du CSV (séparateur ';', CRLF) — le BOM UTF-8 et les headers sont
 //ajoutés par CsvEndpointHelper (0-task8).
 StringBuilder sb = new StringBuilder();
 String LINE_SEP = "\r\n";
 sb.append("Type;Nom;Email;Adresse;NIF;SIRET;TVA;Compte collectif;Compte dedie;Actif").append(LINE_SEP);
 for (ThirdPartyResponse tp : all) {
 sb.append(tp.type() != null ? tp.type().name() : "").append(";")
 .append(safe(tp.name())).append(";")
 .append(safe(tp.email())).append(";")
 .append(safe(tp.address())).append(";")
 .append(safe(tp.nif())).append(";")
 .append(safe(tp.siret())).append(";")
 .append(safe(tp.vatNumber())).append(";")
 .append(safe(tp.collectiveAccountCode())).append(";")
 .append(safe(tp.dedicatedAccountCode())).append(";")
 .append(tp.active() ? "OUI" : "NON").append(LINE_SEP);
 }
 String filename = "tiers-" + companyId + ".csv";
 ResponseEntity<byte[]> response = CsvEndpointHelper.buildCsvResponse(sb.toString(), filename);
 LOG.info("[CSV] Export tiers généré pour companyId={} ({} tiers, {} octets)",
 companyId, all.size(), response.getBody().length);
 return response;
 }

 /** Formate une valeur nullable en chaîne vide (pour CSV). */
 private static String safe(Object o) {
 return o != null ? o.toString() : "";
 }
}

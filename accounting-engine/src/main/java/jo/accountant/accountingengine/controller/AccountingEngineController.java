package jo.accountant.accountingengine.controller;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalRequest;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.dto.KeysetPage;
import jo.accountant.accountingengine.dto.LedgerLine;
import jo.accountant.accountingengine.dto.TrialBalanceLine;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.Journal;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.documentgeneration.entity.DocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.documentgeneration.util.CsvEndpointHelper;
import jo.accountant.documentgeneration.util.PdfEndpointHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints du moteur comptable (§13 Phase 5).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/accounting-engine/...}.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/accounting-engine")
@Tag(name = "AccountingEngine", description = "Moteur comptable — écritures, journal, grand livre, balance (§13 Phase 5)")
public class AccountingEngineController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountingEngineController.class);

    private final AccountingEngineService service;
    private final jo.accountant.core.port.ApproverEmailResolverPort approverEmailResolver;
    private final jo.accountant.core.security.RoleChecker roleChecker;
    private final DocumentGenerationService documentGenerationService;

    public AccountingEngineController(AccountingEngineService service,
                                      jo.accountant.core.port.ApproverEmailResolverPort approverEmailResolver,
                                      jo.accountant.core.security.RoleChecker roleChecker,
                                      DocumentGenerationService documentGenerationService) {
        this.service = service;
        this.approverEmailResolver = approverEmailResolver;
        this.roleChecker = roleChecker;
        this.documentGenerationService = documentGenerationService;
    }

    // --- Exercices & périodes ---

    @Operation(summary = "Créer un exercice fiscal (génère 12 périodes mensuelles)")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = FiscalYear.class))),
        @ApiResponse(responseCode = "422", description = "Dates invalides",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/fiscal-years", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FiscalYear> createFiscalYear(@PathVariable UUID companyId,
                                                       @CurrentUser UUID userId,
                                                       @Valid @RequestBody CreateFiscalYearRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        FiscalYear fy = service.createFiscalYear(companyId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(fy);
    }

    @Operation(summary = "Verrouiller un exercice (LOCKED → toutes les périodes verrouillées)")
    @PatchMapping("/fiscal-years/{fiscalYearId}/lock")
    public FiscalYear lockFiscalYear(@PathVariable UUID companyId,
                                     @PathVariable UUID fiscalYearId,
                                     @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return service.lockFiscalYear(companyId, fiscalYearId);
    }

    @Operation(summary = "Lister les exercices fiscaux de l'entreprise")
    @GetMapping("/fiscal-years")
    public List<FiscalYear> listFiscalYears(@PathVariable UUID companyId,
                                              @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listFiscalYears(companyId);
    }

    @Operation(summary = "Récupérer un exercice fiscal par ID")
    @GetMapping("/fiscal-years/{fiscalYearId}")
    public FiscalYear getFiscalYear(@PathVariable UUID companyId,
                                      @PathVariable UUID fiscalYearId,
                                      @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getFiscalYear(companyId, fiscalYearId);
    }

    @Operation(summary = "Lister les périodes d'un exercice fiscal")
    @GetMapping("/fiscal-years/{fiscalYearId}/periods")
    public List<FiscalPeriod> listFiscalPeriods(@PathVariable UUID companyId,
                                                  @PathVariable UUID fiscalYearId,
                                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listFiscalPeriods(companyId, fiscalYearId);
    }

    @Operation(summary = "Clôturer un exercice (génère et poste les écritures de clôture)",
        description = "Calcule le résultat net (Produits − Charges) et génère une écriture " +
                      "qui solde les comptes de produits/charges contre le compte de résultat " +
                      "(12 ou 110). Le bilan devient équilibré après cette opération.")
    @PostMapping("/fiscal-years/{fiscalYearId}/close")
    public JournalEntryResponse closeFiscalYear(@PathVariable UUID companyId,
                                                @PathVariable UUID fiscalYearId,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");  // S1 fix
        return service.closeFiscalYear(companyId, fiscalYearId);
    }

    @Operation(summary = "Verrouiller une période")
    @PatchMapping("/fiscal-periods/{periodId}/lock")
    public FiscalPeriod lockFiscalPeriod(@PathVariable UUID companyId,
                                         @PathVariable UUID periodId,
                                         @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return service.lockFiscalPeriod(companyId, periodId);
    }

    // --- Journaux ---

    @Operation(summary = "Créer un journal (ex. VT, AC, BQ, OD)")
    @PostMapping(value = "/journals", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Journal> createJournal(@PathVariable UUID companyId,
                                                 @CurrentUser UUID userId,
                                                 @Valid @RequestBody CreateJournalRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        Journal journal = service.createJournal(companyId, req.code(), req.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(journal);
    }

    // --- Écritures ---

    @Operation(summary = "Créer une écriture en brouillon (DRAFT)",
        description = "L'en-tête Idempotency-Key est obligatoire (§3.10). Rejouer la même clé " +
                      "renvoie l'écriture existante, jamais de doublon. La écriture est créée en " +
                      "DRAFT — pas encore de reference. Le postage se fait via POST /{id}/post.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192c0a2-3e4f-5a6b-7c8d-9e0fabcd12","companyId":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","journalCode":"VT","entryDate":"2026-07-15","description":"Facture de vente 2026-0142 — Boutique Pétion-Ville","status":"DRAFT","sourceModule":"MANUAL","idempotencyKey":"abc-123","lines":[{"accountCode":"411000","debit":11500.00,"credit":0,"lineNumber":1},{"accountCode":"701000","debit":0,"credit":10000.00,"lineNumber":2},{"accountCode":"443000","debit":0,"credit":1500.00,"lineNumber":3}],"totalDebit":11500.00,"totalCredit":11500.00}
                    """))),
        @ApiResponse(responseCode = "422", description = "Écriture déséquilibrée / compte inexistant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Période verrouillée / exercice clôturé",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/journal-entries", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JournalEntryResponse> createJournalEntry(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CreateJournalEntryRequest req) {

        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        JournalEntryResponse response = service.createJournalEntry(companyId, idempotencyKey, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Lister les écritures de l'entreprise",
        description = "Endpoint historique non paginé — conserve pour rétro-compat. " +
                      "Préférer GET /journal-entries/paged qui supporte la pagination (audit M8).")
    @GetMapping("/journal-entries")
    public List<JournalEntryResponse> listJournalEntries(@PathVariable UUID companyId,
                                                         @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listJournalEntries(companyId);
    }

    @Operation(summary = "Lister les écritures de l'entreprise (paginé, audit M8)",
        description = "Retourne une page d'écritures. Paramètres : ?page=0&size=50&sort=entryDate,desc. " +
                      "Taille max 200 (limité par Spring Data). Préférer cet endpoint au GET /journal-entries " +
                      "non paginé pour éviter les timeout sur entreprise avec historique.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = JournalEntryResponse.class),
                examples = @ExampleObject(name = "Page de 3 écritures", value = """
                    {
                      "content": [
                        {
                          "id": "0192c0a2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "journalCode": "VT",
                          "entryDate": "2026-03-15",
                          "reference": "VT-2026-0142",
                          "description": "Facture de vente FAC-2026-0001 — Boulangerie du Marché",
                          "status": "POSTED",
                          "sourceModule": "INVOICING",
                          "totalDebit": 1200.00,
                          "totalCredit": 1200.00
                        },
                        {
                          "id": "0192c0a2-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "journalCode": "AC",
                          "entryDate": "2026-03-10",
                          "reference": "AC-2026-0058",
                          "description": "Facture d'achat ACH-2026-0001 — Fournisseur Haïti SA",
                          "status": "POSTED",
                          "sourceModule": "PURCHASING",
                          "totalDebit": 1200.00,
                          "totalCredit": 1200.00
                        },
                        {
                          "id": "0192c0a2-3e4f-5a6b-7c8d-9e0fa1bcde02",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "journalCode": "OD",
                          "entryDate": "2026-03-31",
                          "reference": null,
                          "description": "Amortissement Mars 2026 — Véhicule Toyota",
                          "status": "DRAFT",
                          "sourceModule": "FIXED_ASSETS",
                          "totalDebit": 75000.00,
                          "totalCredit": 75000.00
                        }
                      ],
                      "totalElements": 3,
                      "totalPages": 1,
                      "number": 0,
                      "size": 50,
                      "first": true,
                      "last": true,
                      "empty": false
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/journal-entries/paged")
    public org.springframework.data.domain.Page<JournalEntryResponse> listJournalEntriesPaged(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        if (size > 200) size = 200;  // hard cap
        return service.listJournalEntries(companyId,
            org.springframework.data.domain.PageRequest.of(page, size));
    }

    @Operation(summary = "Récupérer une écriture par son ID",
        description = "Correction 2026-07-26 — endpoint nécessaire pour le deep-linking depuis " +
                      "les notifications mobile. Avant, le mobile ne pouvait récupérer une " +
                      "écriture qu'en parcourant le cache local, ce qui échouait si l'écriture " +
                      "n'avait pas été pré-chargée.")
    @GetMapping("/journal-entries/{entryId}")
    public JournalEntryResponse getJournalEntry(@PathVariable UUID companyId,
                                                  @PathVariable UUID entryId,
                                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getJournalEntry(companyId, entryId);
    }

    @Operation(summary = "Rechercher des écritures avec filtres (audit M8 — version étendue)",
        description = "Recherche filtrée et paginée des écritures. Tous les filtres sont optionnels " +
                      "et combinés par AND. Paramètres : " +
                      "?from=2024-01-01&to=2024-12-31&journalCode=VT&sourceModule=INVOICING&status=POSTED" +
                      "&page=0&size=50. " +
                      "Taille max 200. Tri par défaut : entryDate DESC. " +
                      "Cet endpoint est recommandé pour l'application mobile — il évite de charger " +
                      "toutes les écritures et permet de filtrer par journal, module source, statut " +
                      "ou plage de dates.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = JournalEntryResponse.class),
                examples = @ExampleObject(name = "Recherche filtrée (POSTED + INVOICING + Mars 2026)", value = """
                    {
                      "content": [
                        {
                          "id": "0192c0a2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "journalCode": "VT",
                          "entryDate": "2026-03-15",
                          "reference": "VT-2026-0142",
                          "description": "Facture de vente FAC-2026-0001 — Boulangerie du Marché",
                          "status": "POSTED",
                          "sourceModule": "INVOICING",
                          "totalDebit": 1200.00,
                          "totalCredit": 1200.00
                        }
                      ],
                      "totalElements": 1,
                      "totalPages": 1,
                      "number": 0,
                      "size": 50,
                      "first": true,
                      "last": true,
                      "empty": false
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/journal-entries/search")
    public org.springframework.data.domain.Page<JournalEntryResponse> searchJournalEntries(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String journalCode,
            @org.springframework.web.bind.annotation.RequestParam(required = false) jo.accountant.accountingengine.entity.JournalEntrySourceModule sourceModule,
            @org.springframework.web.bind.annotation.RequestParam(required = false) jo.accountant.accountingengine.entity.JournalEntryStatus status,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        if (size > 200) size = 200;
        return service.searchJournalEntries(companyId, from, to, journalCode, sourceModule, status,
            org.springframework.data.domain.PageRequest.of(page, size));
    }

    @Operation(summary = "Keyset pagination des écritures (R-41 — lot-F1-code-arch)",
        description = "Pagination par curseur (afterEntryDate, afterId) — alternative à " +
                      "GET /journal-entries/paged pour les entreprises avec un volume important " +
                      "(10M+ d'écritures). Sur les pages profondes, la latence est constante " +
                      "(~1-5ms) au lieu de croître linéairement avec l'OFFSET. " +
                      "Usage : <ol>" +
                      "<li>Première page : ?size=50 (afterEntryDate et afterId omis).</li>" +
                      "<li>Pages suivantes : ?afterEntryDate={nextAfterEntryDate}&afterId={nextAfterId}&size=50 " +
                      "tant que hasNext=true.</li>" +
                      "</ol>" +
                      "L'endpoint OFFSET /journal-entries/paged est conservé pour backward compat " +
                      "(et pour les filtres, qui ne sont pas supportés par le keyset en V1).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = KeysetPage.class),
                examples = @ExampleObject(name = "Page keyset (50 écritures + curseur suivant)", value = """
                    {
                      "content": [
                        {
                          "id": "0192c0a2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "journalCode": "VT",
                          "entryDate": "2026-03-15",
                          "reference": "VT-2026-0142",
                          "description": "Facture de vente FAC-2026-0001",
                          "status": "POSTED",
                          "sourceModule": "INVOICING",
                          "totalDebit": 1200.00,
                          "totalCredit": 1200.00
                        }
                      ],
                      "nextAfterEntryDate": "2026-03-15",
                      "nextAfterId": "0192c0a2-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "hasNext": true
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/journal-entries/keyset")
    public KeysetPage<JournalEntryResponse> listJournalEntriesKeyset(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate afterEntryDate,
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID afterId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        if (size > 200) size = 200;
        return service.getKeysetPage(companyId, afterEntryDate, afterId, size);
    }

    @Operation(summary = "Poster une écriture (DRAFT → POSTED ou PENDING_APPROVAL)",
        description = "Déclenche l'évaluation du seuil d'approbation via approval-workflow. " +
                      "Si auto-approved → génère le reference via document-numbering et passe à POSTED. " +
                      "Sinon → PENDING_APPROVAL (reference attribué après approbation). " +
                      "Les emails des approbateurs éligibles sont résolus automatiquement via " +
                      "ApproverEmailResolverPort.")
    @PostMapping("/journal-entries/{entryId}/post")
    public JournalEntryResponse postJournalEntry(@PathVariable UUID companyId,
                                                 @PathVariable UUID entryId,
                                                 @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");  // S1 fix
        List<String> approverEmails = approverEmailResolver != null
            ? approverEmailResolver.resolveEmailsByRoles(companyId, List.of("ADMIN", "OWNER"))
            : List.of();
        return service.postJournalEntry(companyId, entryId, approverEmails);
    }

    @Operation(summary = "Contre-passer une écriture POSTED",
        description = "Crée une nouvelle écriture inversée (débit ↔ crédit permutés), marquée " +
                      "POSTED avec sourceModule=REVERSAL. L'originale passe à VOIDED mais conserve " +
                      "son numéro (règle de numérotation sans trou, §6).")
    @PostMapping("/journal-entries/{entryId}/reverse")
    public JournalEntryResponse reverseJournalEntry(@PathVariable UUID companyId,
                                                    @PathVariable UUID entryId,
                                                    @CurrentUser UUID userId,
                                                    @RequestParam(name = "reason", required = false) String reason) {
        roleChecker.ensureRole(companyId, "ADMIN");  // S1 fix
        return service.reverseJournalEntry(companyId, entryId, reason);
    }

    // --- Lectures ---

    @Operation(summary = "Grand livre (filtré par compte et plage de dates ou exercice fiscal)",
        description = "Filtres disponibles : <ul>" +
                      "<li><code>?fiscalYearId=</code> (UUID) — résout l'exercice via le service " +
                      "et utilise ses bornes start/end comme plage de dates. Prévalence sur " +
                      "<code>from</code>/<code>to</code>.</li>" +
                      "<li><code>?from=&to=</code> (LocalDate) — plage de dates explicite. " +
                      "Optionnels depuis la restructuration 2026-07-25 (suite 4).</li>" +
                      "<li>Si ni <code>fiscalYearId</code> ni <code>from</code>/<code>to</code> " +
                      "n'est fourni, le service résout l'exercice par défaut (OPEN contenant " +
                      "aujourd'hui, sinon le dernier OPEN).</li>" +
                      "</ul>")
    @GetMapping("/ledger")
    public List<LedgerLine> getLedger(@PathVariable UUID companyId,
                                      @CurrentUser UUID userId,
                                      @RequestParam UUID accountId,
                                      @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
                                      @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
                                      @org.springframework.web.bind.annotation.RequestParam(required = false) UUID fiscalYearId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // fiscalYearId explicite → résoudre l'exercice via le service et utiliser ses bornes
        // comme from/to (prévalence sur les from/to explicites).
        if (fiscalYearId != null) {
            java.util.Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, fiscalYearId);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
            }
        }
        // Ni fiscalYearId ni from/to → laisser le service résoudre l'exercice par défaut
        // (OPEN contenant aujourd'hui, sinon le dernier OPEN, sinon vide).
        if (from == null && to == null) {
            java.util.Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, null);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
            }
        }
        return service.getLedger(companyId, accountId, from, to);
    }

    @Operation(summary = "Balance générale (filtrée par exercice fiscal explicite ou dates)",
        description = "Filtres disponibles : <ul>" +
                      "<li><code>?fiscalYearId=</code> (UUID) — résout l'exercice via le service " +
                      "et utilise ses bornes start/end comme plage de dates. Prévalence sur " +
                      "<code>from</code>/<code>to</code>.</li>" +
                      "<li><code>?from=&to=</code> (LocalDate) — plage de dates explicite.</li>" +
                      "<li>Si ni <code>fiscalYearId</code> ni <code>from</code>/<code>to</code> " +
                      "n'est fourni, le service résout l'exercice par défaut (OPEN contenant " +
                      "aujourd'hui, sinon le dernier OPEN).</li>" +
                      "</ul>")
    @GetMapping("/trial-balance")
    public List<TrialBalanceLine> getTrialBalance(@PathVariable UUID companyId,
                                                  @CurrentUser UUID userId,
                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) UUID fiscalYearId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // fiscalYearId explicite → résoudre l'exercice et utiliser ses bornes comme from/to.
        if (fiscalYearId != null) {
            java.util.Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, fiscalYearId);
            if (fy.isPresent()) {
                return service.getTrialBalance(companyId,
                    fy.get().getStartDate(), fy.get().getEndDate());
            }
            // Exercice introuvable → on retombe sur from/to (probablement nulls).
            return service.getTrialBalance(companyId, from, to);
        }
        // from/to explicites → filtrer par plage de dates.
        if (from != null || to != null) {
            return service.getTrialBalance(companyId, from, to);
        }
        // Aucun filtre → laisser le service résoudre l'exercice par défaut.
        return service.getTrialBalance(companyId);
    }

    @Operation(summary = "Activer un exercice fiscal",
        description = "Passe l'exercice à statut=OPEN. Les écritures peuvent ensuite y être saisies.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = FiscalYear.class),
                examples = @ExampleObject(name = "Exercice 2026 activé (statut OPEN)", value = """
                    {
                      "id": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "startDate": "2026-01-01",
                      "endDate": "2026-12-31",
                      "status": "OPEN",
                      "label": "Exercice 2026"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Exercice introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/fiscal-years/{fiscalYearId}/activate")
    public FiscalYear activateFiscalYear(@PathVariable UUID companyId,
                                          @PathVariable UUID fiscalYearId,
                                          @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return service.activateFiscalYear(companyId, fiscalYearId);
    }

    @Operation(summary = "Récupérer l'exercice fiscal actif",
        description = "Retourne l'exercice fiscal actuellement OPEN de l'entreprise (résout automatiquement l'exercice contenant la date du jour).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = FiscalYear.class),
                examples = @ExampleObject(name = "Exercice 2026 actif", value = """
                    {
                      "id": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "startDate": "2026-01-01",
                      "endDate": "2026-12-31",
                      "status": "OPEN",
                      "label": "Exercice 2026"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Aucun exercice actif trouvé pour cette entreprise",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/no-active-fiscal-year",
                      "title": "Aucun exercice actif",
                      "status": 404,
                      "detail": "Aucun exercice fiscal OPEN trouvé pour l'entreprise 0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd. Créer un exercice via POST /fiscal-years.",
                      "properties": {"code": "NO_ACTIVE_FISCAL_YEAR"}
                    }
                    """)))
    })
    @GetMapping("/fiscal-years/active")
    @java.lang.SuppressWarnings("deprecation")  // v2.5.2 — endpoint déprécié, garde rétro-compat
    public FiscalYear getActiveFiscalYear(@PathVariable UUID companyId,
                                           @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getActiveFiscalYear(companyId);
    }

    // ======================================================================
    // step2-backend — Reports Hub v2.4.0 : endpoints PDF dédiés + export CSV
    // ======================================================================

    @Operation(summary = "Générer la balance générale en PDF (Reports Hub v2.4.0)",
        description = "Rendu PDF de la balance générale via :document-generation (template TRIAL_BALANCE_REPORT). " +
                      "Délègue au même service métier que GET /trial-balance. " +
                      "Filtres : ?fiscalYearId= (UUID) ou ?from=&to= (LocalDate) — voir GET /trial-balance.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "PDF binaire (balance générale)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/trial-balance/pdf")
    public ResponseEntity<byte[]> getTrialBalancePdf(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
        @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
        @org.springframework.web.bind.annotation.RequestParam(required = false) UUID fiscalYearId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // Réutiliser la même logique de résolution d'exercice que le endpoint JSON.
        List<TrialBalanceLine> lines;
        String periodLabel;
        if (fiscalYearId != null) {
            Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, fiscalYearId);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
                lines = service.getTrialBalance(companyId, from, to);
                periodLabel = "exercice " + fy.get().getStartDate() + "_" + fy.get().getEndDate();
            } else {
                lines = service.getTrialBalance(companyId, from, to);
                periodLabel = (from != null ? from.toString() : "") + "_" + (to != null ? to.toString() : "");
            }
        } else if (from != null || to != null) {
            lines = service.getTrialBalance(companyId, from, to);
            periodLabel = (from != null ? from.toString() : "") + "_" + (to != null ? to.toString() : "");
        } else {
            Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, null);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
                periodLabel = "exercice " + from + "_" + to;
            } else {
                periodLabel = LocalDate.now().toString();
            }
            lines = service.getTrialBalance(companyId);
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalBalance = BigDecimal.ZERO;
        for (TrialBalanceLine line : lines) {
            if (line.totalDebit() != null) totalDebit = totalDebit.add(line.totalDebit());
            if (line.totalCredit() != null) totalCredit = totalCredit.add(line.totalCredit());
            if (line.balance() != null) totalBalance = totalBalance.add(line.balance());
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("companyName", "");
        variables.put("period", periodLabel);
        variables.put("generationDate", LocalDate.now().toString());
        variables.put("lines", lines);
        variables.put("totalDebit", totalDebit.toString());
        variables.put("totalCredit", totalCredit.toString());
        variables.put("totalBalance", totalBalance.toString());

        String filename = "balance-generale-" + companyId + "-" + periodLabel + ".pdf";
        ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
            documentGenerationService, companyId, DocumentType.TRIAL_BALANCE_REPORT, variables, filename);
        LOG.info("[PDF] Balance générale générée pour companyId={} période={} ({} lignes, {} octets)",
            companyId, periodLabel, lines.size(), response.getBody().length);
        return response;
    }

    @Operation(summary = "Générer le grand livre en PDF (Reports Hub v2.4.0)",
        description = "Rendu PDF du grand livre d'un compte via :document-generation (template LEDGER_REPORT). " +
                      "Délègue au même service métier que GET /ledger. " +
                      "Le paramètre accountId est obligatoire (le grand livre est par compte).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "PDF binaire (grand livre)",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "accountId manquant",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/ledger/pdf")
    public ResponseEntity<byte[]> getLedgerPdf(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestParam UUID accountId,
        @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
        @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
        @org.springframework.web.bind.annotation.RequestParam(required = false) UUID fiscalYearId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // Réutiliser la même logique de résolution que le endpoint JSON.
        if (fiscalYearId != null) {
            Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, fiscalYearId);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
            }
        }
        if (from == null && to == null) {
            Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, null);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
            }
        }
        List<LedgerLine> lines = service.getLedger(companyId, accountId, from, to);

        // Libellé du compte : on prend le code compte de la première ligne si disponible.
        String accountCode = lines.isEmpty() ? accountId.toString() : lines.get(0).accountCode();
        String accountLabel = "Compte " + accountCode;
        String periodLabel = (from != null ? from.toString() : "") + "_" + (to != null ? to.toString() : "");

        Map<String, Object> variables = new HashMap<>();
        variables.put("companyName", "");
        variables.put("period", periodLabel);
        variables.put("accountCode", accountCode);
        variables.put("accountLabel", accountLabel);
        variables.put("generationDate", LocalDate.now().toString());
        variables.put("lines", lines);

        String filename = "grand-livre-" + companyId + "-" + accountCode + "-" + periodLabel + ".pdf";
        ResponseEntity<byte[]> response = PdfEndpointHelper.generatePdf(
            documentGenerationService, companyId, DocumentType.LEDGER_REPORT, variables, filename);
        LOG.info("[PDF] Grand livre généré pour companyId={} compte={} période={} ({} lignes, {} octets)",
            companyId, accountCode, periodLabel, lines.size(), response.getBody().length);
        return response;
    }

    @Operation(summary = "Exporter les écritures comptables en CSV (Reports Hub v2.4.0)",
        description = "Export CSV de toutes les écritures POSTED sur la période. " +
                      "Format : UTF-8 avec BOM (compatible Excel français), séparateur point-virgule, CRLF. " +
                      "Colonnes : Date;Journal;Reference;Description;Statut;Debit total;Credit total. " +
                      "Une ligne par écriture (pas par ligne d'écriture — pour le détail ligne, voir /ledger/export CSV.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "CSV binaire (écritures comptables)",
            content = @Content(mediaType = "text/csv",
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/entries/export")
    public ResponseEntity<byte[]> exportEntriesCsv(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @Parameter(description = "Format d'export — seule la valeur 'csv' est supportée", example = "csv")
        @RequestParam(defaultValue = "csv") String format,
        @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate from,
        @org.springframework.web.bind.annotation.RequestParam(required = false) LocalDate to,
        @org.springframework.web.bind.annotation.RequestParam(required = false) UUID fiscalYearId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // On ne supporte que CSV pour l'instant. Si un autre format est demandé, on retourne 422.
        if (!"csv".equalsIgnoreCase(format)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Error-Reason", "UNSUPPORTED_FORMAT")
                .body(null);
        }

        // step2-backend — si fiscalYearId est fourni (par le mobile), résoudre l'exercice
        // et utiliser ses bornes comme from/to (prévalence sur les from/to explicites).
        // Permet au mobile Reports Hub de passer ?fiscalYearId= sans avoir à calculer
        // les dates d'exercice côté client.
        if (fiscalYearId != null) {
            Optional<FiscalYear> fy = service.resolveFiscalYear(companyId, fiscalYearId);
            if (fy.isPresent()) {
                from = fy.get().getStartDate();
                to = fy.get().getEndDate();
            }
        }

        // Récupérer toutes les écritures de la période — on pagine par 200 jusqu'à épuisement.
        List<JournalEntryResponse> entries = new ArrayList<>();
        int page = 0;
        int size = 200;
        while (true) {
            org.springframework.data.domain.Page<JournalEntryResponse> p =
                service.searchJournalEntries(companyId, from, to, null, null, null, PageRequest.of(page, size));
            entries.addAll(p.getContent());
            if (!p.hasNext()) break;
            page++;
            if (page > 1000) break;  // safety net — 200_000 écritures max
        }

        // Génération du CSV (séparateur ';', CRLF) — le BOM UTF-8 et les headers sont
        // ajoutés par CsvEndpointHelper (v2.5.0-task8).
        StringBuilder sb = new StringBuilder();
        String LINE_SEP = "\r\n";
        sb.append("Date;Journal;Reference;Description;Statut;Debit total;Credit total").append(LINE_SEP);
        for (JournalEntryResponse e : entries) {
            sb.append(safe(e.entryDate())).append(";")
                .append(safe(e.journalCode())).append(";")
                .append(safe(e.reference())).append(";")
                .append(safe(e.description())).append(";")
                .append(e.status() != null ? e.status().name() : "").append(";")
                .append(e.totalDebit() != null ? e.totalDebit().toPlainString() : "0").append(";")
                .append(e.totalCredit() != null ? e.totalCredit().toPlainString() : "0").append(LINE_SEP);
        }
        String periodLabel = (from != null ? from.toString() : "debut") + "_" + (to != null ? to.toString() : "fin");
        String filename = "ecritures-" + companyId + "-" + periodLabel + ".csv";
        ResponseEntity<byte[]> response = CsvEndpointHelper.buildCsvResponse(sb.toString(), filename);
        LOG.info("[CSV] Export écritures généré pour companyId={} période={} ({} écritures, {} octets)",
            companyId, periodLabel, entries.size(), response.getBody().length);
        return response;
    }

    /** Formate une valeur nullable en chaîne vide (pour CSV). */
    private static String safe(Object o) {
        return o != null ? o.toString() : "";
    }
}

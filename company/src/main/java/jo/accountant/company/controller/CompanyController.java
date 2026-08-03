package jo.accountant.company.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import jo.accountant.company.dto.CompanyResponse;
import jo.accountant.company.dto.CreateCompanyRequest;
import jo.accountant.company.dto.UpdateCompanyLegalFieldsRequest;
import jo.accountant.company.entity.Company;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.company.service.CompanyService;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints Company (§13restructurés 2026-07-24).
 *
 * <p>§3.8 : les paths utilisent {@code /api/v1/companies/*} (PAS scopés par companyId dans l'URL
 * car la société est la ressource créée/listée).
 *
 * <p>Restructuration : {@code POST /companies} ne porte plus que les champs d'identité
 * (name, country, functionalCurrency). Le reste est saisi via les étapes du wizard (étapes
 * 2, 3, 6 et 7 principalement).
 
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
@RequestMapping("/api/v1/companies")
@Tag(name = "Company", description = "Company identity, wizard, business-type activation")
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyModuleService companyModuleService;
    private final RoleChecker roleChecker;

    public CompanyController(CompanyService companyService,
                            CompanyModuleService companyModuleService,
                            RoleChecker roleChecker) {
        this.companyService = companyService;
        this.companyModuleService = companyModuleService;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "List companies accessible to the current user",
        description = "Retourne toutes les sociétés auxquelles l'utilisateur courant a accès (via UserCompanyRole), " +
                      "avec leurwizard et champs légaux (siret/vatNumber/nif/address, V53).")
    @ApiResponse(responseCode = "200",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = CompanyResponse.class),
            examples = @ExampleObject(name = "2 sociétés (1 FR + 1 HT)", value = """
                [
                  {
                    "id": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "name": "Boulangerie du Marché",
                    "legalForm": "SARL",
                    "country": "FR",
                    "functionalCurrency": "EUR",
                    "sector": "COMMERCE",
                    "organizationNature": "FOR_PROFIT",
                    "businessTypeCode": "RETAIL_COMMERCE",
                    "primaryActivityLabel": "Commerce de détail alimentaire",
                    "extraAttributes": {},
                    "accountingFrameworkId": null,
                    "fiscalYearStartMonth": 1,
                    "wizardStep": 9,
                    "wizardCompleted": true,
                    "siret": "12345678900012",
                    "vatNumber": "FR12345678901",
                    "nif": null,
                    "address": "12 rue du Marché, 75001 Paris",
                    "createdAt": "2026-01-15T09:00:00Z",
                    "updatedAt": "2026-02-20T14:30:00Z"
                  },
                  {
                    "id": "0192a8d6-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                    "name": "Boutique Pétion-Ville",
                    "legalForm": "SARL",
                    "country": "HT",
                    "functionalCurrency": "HTG",
                    "sector": "COMMERCE",
                    "organizationNature": "FOR_PROFIT",
                    "businessTypeCode": "RETAIL_COMMERCE",
                    "primaryActivityLabel": "Commerce de détail",
                    "extraAttributes": {},
                    "accountingFrameworkId": null,
                    "fiscalYearStartMonth": 1,
                    "wizardStep": 9,
                    "wizardCompleted": true,
                    "siret": null,
                    "vatNumber": null,
                    "nif": "HT-2018-12345",
                    "address": "Rue Lamarre 25, Pétion-Ville, Haïti",
                    "createdAt": "2026-03-01T08:00:00Z",
                    "updatedAt": "2026-03-15T11:00:00Z"
                  }
                ]
                """)))
    @GetMapping
    public List<CompanyResponse> list(@CurrentUser java.util.UUID userId) {
        return companyService.listCompaniesForUser(userId).stream()
            .map(CompanyService::toResponse)
            .toList();
    }

    @Operation(summary = "Create a new company (wizard step 1 — identity only)",
        description = "seuls name, country, functionalCurrency sont " +
                      "acceptés à ce stade. legalForm, sector, accountingFrameworkId et " +
                      "fiscalYearStartMonth doivent être saisis via les étapes 2, 3 et 6 du wizard. " +
                      "§12 : limited to 3 companies per user by default (configurable). " +
                      "Creator is auto-assigned OWNER role. " +
                      "organizationNature et legalForm (nullables) " +
                      "sont désormais acceptés optionnellement pour saisir ces infos dès la création " +
                      "(defaults FOR_PROFIT / OTHER si null).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Company created",
            content = @Content(schema = @Schema(implementation = CompanyResponse.class),
                examples = @ExampleObject(value = """
                    {"id":"0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd","name":"Boutique Pétion-Ville","legalForm":"OTHER","country":"HT","functionalCurrency":"HTG","sector":"AUTRE","organizationNature":"FOR_PROFIT","businessTypeCode":"CUSTOM","primaryActivityLabel":"","accountingFrameworkId":null,"fiscalYearStartMonth":1,"wizardStep":1,"wizardCompleted":false}
                    """))),
        @ApiResponse(responseCode = "409", description = "Max companies reached",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {"type":"https://joaccountant.dev/errors/max_companies_reached","title":"Conflict","status":409,"detail":"You have reached the maximum number of companies (3). Current count: 3. Consider upgrading your subscription to create more.","code":"MAX_COMPANIES_REACHED"}
                    """))),
        @ApiResponse(responseCode = "422", description = "Invalid input (incl. organizationNature/legalForm hors domaine)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<jo.accountant.company.dto.CreateCompanyResponse> create(
            @CurrentUser java.util.UUID userId,
            @Valid @RequestBody CreateCompanyRequest req) {
        jo.accountant.company.dto.CreateCompanyResponse result =
            companyService.createCompany(userId, req.name(), req.country(), req.functionalCurrency(),
                req.organizationNature(), req.legalForm());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Operation(summary = "Get a single company",
        description = "Retourne une société avec tous ses champs (y compris les champs légaux V53).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CompanyResponse.class),
                examples = @ExampleObject(name = "Société française complète", value = """
                    {
                      "id": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "name": "Boulangerie du Marché",
                      "legalForm": "SARL",
                      "country": "FR",
                      "functionalCurrency": "EUR",
                      "sector": "COMMERCE",
                      "organizationNature": "FOR_PROFIT",
                      "businessTypeCode": "RETAIL_COMMERCE",
                      "primaryActivityLabel": "Commerce de détail alimentaire",
                      "extraAttributes": {},
                      "accountingFrameworkId": null,
                      "fiscalYearStartMonth": 1,
                      "wizardStep": 9,
                      "wizardCompleted": true,
                      "siret": "12345678900012",
                      "vatNumber": "FR12345678901",
                      "nif": null,
                      "address": "12 rue du Marché, 75001 Paris",
                      "createdAt": "2026-01-15T09:00:00Z",
                      "updatedAt": "2026-02-20T14:30:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Not found OR belongs to another user (§3.9)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/company-not-found",
                      "title": "Société introuvable",
                      "status": 404,
                      "detail": "Aucune société avec l'id 0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd accessible à cet utilisateur.",
                      "properties": {"code": "COMPANY_NOT_FOUND"}
                    }
                    """)))
    })
    @GetMapping("/{companyId}")
    public CompanyResponse get(@CurrentUser java.util.UUID userId,
                               @PathVariable java.util.UUID companyId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return CompanyService.toResponse(companyService.getCompanyForUser(companyId, userId));
    }

    @Operation(summary = "Update the legal fields of a company",
        description = "Mise à jour partielle des champs légaux (siret, vatNumber, nif, address) " +
                      "persistés par la migration V53. Ces champs restent éditables après " +
                      "wizardCompleted=true car ils relèvent de la conformité réglementaire " +
                      "(mentions légales factures CGI art. 289 + Factur-X). " +
                      "Sémantique : seuls les champs non-nuls sont écrasés ; une chaîne blank " +
                      "efface le champ. Un événement LEGAL_FIELDS_UPDATED est publié pour " +
                      "audit-trail (oldValue/newValue au format JSON, PII masquée).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Legal fields updated",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CompanyResponse.class),
                examples = @ExampleObject(name = "Société FR avec SIRET + VAT + address", value = """
                    {
                      "id": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "name": "Boulangerie du Marché",
                      "legalForm": "SARL",
                      "country": "FR",
                      "functionalCurrency": "EUR",
                      "sector": "COMMERCE",
                      "organizationNature": "FOR_PROFIT",
                      "businessTypeCode": "RETAIL_COMMERCE",
                      "primaryActivityLabel": "Commerce de détail alimentaire",
                      "extraAttributes": {},
                      "accountingFrameworkId": null,
                      "fiscalYearStartMonth": 1,
                      "wizardStep": 9,
                      "wizardCompleted": true,
                      "siret": "12345678900012",
                      "vatNumber": "FR12345678901",
                      "nif": null,
                      "address": "12 rue du Marché, 75001 Paris",
                      "createdAt": "2026-01-15T09:00:00Z",
                      "updatedAt": "2026-07-28T16:45:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Company not found OR belongs to another user (§3.9)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Invalid input (pattern mismatch)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/validation",
                      "title": "Validation échouée",
                      "status": 422,
                      "detail": "Le SIRET doit contenir exactement 14 chiffres.",
                      "properties": {"code": "VALIDATION_ERROR", "field": "siret"}
                    }
                    """)))
    })
    @PatchMapping("/{companyId}/legal")
    public CompanyResponse updateLegalFields(@CurrentUser java.util.UUID userId,
                                             @PathVariable java.util.UUID companyId,
                                             @Valid @RequestBody UpdateCompanyLegalFieldsRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return CompanyService.toResponse(companyService.updateLegalFields(companyId, userId, req));
    }

    /**
     * Fix PDF v9.4 — Upload du logo entreprise (multipart/form-data).
     *
     * <p>Le logo est stocké via {@link jo.accountant.core.port.FileStoragePort} et la clé opaque
     * est persistée dans {@code companies.logo_storage_key} (migration V3_017). Le logo est
     * ensuite résolu par {@code CompanyInfoPortAdapter} pour injection automatique dans les PDFs.
     *
     * <p>Contraintes :
     * <ul>
     *   <li>Format : PNG, JPEG, ou WebP (validated via Content-Type)</li>
     *   <li>Taille max : 2 MB</li>
     *   <li>Rôle requis : ADMIN</li>
     * </ul>
     */
    @Operation(summary = "Upload logo entreprise",
        description = "Téléverse le logo de l'entreprise (PNG/JPEG/WebP, max 2MB). " +
                      "Le logo est stocké via FileStoragePort et résolu automatiquement dans les PDFs générés.")
    @PostMapping(value = "/{companyId}/logo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompanyResponse uploadLogo(@CurrentUser java.util.UUID userId,
                                       @PathVariable java.util.UUID companyId,
                                       @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return CompanyService.toResponse(companyService.uploadLogo(companyId, userId, file));
    }

    /**
     * Fix PDF v9.4 — Supprime le logo entreprise.
     */
    @Operation(summary = "Supprime le logo entreprise")
    @DeleteMapping("/{companyId}/logo")
    public CompanyResponse deleteLogo(@CurrentUser java.util.UUID userId,
                                       @PathVariable java.util.UUID companyId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return CompanyService.toResponse(companyService.deleteLogo(companyId, userId));
    }

    @Operation(summary = "Update wizard step 1 (identité — ré-éditable)",
        description = "V8.2 — Wizard refondu en 4 étapes. " +
                      "(identité) est ré-éditable via cet endpoint : corriger " +
                      "name/country/functionalCurrency sans recréer la société. " +
                      "Les étapes 2 et 3 ont leurs propres endpoints dédiés " +
                      "(PATCH /wizard/2 et PATCH /wizard/3) avec DTO typés.")
    @PatchMapping("/{companyId}/wizard/1")
    public CompanyResponse updateWizardStep1(@CurrentUser java.util.UUID userId,
                                              @PathVariable java.util.UUID companyId,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        roleChecker.ensureRole(companyId, "ADMIN");
        String name = payload != null && payload.get("name") instanceof String s ? s : null;
        String country = payload != null && payload.get("country") instanceof String c ? c : null;
        String functionalCurrency = payload != null && payload.get("functionalCurrency") instanceof String fc ? fc : null;
        return CompanyService.toResponse(
            companyService.applyWizardStep1(companyId, userId, name, country, functionalCurrency));
    }

    @Operation(summary = "Update wizard step 2 (activité & type métier)",
        description = "V8.2 — Fusionne les anciennes étapes 3 (sector), 4 (business type), " +
                      "5 (activity), 7 (required fields) et 8 (module selection pour CUSTOM). " +
                      "Auto-popule organizationNature et sector depuis les defaults du BusinessType. " +
                      "Accepte le DTO typé {@code WizardStep2Request} (validation @Valid).")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
        description = "Payload typé WizardStep2Request",
        content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json",
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = jo.accountant.company.dto.WizardStep2Request.class),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(name = "RETAIL_COMMERCE", value = """
                {
                  "primaryActivityLabel": "Vente au détail de produits alimentaires",
                  "businessTypeCode": "RETAIL_COMMERCE",
                  "sector": "COMMERCE",
                  "extraAttributes": {"pointOfSaleType": "PHYSICAL_STORE"},
                  "customModules": null
                }
                """)))
    @PatchMapping("/{companyId}/wizard/2")
    public CompanyResponse updateWizardStep2(@CurrentUser java.util.UUID userId,
                                              @PathVariable java.util.UUID companyId,
                                              @Valid @RequestBody jo.accountant.company.dto.WizardStep2Request req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return CompanyService.toResponse(companyService.applyWizardStep2(companyId, userId, req));
    }

    @Operation(summary = "Update wizard step 3 (comptabilité & fiscalité)",
        description = "V8.2 — Fusionne les anciennes étapes 6 (framework+fiscal), 9 (VAT mode), " +
                      "10 (numbering). Stocke vatMode + numberingPrefixes dans extraAttributes " +
                      "pour consommation à l'(completeWizard). Accepte le DTO typé " +
                      "{@code WizardStep3Request} (validation @Valid).")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
        description = "Payload typé WizardStep3Request",
        content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json",
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = jo.accountant.company.dto.WizardStep3Request.class),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(name = "SYSCOHADA + débits", value = """
                {
                  "accountingFrameworkId": "01978a10-syscohad-0001-0000-000000000001",
                  "fiscalYearStartMonth": 1,
                  "fiscalYearStartYear": 2026,
                  "fiscalYearLabel": "Exercice 2026",
                  "vatMode": "DEBIT",
                  "numberingPrefixes": {
                    "SALES_INVOICE": "INV-2026-",
                    "PURCHASE_INVOICE": "ACH-2026-"
                  }
                }
                """)))
    @PatchMapping("/{companyId}/wizard/3")
    public CompanyResponse updateWizardStep3(@CurrentUser java.util.UUID userId,
                                              @PathVariable java.util.UUID companyId,
                                              @Valid @RequestBody jo.accountant.company.dto.WizardStep3Request req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return CompanyService.toResponse(companyService.applyWizardStep3(companyId, userId, req));
    }

    @Operation(summary = "Complete the wizard (— activation atomique)",
        description = "V8.2 — Active atomiquement en UNE SEULE transaction : " +
                      "(1) modules always-on + sectoriels BusinessType + customModules si CUSTOM, " +
                      "(2) plan comptable (ChartOfAccountsService.initialize avec seed sectoriel), " +
                      "(3) exercice fiscal + 12 périodes mensuelles, " +
                      "(4) 8 journaux standards (VT/AC/BQ/CA/OD/PA/DP/FX), " +
                      "(5) 6 séquences de numérotation par défaut, " +
                      "(6) règles TVA par défaut si pays non couvert par seeds globaux. " +
                      "Idempotent : si rappelé, ne crée pas de doublons. " +
                      "Retourne {@code CompanyWizardResult} avec récapitulatif des objets créés.")
    @PostMapping("/{companyId}/wizard/complete")
    public jo.accountant.company.dto.CompanyWizardResult completeWizard(
        @CurrentUser java.util.UUID userId,
        @PathVariable java.util.UUID companyId,
        @RequestBody(required = false) jo.accountant.company.dto.CompleteWizardRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return companyService.completeWizard(companyId, userId,
            req != null ? req : new jo.accountant.company.dto.CompleteWizardRequest(null, null, null));
    }

    @Operation(summary = "List activated modules for this company")
    @GetMapping("/{companyId}/modules")
    public List<jo.accountant.company.entity.CompanyModule> listModules(
        @CurrentUser java.util.UUID userId,
        @PathVariable java.util.UUID companyId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return companyModuleService.listForCompany(companyId);
    }

    @Operation(summary = "Activate a module for this company",
        description = "(suite — feature toggle) : permet à un " +
                      "administrateur d'activer manuellement un module sectoriel non inclus " +
                      "par défaut dans le mapping BusinessType → modules. Ex. un cabinet " +
                      "comptable qui diversifie dans le retail peut activer INVENTORY sans " +
                      "recréer une société. Les modules always-on peuvent être réactivés " +
                      "ici (cas d'une mauvaise désactivation).")
    @PostMapping("/{companyId}/modules/{moduleCode}/activate")
    public jo.accountant.company.entity.CompanyModule activateModule(
        @CurrentUser java.util.UUID userId,
        @PathVariable java.util.UUID companyId,
        @PathVariable String moduleCode) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return companyModuleService.enable(companyId, parseModuleCode(moduleCode));
    }

    @Operation(summary = "Deactivate a module for this company",
        description = "(suite — feature toggle) : permet à un " +
                      "administrateur de désactiver un module sectoriel non utilisé. " +
                      "Refuse la désactivation d'un module always-on (409 " +
                      "MODULE_CANNOT_BE_DISABLED) — ces modules sont nécessaires au " +
                      "fonctionnement transverse du système. Les endpoints du module " +
                      "désactivé retourneront 403 MODULE_NOT_ENABLED.")
    @PostMapping("/{companyId}/modules/{moduleCode}/deactivate")
    public jo.accountant.company.entity.CompanyModule deactivateModule(
        @CurrentUser java.util.UUID userId,
        @PathVariable java.util.UUID companyId,
        @PathVariable String moduleCode) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return companyModuleService.disable(companyId, parseModuleCode(moduleCode));
    }

    /** Parse la valeur du path param {@code moduleCode} en {@link ModuleCode}. */
    private static jo.accountant.company.entity.ModuleCode parseModuleCode(String raw) {
        try {
            return jo.accountant.company.entity.ModuleCode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new jo.accountant.core.exception.ValidationException("MODULE_CODE_INVALID",
                "Code de module invalide : " + raw + ". Valeurs attendues : "
                + java.util.Arrays.toString(jo.accountant.company.entity.ModuleCode.values()));
        }
    }

}

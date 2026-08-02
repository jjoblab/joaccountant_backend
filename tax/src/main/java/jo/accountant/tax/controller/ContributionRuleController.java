package jo.accountant.tax.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.tax.entity.ContributionRule;
import jo.accountant.tax.entity.ContributionRule.ContributionBase;
import jo.accountant.tax.entity.ContributionRule.ContributionRegime;
import jo.accountant.tax.entity.ContributionRule.ContributionType;
import jo.accountant.tax.repository.ContributionRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour les règles de cotisation sociale#3 — session 7).
 *
 * <p>Expose le CRUD pour {@link ContributionRule} — débloque l'intégration mobile du moteur
 * de paie par tranches (PMSS, CSG abattue, Tranche A/B). Le {@link jo.accountant.payroll.service.PayrollCalculator}
 * était implémenté mais les règles n'étaient pas configurables via API.
 *
 * <p>Tous les endpoints nécessitent le rôle ADMIN (configuration sensible — impact sur tous
 * les bulletins de paie).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tax/contribution-rules")
@Tag(name = "Tax", description = "Règles fiscales + TVA (débits/encaissements, V55) + cotisations (V51)")
/**
 * Contrôleur REST ContributionRule.
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
 *   <li>{@code DELETE /}</li>
 *   <li>{@code GET  /}</li>
 * </ul>

 * @author jo@Dev


 */

public class ContributionRuleController {

    private final ContributionRuleRepository repository;
    private final RoleChecker roleChecker;

    public ContributionRuleController(ContributionRuleRepository repository, RoleChecker roleChecker) {
        this.repository = repository;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Lister les règles de cotisation d'une entreprise",
        description = "Retourne toutes les règles actives. Filtrable par régime via `?regime=FR_CADRE`.\n\n" +
                      "**Régimes supportés** : `FR_GENERAL`, `FR_CADRE`, `FR_NON_CADRE`, `HT_GENERAL`, `CUSTOM`.\n\n" +
                      "**Bases de calcul** : `GROSS` (brut), `GROSS_ABATED` (brut abattu — ex : CSG), `CAPPED_GROSS` (brut plafonné — ex : retraite Tranche A), `CAPPED_GROSS_ABATED`, `TRANCHE_B`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Liste des règles (peut être vide)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(name = "Règles FR_GENERAL", value = """
                    [
                      {
                        "id": "0192a8e0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "code": "URSSAF_RETRAITE_TA",
                        "label": "Retraite Tranche A (capped)",
                        "regime": "FR_GENERAL",
                        "contributionType": "EMPLOYEE_AND_EMPLOYER",
                        "rate": 0.0690,
                        "baseType": "CAPPED_GROSS",
                        "abatementRate": 100,
                        "monthlyCeiling": 3666.00,
                        "ceilingMultiplier": 1.0,
                        "taxMappingCode": "645100",
                        "active": true,
                        "createdAt": "2026-07-28T10:00:00",
                        "updatedAt": "2026-07-28T10:00:00"
                      },
                      {
                        "id": "0192a8e0-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "code": "CSG_DEDUCTIBLE",
                        "label": "CSG déductible (abattue 98.25%)",
                        "regime": "FR_GENERAL",
                        "contributionType": "EMPLOYEE",
                        "rate": 0.0240,
                        "baseType": "GROSS_ABATED",
                        "abatementRate": 98.25,
                        "monthlyCeiling": null,
                        "ceilingMultiplier": null,
                        "taxMappingCode": "675100",
                        "active": true,
                        "createdAt": "2026-07-28T10:00:00",
                        "updatedAt": "2026-07-28T10:00:00"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping
    public List<ContributionRule> list(@PathVariable UUID companyId,
                                        @CurrentUser UUID userId,
                                        @Parameter(description = "Filtrer par régime (FR_GENERAL, FR_CADRE, FR_NON_CADRE, HT_GENERAL, CUSTOM)",
                                            example = "FR_GENERAL")
                                        @RequestParam(required = false) ContributionRegime regime) {
        roleChecker.ensureRole(companyId, "VIEWER");
        if (regime != null) {
            return repository.findByCompanyIdAndRegimeAndActiveTrue(companyId, regime);
        }
        return repository.findByCompanyIdAndActiveTrue(companyId);
    }

    @Operation(summary = "Créer une règle de cotisation",
        description = "Crée une nouvelle règle (ex : URSSAF, RETRAITE_TA, CSG). Le `code` doit être unique par entreprise.\n\n" +
                      "**Exemples de codes standards** : `URSSAF_RETRAITE_TA`, `URSSAF_RETRAITE_TB`, `CSG_DEDUCTIBLE`, `CSG_NON_DEDUCTIBLE`, `MEDICAL`, `OFATMA_HEALTH`, `IRI` (Haïti).\n\n" +
                      "**Type de cotisation** : `EMPLOYEE` (cotisation salariale), `EMPLOYER` (patronale), `EMPLOYEE_AND_EMPLOYER` (les deux — le taux s'applique aux deux).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Règle créée",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(name = "Retraite Tranche A créée", value = """
                    {
                      "id": "0192a8e0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "code": "URSSAF_RETRAITE_TA",
                      "label": "Retraite Tranche A (capped)",
                      "regime": "FR_GENERAL",
                      "contributionType": "EMPLOYEE_AND_EMPLOYER",
                      "rate": 0.0690,
                      "baseType": "CAPPED_GROSS",
                      "abatementRate": 100,
                      "monthlyCeiling": 3666.00,
                      "ceilingMultiplier": 1.0,
                      "taxMappingCode": "645100",
                      "active": true,
                      "createdAt": "2026-07-28T10:00:00",
                      "updatedAt": "2026-07-28T10:00:00"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Code déjà existant — code `CONTRIBUTION_RULE_CODE_EXISTS`",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/contribution-rule-code-exists",
                      "title": "Code déjà existant",
                      "status": 409,
                      "detail": "Une règle de cotisation avec le code 'URSSAF_RETRAITE_TA' existe déjà pour cette entreprise.",
                      "properties": {"code": "CONTRIBUTION_RULE_CODE_EXISTS"}
                    }
                    """))),
        @ApiResponse(responseCode = "422", description = "Validation échouée (rate négatif, code vide, régime inconnu)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<ContributionRule> create(@PathVariable UUID companyId,
                                                     @CurrentUser UUID userId,
                                                     @RequestBody CreateContributionRuleRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        if (repository.findByCompanyIdAndActiveTrue(companyId).stream()
            .anyMatch(r -> r.getCode().equalsIgnoreCase(req.code()))) {
            throw new ConflictException("CONTRIBUTION_RULE_CODE_EXISTS",
                "Une règle de cotisation avec le code '" + req.code() + "' existe déjà pour cette entreprise.");
        }
        ContributionRule rule = new ContributionRule();
        rule.setId(UUID.randomUUID());
        rule.setCompanyId(companyId);
        rule.setCode(req.code().trim());
        rule.setLabel(req.label().trim());
        rule.setRegime(req.regime());
        rule.setContributionType(req.contributionType());
        rule.setRate(req.rate());
        rule.setBaseType(req.baseType());
        rule.setAbatementRate(req.abatementRate() != null ? req.abatementRate() : new BigDecimal("100"));
        rule.setMonthlyCeiling(req.monthlyCeiling());
        rule.setCeilingMultiplier(req.ceilingMultiplier());
        rule.setTaxMappingCode(req.taxMappingCode());
        rule.setActive(true);
        ContributionRule saved = repository.save(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "Modifier une règle de cotisation",
        description = "Met à jour une règle existante. Le `code` n'est pas modifiable (utiliser delete + create).\n\n" +
                      "**Sémantique de mise à jour partielle** : seuls les champs non-null du body sont écrasés.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Règle mise à jour",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(name = "Rate ajusté à 7.10%", value = """
                    {
                      "id": "0192a8e0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "code": "URSSAF_RETRAITE_TA",
                      "label": "Retraite Tranche A (capped)",
                      "rate": 0.0710,
                      "active": true
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Règle introuvable (id inconnu ou n'appartient pas à ce companyId)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PutMapping("/{ruleId}")
    public ContributionRule update(@PathVariable UUID companyId,
                                     @PathVariable UUID ruleId,
                                     @CurrentUser UUID userId,
                                     @RequestBody UpdateContributionRuleRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        ContributionRule rule = repository.findById(ruleId)
            .orElseThrow(() -> new NotFoundException("ContributionRule", ruleId));
        if (!rule.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ContributionRule", ruleId);
        }
        if (req.label() != null) rule.setLabel(req.label());
        if (req.rate() != null) rule.setRate(req.rate());
        if (req.baseType() != null) rule.setBaseType(req.baseType());
        if (req.abatementRate() != null) rule.setAbatementRate(req.abatementRate());
        if (req.monthlyCeiling() != null) rule.setMonthlyCeiling(req.monthlyCeiling());
        if (req.ceilingMultiplier() != null) rule.setCeilingMultiplier(req.ceilingMultiplier());
        if (req.taxMappingCode() != null) rule.setTaxMappingCode(req.taxMappingCode());
        if (req.active() != null) rule.setActive(req.active());
        return repository.save(rule);
    }

    @Operation(summary = "Supprimer (désactiver) une règle de cotisation",
        description = "Soft delete — la règle est marquée `active=false`. Les bulletins de paie déjà générés ne sont pas affectés.\n\n" +
                      "Cette approche préserve l'historique : les calculs de paie antérieurs restent auditables, " +
                      "et la règle peut être réactivée via `PUT /{ruleId}` avec `{\"active\": true}` si besoin.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Règle désactivée (soft delete)"),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Règle introuvable",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID companyId,
                                         @PathVariable UUID ruleId,
                                         @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ADMIN");
        ContributionRule rule = repository.findById(ruleId)
            .orElseThrow(() -> new NotFoundException("ContributionRule", ruleId));
        if (!rule.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ContributionRule", ruleId);
        }
        rule.setActive(false);
        repository.save(rule);
        return ResponseEntity.noContent().build();
    }

    // --- DTOs ---

    public record CreateContributionRuleRequest(
        String code,
        String label,
        ContributionRegime regime,
        ContributionType contributionType,
        BigDecimal rate,
        ContributionBase baseType,
        BigDecimal abatementRate,
        BigDecimal monthlyCeiling,
        BigDecimal ceilingMultiplier,
        String taxMappingCode
    ) {}

    public record UpdateContributionRuleRequest(
        String label,
        BigDecimal rate,
        ContributionBase baseType,
        BigDecimal abatementRate,
        BigDecimal monthlyCeiling,
        BigDecimal ceilingMultiplier,
        String taxMappingCode,
        Boolean active
    ) {}
}

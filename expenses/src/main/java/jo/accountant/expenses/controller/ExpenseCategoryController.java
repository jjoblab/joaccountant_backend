package jo.accountant.expenses.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.expenses.dto.CreateExpenseCategoryRequest;
import jo.accountant.expenses.dto.ExpenseCategoryResponse;
import jo.accountant.expenses.dto.UpdateExpenseCategoryRequest;
import jo.accountant.expenses.service.ExpenseCategoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints CRUD pour les catégories de notes de frais (audit batch B).
 *
 * <p>Auparavant, seules les 4 catégories standards ({@code TRAVEL/MEALS/SUPPLIES/OTHER})
 * étaient disponibles, seedées par la migration V54 avec des plafonds NULL (pas de
 * validation). Les administrateurs ne pouvaient pas configurer les plafonds
 * journaliers/mensuels via l'API — il fallait modifier la base directement.
 *
 * <p>Ce contrôleur expose 3 endpoints :
 * <ul>
 * <li>{@code GET /api/v1/companies/{companyId}/expenses/categories} — liste ;</li>
 * <li>{@code POST /api/v1/companies/{companyId}/expenses/categories} — créer une
 * catégorie personnalisée (ex: HOTEL, PARKING) avec plafonds ;</li>
 * <li>{@code PUT /api/v1/companies/{companyId}/expenses/categories/{categoryId}} —
 * modifier les plafonds d'une catégorie existante.</li>
 * </ul>
 *
 * <p><b>Rôles</b> :
 * <ul>
 * <li>{@code GET} : {@code VIEWER} (lecture seule) — tout utilisateur ayant accès à
 * l'entreprise peut voir les catégories ;</li>
 * <li>{@code POST}/{@code PUT} : {@code ADMIN} uniquement — la configuration des
 * plafonds est une décision de politique d'entreprise, pas une saisie courante.</li>
 * </ul>
 *
 * <p>Le module :expenses est <strong>toujours-actif</strong> (always-on — voir
 * {@code BusinessTypeModuleService.alwaysOnModules}). Pas de {@code ModuleAccessGuard}
 * requise sur ses endpoints (au même titre qu'{@code ExpensesController}).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/expenses/categories")
@Tag(name = "ExpenseCategories",
 description = "CRUD des catégories de notes de frais et de leurs plafonds "
 + "journaliers/mensuels (audit batch B)")
public class ExpenseCategoryController {

 private final ExpenseCategoryService service;
 private final RoleChecker roleChecker;

 public ExpenseCategoryController(ExpenseCategoryService service, RoleChecker roleChecker) {
 this.service = service;
 this.roleChecker = roleChecker;
 }

 @Operation(summary = "Lister les catégories de notes de frais",
 description = "Retourne toutes les catégories configurées pour l'entreprise "
 + "(codes standards TRAVEL/MEALS/SUPPLIES/OTHER seedés par V54 + "
 + "codes personnalisés créés via POST). Triées par code.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseCategoryResponse.class),
 examples = @ExampleObject(name = "2 catégories avec plafonds", value = """
 [
 {
 "id": "0192c0f1-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "MISSION_PARIS",
 "label": "Mission Paris (repas + transport)",
 "dailyLimit": 80.00,
 "monthlyLimit": null
 },
 {
 "id": "0192c0f1-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "REPAS_CLIENT",
 "label": "Repas d'affaires avec client",
 "dailyLimit": null,
 "monthlyLimit": 300.00
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping
 public List<ExpenseCategoryResponse> list(@PathVariable UUID companyId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 return service.list(companyId);
 }

 @Operation(summary = "Créer une catégorie de note de frais",
 description = "Crée une nouvelle catégorie avec plafonds journaliers/mensuels "
 + "optionnels. Le code doit être unique par entreprise. Les codes "
 + "standards (TRAVEL/MEALS/SUPPLIES/OTHER) sont déjà seedés par V54 "
 + "— utiliser PUT pour configurer leurs plafonds.")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseCategoryResponse.class),
 examples = @ExampleObject(name = "Catégorie MISSION_PARIS créée", value = """
 {
 "id": "0192c0f1-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "MISSION_PARIS",
 "label": "Mission Paris (repas + transport)",
 "dailyLimit": 80.00,
 "monthlyLimit": null
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "Code déjà existant — code `EXPENSE_CATEGORY_CODE_EXISTS`",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/expense-category-code-exists",
 "title": "Code déjà existant",
 "status": 409,
 "detail": "Une catégorie de note de frais avec le code 'MISSION_PARIS' existe déjà pour cette entreprise.",
 "properties": {"code": "EXPENSE_CATEGORY_CODE_EXISTS"}
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Code invalide (pattern / taille)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<ExpenseCategoryResponse> create(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateExpenseCategoryRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return ResponseEntity.status(HttpStatus.CREATED)
 .body(service.create(companyId, req));
 }

 @Operation(summary = "Modifier les plafonds d'une catégorie",
 description = "Met à jour les plafonds journaliers/mensuels d'une catégorie "
 + "existante. Le code n'est PAS modifiable (intégrité référentielle "
 + "avec expense_line.category). Pour désactiver un plafond, passer "
 + "null explicitement dans le JSON.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ExpenseCategoryResponse.class),
 examples = @ExampleObject(name = "Plafond mensuel ajusté à 350€", value = """
 {
 "id": "0192c0f1-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "REPAS_CLIENT",
 "label": "Repas d'affaires avec client",
 "dailyLimit": null,
 "monthlyLimit": 350.00
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "Catégorie introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "Plafond négatif",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PutMapping(value = "/{categoryId}", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ExpenseCategoryResponse update(
 @PathVariable UUID companyId,
 @PathVariable UUID categoryId,
 @CurrentUser UUID userId,
 @Valid @RequestBody UpdateExpenseCategoryRequest req) {
 roleChecker.ensureRole(companyId, "ADMIN");
 return service.update(companyId, categoryId, req);
 }
}

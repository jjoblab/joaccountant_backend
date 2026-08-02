package jo.accountant.inventory.controller;

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
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.inventory.dto.CreateItemRequest;
import jo.accountant.inventory.dto.CreateStockMoveRequest;
import jo.accountant.inventory.dto.CreateWarehouseRequest;
import jo.accountant.inventory.dto.InventoryValuationResponse;
import jo.accountant.inventory.dto.ItemResponse;
import jo.accountant.inventory.dto.ItemValuation;
import jo.accountant.inventory.dto.StockMoveResponse;
import jo.accountant.inventory.entity.Warehouse;
import jo.accountant.inventory.service.InventoryService;
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
 * Endpoints de gestion de stock (§13 Phase 9).
 *
 * <p>Convention d'URL (§3.8) :
 * {@code /api/v1/companies/{companyId}/inventory/...}.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/inventory")
@Tag(name = "Inventory", description = "Stock, valorisation FIFO/coût moyen pondéré, COGS (§13 Phase 9)")
public class InventoryController {

 private final InventoryService service;
 private final RoleChecker roleChecker;
 private final ModuleAccessGuard moduleAccessGuard;

 public InventoryController(InventoryService service, RoleChecker roleChecker,
 ModuleAccessGuard moduleAccessGuard) {
 this.service = service;
 this.roleChecker = roleChecker;
 this.moduleAccessGuard = moduleAccessGuard;
 }

 @Operation(summary = "Créer un entrepôt",
 description = "Crée un nouvel entrepôt pour l'entreprise.")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = Warehouse.class),
 examples = @ExampleObject(name = "Entrepôt principal créé", value = """
 {
 "id": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "code": "WH-MAIN",
 "label": "Entrepôt principal Pétion-Ville",
 "address": "Rue Lamarre 25, Pétion-Ville, Haïti",
 "active": true
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping(value = "/warehouses", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<Warehouse> createWarehouse(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateWarehouseRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return ResponseEntity.status(HttpStatus.CREATED).body(service.createWarehouse(companyId, req));
 }

 @Operation(summary = "Lister les articles du tenant",
 description = "Retourne tous les articles triés par SKU ascendant. " +
 "Utilisé par l'écran Inventaire du mobile (audit E-8, correction #1).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ItemResponse.class),
 examples = @ExampleObject(name = "2 articles", value = """
 [
 {
 "id": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "sku": "SKU-001",
 "label": "Sac de riz 25kg",
 "unitOfMeasure": "PIECE",
 "costingMethod": "WEIGHTED_AVERAGE",
 "reorderThreshold": 10,
 "inventoryAccountId": "0192a8c0-5a6b-7c8d-9e0f-a1bcde0501aa",
 "cogsAccountId": "0192a8c0-6b7c-8d9e-0fa1-bcde050102ab"
 },
 {
 "id": "0192c0f9-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "sku": "SKU-002",
 "label": "Bidon huile 5L",
 "unitOfMeasure": "PIECE",
 "costingMethod": "FIFO",
 "reorderThreshold": 5,
 "inventoryAccountId": "0192a8c0-5a6b-7c8d-9e0f-a1bcde0501aa",
 "cogsAccountId": "0192a8c0-6b7c-8d9e-0fa1-bcde050102ab"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/items")
 public List<ItemResponse> listItems(@PathVariable UUID companyId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return service.listItems(companyId);
 }

 @Operation(summary = "Récupérer un article par son ID",
 description = "Correction 2026-07-26 — endpoint nécessaire pour le deep-linking depuis " +
 "les notifications mobile.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ItemResponse.class),
 examples = @ExampleObject(name = "Article SKU-001", value = """
 {
 "id": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "sku": "SKU-001",
 "label": "Sac de riz 25kg",
 "unitOfMeasure": "PIECE",
 "costingMethod": "WEIGHTED_AVERAGE",
 "reorderThreshold": 10,
 "inventoryAccountId": "0192a8c0-5a6b-7c8d-9e0f-a1bcde0501aa",
 "cogsAccountId": "0192a8c0-6b7c-8d9e-0fa1-bcde050102ab"
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Article introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/items/{itemId}")
 public ItemResponse getItem(@PathVariable UUID companyId,
 @PathVariable UUID itemId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return service.getItem(companyId, itemId);
 }

 @Operation(summary = "Créer un article",
 description = "Crée un nouvel article de stock avec méthode de valorisation (FIFO ou WEIGHTED_AVERAGE).")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ItemResponse.class),
 examples = @ExampleObject(name = "Article SKU-001 créé", value = """
 {
 "id": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "sku": "SKU-001",
 "label": "Sac de riz 25kg",
 "unitOfMeasure": "PIECE",
 "costingMethod": "WEIGHTED_AVERAGE",
 "reorderThreshold": 10,
 "inventoryAccountId": "0192a8c0-5a6b-7c8d-9e0f-a1bcde0501aa",
 "cogsAccountId": "0192a8c0-6b7c-8d9e-0fa1-bcde050102ab"
 }
 """))),
 @ApiResponse(responseCode = "422", description = "SKU vide ou costingMethod inconnu",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping(value = "/items", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<ItemResponse> createItem(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateItemRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return ResponseEntity.status(HttpStatus.CREATED).body(service.createItem(companyId, req));
 }

 @Operation(summary = "Créer un mouvement de stock",
 description = "IN : entrée (crée couche FIFO). OUT : sortie (calcule COGS, génère écriture). " +
 "TRANSFER : non supporté en Phase 9. " +
 "Stock négatif rejeté par défaut. LIFO jamais implémenté (IFRS l'interdit).")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = StockMoveResponse.class),
 examples = @ExampleObject(name = "Sortie de stock (OUT) + écriture COGS", value = """
 {
 "id": "0192c0fa-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "warehouseId": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "moveDate": "2026-03-15",
 "direction": "OUT",
 "quantity": 5,
 "unitCost": 1250.00,
 "totalCost": 6250.00,
 "sourceDocument": "INV-2026-03-0001",
 "journalEntryId": "0192c0fb-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "createdAt": "2026-03-15T14:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "409", description = "Stock insuffisant",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/insufficient-stock",
 "title": "Stock insuffisant",
 "status": 409,
 "detail": "Stock insuffisant pour l'article SKU-001 : demandé 5, disponible 2.",
 "properties": {"code": "INSUFFICIENT_STOCK"}
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Paramètres invalides",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping(value = "/stock-moves", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<StockMoveResponse> postStockMove(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateStockMoveRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return ResponseEntity.status(HttpStatus.CREATED).body(service.postStockMove(companyId, req));
 }

 @Operation(summary = "Valorisation de stock d'un article",
 description = "Retourne la valorisation d'un article : quantité restante, coût unitaire moyen pondéré, valeur totale.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ItemValuation.class),
 examples = @ExampleObject(name = "Valorisation SKU-001", value = """
 {
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "sku": "SKU-001",
 "label": "Sac de riz 25kg",
 "quantity": 50,
 "unitCost": 1250.00,
 "totalValue": 62500.00
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Article introuvable",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/items/{itemId}/valuation")
 public ItemValuation getValuation(@PathVariable UUID companyId,
 @PathVariable UUID itemId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return service.getValuation(companyId, itemId);
 }

 @Operation(summary = "Valorisation agrégée de tout le stock (Part E1)",
 description = "Retourne une ligne par couple (article, entrepôt) ayant du stock " +
 "restant : {sku, label, warehouse, quantity, unitCost, totalValue}. " +
 "Utilisé pour le rapport de valorisation d'inventaire.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = InventoryValuationResponse.class),
 examples = @ExampleObject(name = "3 lignes de valorisation", value = """
 [
 {
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "sku": "SKU-001",
 "label": "Sac de riz 25kg",
 "warehouseId": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "warehouse": "Entrepôt principal Pétion-Ville",
 "quantity": 50,
 "unitCost": 1250.00,
 "totalValue": 62500.00
 },
 {
 "itemId": "0192c0f9-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "sku": "SKU-002",
 "label": "Bidon huile 5L",
 "warehouseId": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "warehouse": "Entrepôt principal Pétion-Ville",
 "quantity": 30,
 "unitCost": 450.00,
 "totalValue": 13500.00
 },
 {
 "itemId": "0192c0f9-3e4f-5a6b-7c8d-9e0fa1bcde02",
 "sku": "SKU-003",
 "label": "Sac de farine 50kg",
 "warehouseId": "0192c0f8-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "warehouse": "Entrepôt secondaire Cap-Haïtien",
 "quantity": 15,
 "unitCost": 1800.00,
 "totalValue": 27000.00
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/valuation")
 public List<InventoryValuationResponse> getAggregatedValuation(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 return service.getAggregatedValuation(companyId);
 }

 @Operation(summary = "Lister les mouvements de stock sur une période (Part E2)",
 description = "Tous les mouvements de stock (IN/OUT/TRANSFER) dont la moveDate est " +
 "comprise entre {@code from} et {@code to} (inclus), triés par moveDate " +
 "décroissant. Si {@code from}/{@code to} sont omis, borne inférieure = " +
 "1900-01-01 et borne supérieure = aujourd'hui. Utilisé pour le registre " +
 "des mouvements de stock.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = StockMoveResponse.class),
 examples = @ExampleObject(name = "3 mouvements (IN + OUT + TRANSFER)", value = """
 [
 {
 "id": "0192c0fa-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "warehouseId": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "moveDate": "2026-03-15",
 "direction": "OUT",
 "quantity": 5,
 "unitCost": 1250.00,
 "totalCost": 6250.00,
 "sourceDocument": "INV-2026-03-0001",
 "journalEntryId": "0192c0fb-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "createdAt": "2026-03-15T14:00:00Z"
 },
 {
 "id": "0192c0fa-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "warehouseId": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "moveDate": "2026-03-10",
 "direction": "IN",
 "quantity": 50,
 "unitCost": 1250.00,
 "totalCost": 62500.00,
 "sourceDocument": "PO-2026-03-0001",
 "journalEntryId": null,
 "createdAt": "2026-03-10T09:00:00Z"
 },
 {
 "id": "0192c0fa-3e4f-5a6b-7c8d-9e0fa1bcde02",
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "warehouseId": "0192c0f8-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "moveDate": "2026-03-12",
 "direction": "TRANSFER",
 "quantity": 10,
 "unitCost": 1250.00,
 "totalCost": 12500.00,
 "sourceDocument": "T03-0001",
 "journalEntryId": null,
 "createdAt": "2026-03-12T11:30:00Z"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping("/stock-moves")
 public List<StockMoveResponse> listStockMoves(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @RequestParam(required = false) LocalDate from,
 @RequestParam(required = false) LocalDate to,
 @RequestParam(required = false) UUID itemId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 // Corrige 2026-07-26 : ajout du filtre optionnel ?itemId= pour permettre au
 // mobile de ne télécharger que les mouvements d'un article spécifique (avant,
 // le mobile téléchargeait TOUS les mouvements de l'entreprise puis filtrait
 // côté client — OOM potentiel sur entreprise avec historique).
 var all = service.listStockMoves(companyId, from, to);
 if (itemId == null) return all;
 return all.stream()
 .filter(m -> itemId.equals(m.itemId()))
 .toList();
 }
}

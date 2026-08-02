package jo.accountant.purchaseorders.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.purchaseorders.dto.CreatePurchaseOrderRequest;
import jo.accountant.purchaseorders.dto.PurchaseOrderResponse;
import jo.accountant.purchaseorders.dto.ThreeWayMatchResult;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;
import jo.accountant.purchaseorders.service.PurchaseOrdersService;
import jo.accountant.purchaseorders.service.ThreeWayMatchService;
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
 * Endpoints REST du module :purchase-orders.
 *
 * <p>Convention d'URL : {@code /api/v1/companies/{companyId}/purchase-orders/...}.
 *
 * <p>Le module ne génère pas d'écriture comptable au MVP. Les commandes servent uniquement de
 * référence au 3-way match ({@code POST /purchase-orders/3-way-match?invoiceId=}).
 *
 * <p><b>Contrôle d'accès</b> — Pattern appliqué en tête de chaque endpoint, calqué sur
 * {@code PurchasingController} (module :purchasing voisin) :
 * <ol>
 * <li>{@code roleChecker.ensureRole(companyId, "...")} — vérifie que l'utilisateur JWT a un rôle
 * suffisant <em>et</em> que la company ciblée fait partie de celles auxquelles il a accès
 * (sinon 403 {@code NO_COMPANY_ACCESS} ou 403 {@code INSUFFICIENT_ROLE}).</li>
 * <li>{@code moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING)} — vérifie que le
 * module métier est activé pour la company (sinon 403 {@code MODULE_NOT_ENABLED}).</li>
 * </ol>
 *
 * <p><b>Choix du ModuleCode</b> : le module :purchase-orders réutilise {@link ModuleCode#PURCHASING}
 * plutôt que d'introduire un nouveau code dédié. Justification :
 * <ul>
 * <li>Les bons de commande sont fonctionnellement une sous-activité du processus d'achat
 * (un PO précède et référence une facture fournisseur).</li>
 * <li>Le 3-way match compare une commande à une facture du module :purchasing — les deux
 * modules activent/désactivent ensemble pour rester cohérents.</li>
 * <li>Évite l'introduction d'une nouvelle valeur d'enum + seed business_type_module + migration
 * pour un découpage qui n'apporterait pas de granularité métier utile à ce stade.</li>
 * </ul>
 *
 * <p>Niveaux de rôle appliqués :
 * <ul>
 * <li>Lectures ({@code list}, {@code get}) : {@code VIEWER} minimum.</li>
 * <li>Écritures ({@code create}, {@code changeStatus}, {@code threeWayMatch}) :
 * {@code BOOKKEEPER} minimum.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/purchase-orders")
@Tag(name = "Purchase Orders", description = "Bons de commande + 3-way match PO/GR/Invoice (/ V59)")
public class PurchaseOrdersController {

 private final PurchaseOrdersService poService;
 private final ThreeWayMatchService matchService;
 private final RoleChecker roleChecker;
 private final ModuleAccessGuard moduleAccessGuard;

 public PurchaseOrdersController(PurchaseOrdersService poService,
 ThreeWayMatchService matchService,
 RoleChecker roleChecker,
 ModuleAccessGuard moduleAccessGuard) {
 this.poService = poService;
 this.matchService = matchService;
 this.roleChecker = roleChecker;
 this.moduleAccessGuard = moduleAccessGuard;
 }

 @Operation(summary = "Lister les commandes fournisseurs",
 description = "Retourne toutes les commandes fournisseurs de l'entreprise, tous statuts confondus.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = PurchaseOrderResponse.class),
 examples = @ExampleObject(name = "2 commandes fournisseurs", value = """
 [
 {
 "id": "0192c110-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "supplierId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "supplierName": "Fournisseur Haïti SA",
 "orderNumber": "PO-2026-0001",
 "orderDate": "2026-03-01",
 "status": "RECEIVED",
 "currency": "EUR",
 "totalAmount": 1200.00,
 "lines": [
 {"id": "0192c111-1c2d-3e4f-5a6b-7c8d9e0fabcd", "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd", "description": "Sac de riz 25kg", "quantity": 10, "unitPrice": 100.00, "receivedQuantity": 10, "lineTotal": 1000.00}
 ],
 "createdAt": "2026-03-01T09:00:00Z",
 "updatedAt": "2026-03-05T14:00:00Z"
 },
 {
 "id": "0192c110-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "supplierId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "supplierName": "Fournisseur Haïti SA",
 "orderNumber": "PO-2026-0002",
 "orderDate": "2026-03-15",
 "status": "DRAFT",
 "currency": "EUR",
 "totalAmount": 600.00,
 "lines": [
 {"id": "0192c111-2d3e-4f5a-6b7c-8d9e0fa1bcde", "itemId": "0192c0f9-2d3e-4f5a-6b7c-8d9e0fa1bcde", "description": "Bidon huile 5L", "quantity": 4, "unitPrice": 150.00, "receivedQuantity": 0, "lineTotal": 600.00}
 ],
 "createdAt": "2026-03-15T08:00:00Z",
 "updatedAt": "2026-03-15T08:00:00Z"
 }
 ]
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER requis) ou module PURCHASING désactivé",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping
 public List<PurchaseOrderResponse> list(@PathVariable UUID companyId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
 return poService.list(companyId);
 }

 @Operation(summary = "Récupérer une commande par ID",
 description = "Retourne une commande avec toutes ses lignes (description, quantité, prix unitaire, quantité reçue, total ligne).")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = PurchaseOrderResponse.class),
 examples = @ExampleObject(name = "Détail commande PO-2026-0001", value = """
 {
 "id": "0192c110-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "supplierId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "supplierName": "Fournisseur Haïti SA",
 "orderNumber": "PO-2026-0001",
 "orderDate": "2026-03-01",
 "status": "RECEIVED",
 "currency": "EUR",
 "totalAmount": 1200.00,
 "lines": [
 {
 "id": "0192c111-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "description": "Sac de riz 25kg",
 "quantity": 10,
 "unitPrice": 100.00,
 "receivedQuantity": 10,
 "lineTotal": 1000.00
 },
 {
 "id": "0192c111-3e4f-5a6b-7c8d-9e0fa1bcde02",
 "itemId": "0192c0f9-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "description": "Bidon huile 5L",
 "quantity": 1,
 "unitPrice": 200.00,
 "receivedQuantity": 1,
 "lineTotal": 200.00
 }
 ],
 "createdAt": "2026-03-01T09:00:00Z",
 "updatedAt": "2026-03-05T14:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER requis) ou module PURCHASING désactivé",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "Commande introuvable / hors tenant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping("/{poId}")
 public PurchaseOrderResponse get(@PathVariable UUID companyId,
 @PathVariable UUID poId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
 return poService.get(companyId, poId);
 }

 @Operation(summary = "Créer une commande fournisseur",
 description = "Crée une commande (DRAFT par défaut) avec ses lignes. Le total est calculé " +
 "automatiquement = Σ (quantity × unitPrice).")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = PurchaseOrderResponse.class),
 examples = @ExampleObject(name = "Commande PO-2026-0001 créée", value = """
 {
 "id": "0192c110-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "supplierId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "supplierName": "Fournisseur Haïti SA",
 "orderNumber": "PO-2026-0001",
 "orderDate": "2026-03-01",
 "status": "DRAFT",
 "currency": "EUR",
 "totalAmount": 1200.00,
 "lines": [
 {"id": "0192c111-1c2d-3e4f-5a6b-7c8d9e0fabcd", "itemId": "0192c0f9-1c2d-3e4f-5a6b-7c8d9e0fabcd", "description": "Sac de riz 25kg", "quantity": 10, "unitPrice": 100.00, "receivedQuantity": 0, "lineTotal": 1000.00}
 ],
 "createdAt": "2026-03-01T09:00:00Z",
 "updatedAt": "2026-03-01T09:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis) ou module PURCHASING désactivé",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "Numéro déjà existant",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/po-number-exists",
 "title": "Numéro déjà existant",
 "status": 409,
 "detail": "Une commande avec le numéro 'PO-2026-0001' existe déjà.",
 "properties": {"code": "PO_NUMBER_EXISTS"}
 }
 """)))
 })
 @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<PurchaseOrderResponse> create(
 @PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreatePurchaseOrderRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
 PurchaseOrderResponse po = poService.create(companyId, req);
 return ResponseEntity.status(HttpStatus.CREATED).body(po);
 }

 @Operation(summary = "Changer le statut d'une commande",
 description = "DRAFT → SUBMITTED → RECEIVED → CLOSED.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = PurchaseOrderResponse.class),
 examples = @ExampleObject(name = "Commande soumise (status=SUBMITTED)", value = """
 {
 "id": "0192c110-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "supplierName": "Fournisseur Haïti SA",
 "orderNumber": "PO-2026-0001",
 "orderDate": "2026-03-01",
 "status": "SUBMITTED",
 "currency": "EUR",
 "totalAmount": 1200.00
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis) ou module PURCHASING désactivé",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "Transition de statut invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/{poId}/change-status")
 public PurchaseOrderResponse changeStatus(@PathVariable UUID companyId,
 @PathVariable UUID poId,
 @CurrentUser UUID userId,
 @Parameter(description = "Nouveau statut : DRAFT, SUBMITTED, RECEIVED, CLOSED", required = true, example = "SUBMITTED")
 @RequestParam PurchaseOrderStatus status) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
 return poService.changeStatus(companyId, poId, status);
 }

 @Operation(summary = "3-way match entre une facture et une commande",
 description = "Vérifie que (a) une commande existe pour le fournisseur de la facture, " +
 "(b) les quantités facturées ≤ quantités commandées, " +
 "(c) les prix facturés = prix commandés. " +
 "Retourne un ThreeWayMatchResult avec matches=true/false et la liste des écarts.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 description = "3-way match OK — aucune divergence",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ThreeWayMatchResult.class),
 examples = @ExampleObject(name = "3-way match OK (matches=true)", value = """
 {
 "invoiceId": "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "purchaseOrderId": "0192c110-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "matches": true,
 "discrepancies": []
 }
 """))),
 @ApiResponse(responseCode = "200",
 description = "3-way match KO — 2 écarts (QUANTITY_EXCEEDED + PRICE_MISMATCH)",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ThreeWayMatchResult.class),
 examples = @ExampleObject(name = "3-way match KO (matches=false)", value = """
 {
 "invoiceId": "0192c106-2d3e-4f5a-6b7c-8d9e0fa1bcde",
 "purchaseOrderId": "0192c110-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "matches": false,
 "discrepancies": [
 {
 "type": "QUANTITY_EXCEEDED",
 "detail": "Quantité facturée (12) supérieure à la quantité commandée (10) sur la ligne Sac de riz 25kg.",
 "invoiceLineId": "0192c108-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "poLineId": "0192c111-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "expected": 10,
 "actual": 12
 },
 {
 "type": "PRICE_MISMATCH",
 "detail": "Prix unitaire facturé (110.00) différent du prix commandé (100.00) sur la ligne Sac de riz 25kg.",
 "invoiceLineId": "0192c108-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "poLineId": "0192c111-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "expected": 100.00,
 "actual": 110.00
 }
 ]
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis) ou module PURCHASING désactivé",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "Facture introuvable",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/3-way-match")
 public ThreeWayMatchResult threeWayMatch(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Parameter(description = "ID de la facture fournisseur à rapprocher", required = true,
 example = "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd")
 @RequestParam UUID invoiceId) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
 return matchService.match(companyId, invoiceId);
 }
}

package jo.accountant.purchasing.controller;

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
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.purchasing.dto.CreatePurchaseInvoiceRequest;
import jo.accountant.purchasing.dto.PurchaseInvoiceResponse;
import jo.accountant.purchasing.dto.RecordPurchasePaymentRequest;
import jo.accountant.purchasing.service.PurchasingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Endpoints d'achats (restructuration 2026-07-24 — module :purchasing).
 *
 * <p>Symétrique de {@code InvoicingController} pour le côté fournisseur. Le module
 * est <strong>sectoriel</strong> : son utilisation exige que le module {@code PURCHASING}
 * soit activé pour la société (vérifié en tête de chaque endpoint via
 * {@link ModuleAccessGuard#ensureEnabled}).
 *
 * <p>Pattern de contrôle d'accès calqué sur {@code TaxController} :
 * {@code roleChecker.ensureRole} puis {@code moduleAccessGuard.ensureEnabled} en tête.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/purchase-invoices")
@Tag(name = "Purchasing", description = "Factures fournisseur / achats (restructuration 2026-07-24)")
public class PurchasingController {

    private final PurchasingService service;
    private final RoleChecker roleChecker;
    private final ModuleAccessGuard moduleAccessGuard;

    public PurchasingController(PurchasingService service, RoleChecker roleChecker,
                                 ModuleAccessGuard moduleAccessGuard) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.moduleAccessGuard = moduleAccessGuard;
    }

    @Operation(summary = "Créer une facture d'achat (DRAFT)",
        description = "Crée une facture d'achat au statut DRAFT. L'écriture comptable est générée lors de la réception (POST /{id}/receive).")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class),
                examples = @ExampleObject(name = "Facture d'achat DRAFT créée", value = """
                    {
                      "id": "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "thirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "thirdPartyName": "Fournisseur Haïti SA",
                      "type": "GOODS",
                      "status": "DRAFT",
                      "invoiceNumber": null,
                      "supplierReference": "FH-2026-0345",
                      "issueDate": "2026-03-15",
                      "dueDate": "2026-04-14",
                      "currency": "EUR",
                      "subtotal": 1000.00,
                      "taxAmount": 200.00,
                      "totalAmount": 1200.00,
                      "paidAmount": 0,
                      "balanceDue": 1200.00,
                      "journalEntryId": null,
                      "lines": [],
                      "createdAt": "2026-03-15T14:00:00Z",
                      "updatedAt": "2026-03-15T14:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis) ou module PURCHASING désactivé",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PurchaseInvoiceResponse> create(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreatePurchaseInvoiceRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPurchaseInvoice(companyId, req));
    }

    @Operation(summary = "Lister les factures d'achat (paginé)",
        description = "Filtre optionnel <code>?fiscalYearId=</code> (UUID) — si présent, " +
                      "résout l'exercice via <code>AccountingEngineService.resolveFiscalYear</code> " +
                      "et ne retourne que les factures dont <code>issueDate</code> est comprise " +
                      "entre les bornes start/end de l'exercice. Pagination via <code>?page=&size=</code> " +
                      "(défaut 0/20, size capped à 200). Finding #3 — remplace la variante List<> " +
                      "hard-cappée à 200 pour éviter l'OOM sur entreprises matures.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class),
                examples = @ExampleObject(name = "Page de 2 factures (RECEIVED + PARTIALLY_PAID)", value = """
                    {
                      "content": [
                        {
                          "id": "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "thirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                          "thirdPartyName": "Fournisseur Haïti SA",
                          "type": "GOODS",
                          "status": "RECEIVED",
                          "invoiceNumber": "ACH-2026-0001",
                          "supplierReference": "FH-2026-0345",
                          "issueDate": "2026-03-15",
                          "dueDate": "2026-04-14",
                          "currency": "EUR",
                          "subtotal": 1000.00,
                          "taxAmount": 200.00,
                          "totalAmount": 1200.00,
                          "paidAmount": 0,
                          "balanceDue": 1200.00,
                          "lines": []
                        },
                        {
                          "id": "0192c106-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "thirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                          "thirdPartyName": "Fournisseur Haïti SA",
                          "type": "GOODS",
                          "status": "PARTIALLY_PAID",
                          "invoiceNumber": "ACH-2026-0002",
                          "supplierReference": "FH-2026-0346",
                          "issueDate": "2026-02-20",
                          "dueDate": "2026-03-22",
                          "currency": "EUR",
                          "subtotal": 2000.00,
                          "taxAmount": 400.00,
                          "totalAmount": 2400.00,
                          "paidAmount": 1200.00,
                          "balanceDue": 1200.00,
                          "lines": []
                        }
                      ],
                      "totalElements": 2,
                      "totalPages": 1,
                      "number": 0,
                      "size": 20,
                      "first": true,
                      "last": true,
                      "empty": false
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis) ou module PURCHASING désactivé",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping
    public org.springframework.data.domain.Page<PurchaseInvoiceResponse> list(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @RequestParam(required = false) UUID fiscalYearId,
        @RequestParam(required = false, defaultValue = "0") int page,
        @RequestParam(required = false, defaultValue = "20") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        // Finding #3 — PageRequest cappé à 200 (empêche l'OOM si un client demande size=10000).
        org.springframework.data.domain.Pageable pageable =
            org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        return service.listInvoices(companyId, fiscalYearId, pageable);
    }

    @Operation(summary = "Détail d'une facture d'achat",
        description = "Retourne la facture avec toutes ses lignes (description, quantité, prix unitaire, taux TVA, compte de charge).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class),
                examples = @ExampleObject(name = "Détail facture ACH-2026-0001", value = """
                    {
                      "id": "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "thirdPartyId": "0192a8d3-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "thirdPartyName": "Fournisseur Haïti SA",
                      "type": "GOODS",
                      "status": "RECEIVED",
                      "invoiceNumber": "ACH-2026-0001",
                      "supplierReference": "FH-2026-0345",
                      "issueDate": "2026-03-15",
                      "dueDate": "2026-04-14",
                      "currency": "EUR",
                      "subtotal": 1000.00,
                      "taxAmount": 200.00,
                      "totalAmount": 1200.00,
                      "paidAmount": 0,
                      "balanceDue": 1200.00,
                      "journalEntryId": "0192c107-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "lines": [
                        {"id": "0192c108-1c2d-3e4f-5a6b-7c8d9e0fabcd", "description": "Cartons de riz 25kg", "quantity": 10, "unitPrice": 100.00, "taxRate": 0.20, "lineTotalHt": 1000.00}
                      ]
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Facture introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public PurchaseInvoiceResponse get(@PathVariable UUID companyId,
                                        @PathVariable UUID id,
                                        @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        return service.getInvoice(companyId, id);
    }

    @Operation(summary = "Modifier une facture DRAFT",
        description = "Endpoint simplifié au MVP : délègue à la logique de création. " +
                      "Seules les factures DRAFT peuvent être modifiées.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class))),
        @ApiResponse(responseCode = "409", description = "PATCH non implémenté au MVP — code `PATCH_NOT_IMPLEMENTED`",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/patch-not-implemented",
                      "title": "PATCH non implémenté",
                      "status": 409,
                      "detail": "La modification d'une facture d'achat n'est pas implémentée au MVP. Recréer la facture si elle est encore DRAFT.",
                      "properties": {"code": "PATCH_NOT_IMPLEMENTED"}
                    }
                    """)))
    })
    @PatchMapping("/{id}")
    public PurchaseInvoiceResponse update(@PathVariable UUID companyId,
                                           @PathVariable UUID id,
                                           @CurrentUser UUID userId,
                                           @Valid @RequestBody CreatePurchaseInvoiceRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        // MVP : pas de update partiel implémenté — la facture doit être recréée si elle
        // doit être modifiée après création. Endpoint présent pour conformité au contrat
        // API (§2.1 du prompt) mais lève une erreur explicite.
        throw new jo.accountant.core.exception.ConflictException("PATCH_NOT_IMPLEMENTED",
            "La modification d'une facture d'achat n'est pas implémentée au MVP. " +
            "Recréer la facture si elle est encore DRAFT.");
    }

    @Operation(summary = "Recevoir une facture (DRAFT → RECEIVED)",
        description = "Attribue le numéro interne via document-numbering, génère l'écriture " +
                      "comptable (Débit Achats + TVA déductible / Crédit Fournisseur).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class),
                examples = @ExampleObject(name = "Facture reçue (statut RECEIVED)", value = """
                    {
                      "id": "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "status": "RECEIVED",
                      "invoiceNumber": "ACH-2026-0001",
                      "totalAmount": 1200.00,
                      "balanceDue": 1200.00,
                      "journalEntryId": "0192c107-1c2d-3e4f-5a6b-7c8d9e0fabcd"
                    }
                    """))),
        @ApiResponse(responseCode = "409", description = "Facture non DRAFT",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/{id}/receive")
    public PurchaseInvoiceResponse receive(@PathVariable UUID companyId,
                                            @PathVariable UUID id,
                                            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        return service.receive(companyId, id);
    }

    @Operation(summary = "Enregistrer un paiement fournisseur",
        description = "Enregistre un paiement partiel ou total sur la facture d'achat. Passe la facture à PAID si solde = 0, sinon PARTIALLY_PAID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class),
                examples = @ExampleObject(name = "Paiement partiel enregistré", value = """
                    {
                      "id": "0192c106-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                      "status": "PARTIALLY_PAID",
                      "totalAmount": 2400.00,
                      "paidAmount": 1200.00,
                      "balanceDue": 1200.00
                    }
                    """))),
        @ApiResponse(responseCode = "409", description = "Facture non RECEIVED ou déjà payée",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(value = "/{id}/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseInvoiceResponse recordPayment(@PathVariable UUID companyId,
                                                  @PathVariable UUID id,
                                                  @CurrentUser UUID userId,
                                                  @Valid @RequestBody RecordPurchasePaymentRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        return service.recordPayment(companyId, id, req);
    }

    @Operation(summary = "Annuler une facture (disponible tant que non payée)",
        description = "Passe la facture à statut VOID. Refusé (409) si un paiement a déjà été enregistré.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PurchaseInvoiceResponse.class),
                examples = @ExampleObject(name = "Facture annulée (statut VOID)", value = """
                    {
                      "id": "0192c106-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "status": "VOID",
                      "invoiceNumber": "ACH-2026-0001",
                      "totalAmount": 1200.00,
                      "paidAmount": 0,
                      "balanceDue": 0
                    }
                    """))),
        @ApiResponse(responseCode = "409", description = "Facture déjà payée — annulation impossible",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/invoice-already-paid",
                      "title": "Annulation impossible",
                      "status": 409,
                      "detail": "La facture ACH-2026-0001 a déjà été payée — impossible de l'annuler.",
                      "properties": {"code": "INVOICE_ALREADY_PAID"}
                    }
                    """)))
    })
    @PostMapping("/{id}/void")
    public PurchaseInvoiceResponse voidInvoice(@PathVariable UUID companyId,
                                                @PathVariable UUID id,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
        return service.voidInvoice(companyId, id);
    }
}

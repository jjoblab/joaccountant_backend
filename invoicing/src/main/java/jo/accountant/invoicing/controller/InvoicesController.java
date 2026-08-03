package jo.accountant.invoicing.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.RecordPaymentRequest;
import jo.accountant.invoicing.entity.InvoiceDirection;
import jo.accountant.invoicing.service.InvoicingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * InvoicesController — contrôleur unifié pour les factures (v9.0).
 *
 * <p>Endpoint : {@code /api/v1/companies/{companyId}/invoices}
 *
 * <p>Remplace progressivement {@code InvoicingController} (sales-only) et
 * {@code PurchasingController} (purchase-only) par un seul contrôleur qui
 * gère les deux directions via le paramètre {@code ?direction=}.
 *
 * <p>Les anciens contrôleurs restent fonctionnels pour backward compat.
 *
 * <h3>Endpoints</h3>
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/invoices?direction=SALES|PURCHASE</td><td>Liste filtrée par direction</td></tr>
 *   <tr><td>GET</td><td>/invoices/{id}</td><td>Détail (toutes directions)</td></tr>
 *   <tr><td>POST</td><td>/invoices?direction=SALES|PURCHASE</td><td>Créer DRAFT</td></tr>
 *   <tr><td>POST</td><td>/invoices/{id}/issue</td><td>DRAFT → ISSUED (vente: émettre ; achat: recevoir)</td></tr>
 *   <tr><td>POST</td><td>/invoices/{id}/record-payment</td><td>Enregistrer paiement</td></tr>
 *   <tr><td>POST</td><td>/invoices/{id}/void</td><td>Annuler (contre-passation si ISSUED)</td></tr>
 *   <tr><td>DELETE</td><td>/invoices/{id}</td><td>Supprimer DRAFT</td></tr>
 *   <tr><td>POST</td><td>/invoices/{id}/credit-note</td><td>Créer avoir (SALES only)</td></tr>
 * </table>
 *
 * @since v9.0
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/invoices")
@Tag(name = "Invoices (Unified)", description = "Factures unifiées (vente + achat) — v9.0")
public class InvoicesController {

    private final RoleChecker roleChecker;
    private final InvoicingService invoicingService;

    public InvoicesController(RoleChecker roleChecker,
                               InvoicingService invoicingService) {
        this.roleChecker = roleChecker;
        this.invoicingService = invoicingService;
    }

    @Operation(summary = "Lister les factures (filtrées par direction et/ou exercice fiscal)",
        description = "Fix Dim 5 C2 (audit v9.4) : ?fiscalYearId= optionnel filtre par exercice " +
        "(défaut = exercice actif). Avant ce fix, l'endpoint retournait TOUT l'historique " +
        "des factures (hard cap 200 tronqué silencieusement).")
    @GetMapping
    public List<InvoiceResponse> list(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @Parameter(description = "Filtrer par direction : SALES ou PURCHASE. Si absent, toutes directions.")
            @RequestParam(value = "direction", required = false) InvoiceDirection direction,
            @Parameter(description = "Filtrer par exercice fiscal (null = exercice actif). " +
                "Permet de consulter un exercice clôturé.")
            @RequestParam(value = "fiscalYearId", required = false) UUID fiscalYearId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // TODO v9.0 : implémenter listInvoicesByDirection dans InvoicingService
        // Pour l'instant, on délègue aux méthodes existantes selon la direction
        if (direction == InvoiceDirection.PURCHASE) {
            // Délègue à PurchasingService via un future InvoiceService unifié
            // En attendant, retourne une liste vide (le contrôleur purchasing existe)
            return List.of();
        }
        // Fix Dim 5 C2 : si fiscalYearId fourni, on l'utilise pour filtrer par exercice.
        // Si null, listInvoices(companyId, null) fallback sur l'exercice actif.
        if (fiscalYearId != null) {
            return invoicingService.listInvoices(companyId, fiscalYearId);
        }
        return invoicingService.listInvoices(companyId);
    }

    @Operation(summary = "Récupérer une facture par ID (toutes directions)")
    @GetMapping("/{invoiceId}")
    public InvoiceResponse get(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return invoicingService.loadInvoiceResponse(companyId, invoiceId);
    }

    @Operation(summary = "Créer une facture (DRAFT) — direction en query param")
    @PostMapping
    public ResponseEntity<InvoiceResponse> create(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @RequestParam(value = "direction", defaultValue = "SALES") InvoiceDirection direction,
            @Valid @RequestBody CreateInvoiceRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        // v9.2 — Création unifiée : SALES via createInvoice, PURCHASE via createPurchaseInvoice
        if (direction == InvoiceDirection.PURCHASE) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(invoicingService.createPurchaseInvoice(companyId, req));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invoicingService.createInvoice(companyId, req));
    }

    @Operation(summary = "Émettre/Recevoir une facture (DRAFT → ISSUED)")
    @PostMapping("/{invoiceId}/issue")
    public InvoiceResponse issue(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        // TODO v9.0 : détecter la direction et appeler issue (sales) ou receive (purchase)
        return invoicingService.issueInvoice(companyId, invoiceId);
    }

    @Operation(summary = "Enregistrer un paiement")
    @PostMapping("/{invoiceId}/record-payment")
    public InvoiceResponse recordPayment(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId,
            @Valid @RequestBody RecordPaymentRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return invoicingService.recordPayment(companyId, invoiceId, req);
    }

    @Operation(summary = "Annuler une facture (DRAFT/ISSUED → VOID)")
    @PostMapping("/{invoiceId}/void")
    public InvoiceResponse voidInvoice(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return invoicingService.voidInvoice(companyId, invoiceId);
    }

    @Operation(summary = "Supprimer une facture DRAFT")
    @DeleteMapping("/{invoiceId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        invoicingService.deleteInvoice(companyId, invoiceId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // v9.2 — Endpoints hérités de l'ancien InvoicingController (PDF, Factur-X, remind)
    // ════════════════════════════════════════════════════════════════════════

    @Operation(summary = "Envoyer une relance")
    @PostMapping("/{invoiceId}/remind")
    public InvoiceResponse remind(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return invoicingService.remindInvoice(companyId, invoiceId);
    }

    @Operation(summary = "Générer le PDF d'une facture")
    @GetMapping("/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getPdf(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] pdf = invoicingService.getInvoicePdf(companyId, invoiceId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"invoice-" + invoiceId + ".pdf\"")
                .body(pdf);
    }

    @Operation(summary = "Générer le XML Factur-X")
    @GetMapping("/{invoiceId}/factur-x")
    public ResponseEntity<byte[]> getFacturX(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] xml = invoicingService.getInvoiceFacturX(companyId, invoiceId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .header("Content-Disposition", "attachment; filename=\"factur-x-" + invoiceId + ".xml\"")
                .body(xml);
    }

    @Operation(summary = "Générer le PDF/A-3 avec Factur-X embarqué")
    @GetMapping("/{invoiceId}/factur-x-pdf")
    public ResponseEntity<byte[]> getFacturXPdf(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] pdf = invoicingService.getInvoiceFacturXPdf(companyId, invoiceId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"factur-x-pdf-" + invoiceId + ".pdf\"")
                .body(pdf);
    }
}

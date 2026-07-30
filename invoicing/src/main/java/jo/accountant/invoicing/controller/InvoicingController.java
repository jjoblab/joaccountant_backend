package jo.accountant.invoicing.controller;

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
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.RecordPaymentRequest;
import jo.accountant.invoicing.service.InvoicingService;
import jo.accountant.invoicing.signature.DocumentType;
import jo.accountant.invoicing.signature.ElectronicSignatureService;
import jo.accountant.invoicing.signature.SignatureResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de facturation (§13 Phase 12).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/invoicing")
@Tag(name = "Invoicing", description = "Facturation, avoirs (§13 Phase 12)")
public class InvoicingController {

    private final InvoicingService service;
    private final RoleChecker roleChecker;
    // R-36 (lot-F3-security) — Service de signature électronique (NoOp par défaut, XAdES si configuré)
    private final ElectronicSignatureService signatureService;

    public InvoicingController(InvoicingService service, RoleChecker roleChecker,
                                ElectronicSignatureService signatureService) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.signatureService = signatureService;
    }

    @Operation(summary = "Lister les factures (paginé)",
        description = "Filtre optionnel ?fiscalYearId= pour ne récupérer que les factures d'un " +
                      "exercice fiscal donné. Pagination via ?page=&size= (défaut 0/20, size capped " +
                      "à 200). Finding #3 — remplace la variante List<> hard-cappée à 200 pour " +
                      "éviter l'OOM sur entreprises matures.")
    @GetMapping("/invoices")
    public org.springframework.data.domain.Page<InvoiceResponse> listInvoices(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @org.springframework.web.bind.annotation.RequestParam(required = false) UUID fiscalYearId,
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0") int page,
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "20") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // Finding #3 — PageRequest cappé à 200 (empêche l'OOM si un client demande size=10000).
        org.springframework.data.domain.Pageable pageable =
            org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        return service.listInvoices(companyId, fiscalYearId, pageable);
    }

    @Operation(summary = "V7-8 — Lister les factures (pagination keyset)",
        description = "Pagination par curseur (keyset) — latence constante sur pages profondes. " +
                      "Recommandé pour les entreprises avec > 10 000 factures (Caribbean Textiles). " +
                      "Usage : ?size=50 pour la première page, puis ?size=50&afterIssueDate=...&afterId=... " +
                      "pour les pages suivantes (les valeurs sont retournées dans nextAfterIssueDate / nextAfterId).")
    @GetMapping("/invoices/keyset")
    public ResponseEntity<jo.accountant.accountingengine.dto.KeysetPage<InvoiceResponse>> listInvoicesKeyset(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                java.time.LocalDate afterIssueDate,
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID afterId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return ResponseEntity.ok(service.listInvoicesKeyset(companyId, afterIssueDate, afterId, size));
    }

    @Operation(summary = "Récupérer une facture par son ID",
        description = "Correction 2026-07-26 — endpoint nécessaire pour le deep-linking depuis " +
                      "les notifications mobile. Avant, le mobile ne pouvait récupérer une facture " +
                      "qu'en parcourant le cache local, ce qui échouait si la facture n'avait pas " +
                      "été pré-chargée.")
    @GetMapping("/invoices/{invoiceId}")
    public InvoiceResponse getInvoice(@PathVariable UUID companyId,
                                        @PathVariable UUID invoiceId,
                                        @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.loadInvoiceResponse(companyId, invoiceId);
    }

    @Operation(summary = "Créer une facture (DRAFT)")
    @PostMapping(value = "/invoices", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvoiceResponse> createInvoice(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreateInvoiceRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createInvoice(companyId, req));
    }

    @Operation(summary = "Émettre une facture (DRAFT → ISSUED)",
        description = "Attribue invoiceNumber via document-numbering, génère l'écriture " +
                      "comptable (Débit Client / Crédit Ventes + TVA).")
    @PostMapping("/invoices/{invoiceId}/issue")
    public InvoiceResponse issueInvoice(@PathVariable UUID companyId,
                                        @PathVariable UUID invoiceId,
                                        @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return service.issueInvoice(companyId, invoiceId);
    }

    /**
     * POST /invoices/{invoiceId}/remind — envoie une relance (audit event REMINDED).
     */
    @Operation(summary = "Envoyer une relance pour une facture",
        description = "Ajoute un événement d'audit REMINDED sur la facture. Déclenche aussi une notification in-app au client si son email est connu.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
        content = @io.swagger.v3.oas.annotations.media.Content(
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = InvoiceResponse.class)))
    @PostMapping("/invoices/{invoiceId}/remind")
    public InvoiceResponse remindInvoice(@PathVariable UUID companyId,
                                          @PathVariable UUID invoiceId,
                                          @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return service.remindInvoice(companyId, invoiceId);
    }

    @Operation(summary = "Enregistrer un règlement")
    @PostMapping(value = "/invoices/{invoiceId}/record-payment", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse recordPayment(@PathVariable UUID companyId,
                                         @PathVariable UUID invoiceId,
                                         @CurrentUser UUID userId,
                                         @Valid @RequestBody RecordPaymentRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return service.recordPayment(companyId, invoiceId, req);
    }

    @Operation(summary = "Créer un avoir pour une facture")
    @PostMapping(value = "/invoices/{invoiceId}/credit-note", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvoiceResponse> createCreditNote(
        @PathVariable UUID companyId, @PathVariable UUID invoiceId,
        @CurrentUser UUID userId, @Valid @RequestBody CreateInvoiceRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createCreditNote(companyId, invoiceId, req));
    }

    @Operation(summary = "Générer le PDF d'une facture",
        description = "Généré via document-generation (Phase 11). Sert le PDF directement.")
    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getInvoicePdf(@PathVariable UUID companyId,
                                                @PathVariable UUID invoiceId,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] pdf = service.getInvoicePdf(companyId, invoiceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"invoice-" + invoiceId + ".pdf\"")
            .body(pdf);
    }

    @Operation(summary = "Générer le XML Factur-X d'une facture (facturation électronique 2026)",
        description = "Audit v4.7 §4.1 Finding #5 — Génère le XML Factur-X profil BASICWL (Cross Industry Invoice D16B, " +
                      "conforme EN 16931) pour conformité Loi 2023-314 (facturation électronique obligatoire B2B France " +
                      "depuis le 1er septembre 2026). Le XML contient SellerTradeParty + BuyerTradeParty (SIRET, TVA " +
                      "intracommunautaire), ApplicableTradeTax par taux de TVA, et SpecifiedTradeSettlementHeaderMonetarySummation. " +
                      "Limitation v4.7.2 : le XML est servi séparément — l'embarquement PDF/A-3 sera finalisé en v4.8.\n\n" +
                      "**Content-Type** : `application/xml` (UTF-8). Content-Disposition : `attachment; filename=\"factur-x-{invoiceId}.xml\"`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "XML Factur-X généré (CII D16B BASICWL, EN 16931)",
            content = @Content(mediaType = "application/xml",
                examples = @ExampleObject(name = "Factur-X BASICWL", summary = "XML CII D16B (extrait)",
                    value = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                                                  xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
                                                  xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
                          <rsm:ExchangedDocumentContext>
                            <ram:GuidelineSpecifiedDocumentContextParameter>
                              <ram:ID>urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basicwl</ram:ID>
                            </ram:GuidelineSpecifiedDocumentContextParameter>
                          </rsm:ExchangedDocumentContext>
                          <rsm:ExchangedDocument>
                            <ram:ID>FAC-2026-0042</ram:ID>
                            <ram:TypeCode>380</ram:TypeCode>
                            <ram:IssueDateTime><udt:DateTimeString format="102">20260728</udt:DateTimeString></ram:IssueDateTime>
                          </rsm:ExchangedDocument>
                          <rsm:SupplyChainTradeTransaction>
                            <ram:ApplicableHeaderTradeSettlement>
                              <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                                <ram:LineTotalAmount>1000.00</ram:LineTotalAmount>
                                <ram:TaxTotalAmount currencyID="EUR">200.00</ram:TaxTotalAmount>
                                <ram:GrandTotalAmount>1200.00</ram:GrandTotalAmount>
                                <ram:DuePayableAmount>1200.00</ram:DuePayableAmount>
                              </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                            </ram:ApplicableHeaderTradeSettlement>
                          </rsm:SupplyChainTradeTransaction>
                        </rsm:CrossIndustryInvoice>
                        """))),
        @ApiResponse(responseCode = "404", description = "Facture introuvable",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Champs légaux manquants (SIRET / TVA intracomm.) — configurer via PATCH /companies/{id}/legal",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/invoices/{invoiceId}/factur-x")
    public ResponseEntity<byte[]> getInvoiceFacturX(@PathVariable UUID companyId,
                                                     @PathVariable UUID invoiceId,
                                                     @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        byte[] xml = service.getInvoiceFacturX(companyId, invoiceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/xml")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"factur-x-" + invoiceId + ".xml\"")
            .body(xml);
    }

    @Operation(summary = "Générer le PDF/A-3 avec XML Factur-X embarqué (facturation électronique 2026)",
        description = "Audit v4.7 §4.1 Finding #9 — Génère un PDF/A-3 unique contenant le rendu visuel de la " +
                      "facture ET le XML Factur-X (CII D16B) embarqué comme /EmbeddedFile (AFRelationship=/Data). " +
                      "Conforme à la spec Factur-X / EN 16931 : lisible par les humains ET parsable par le PPF/DGFiP. " +
                      "v8-2 : implémenté avec openpdf 1.4.2 (LGPL). Best-effort PDF/A-3 (XMP pdfaid:part=3/B) — " +
                      "pour une conformité PDF/A-3 strict certifiable, basculer sur iText 7 + pdfa-io (AGPL/commercial).")
    @GetMapping("/invoices/{invoiceId}/factur-x-pdf")
    public ResponseEntity<byte[]> getInvoiceFacturXPdf(@PathVariable UUID companyId,
                                                       @PathVariable UUID invoiceId,
                                                       @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        try {
            byte[] pdfA3 = service.getInvoiceFacturXPdf(companyId, invoiceId);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"factur-x-" + invoiceId + ".pdf\"")
                .body(pdfA3);
        } catch (UnsupportedOperationException ex) {
            // v8-2 : la dépendance openpdf est désormais présente, ce branch reste par sécurité
            // (au cas où la dépendance serait retirée du classpath — la feature serait désactivée
            // sans crasher l'app). Le détail est loggé côté service ; on expose un header
            // X-Error-Reason pour le client.
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .header("X-Error-Reason", "PDF_A3_FACTURX_DEPENDENCY_MISSING")
                .header(org.springframework.http.HttpHeaders.WARNING,
                    "199 - \"openpdf/iText dependency missing — see FacturXExporter.embedFacturXInPdf javadoc\"")
                .body(null);
        } catch (IllegalStateException ex) {
            // v8-2 : openpdf a échoué à embarquer le XML (PDF source corrompu, I/O error, etc.).
            // On retourne 500 avec un message clair plutôt que de propager une stack trace.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Error-Reason", "PDF_A3_FACTURX_EMBEDDING_FAILED")
                .header(org.springframework.http.HttpHeaders.WARNING,
                    "199 - \"" + ex.getMessage() + "\"")
                .body(null);
        }
    }

    /**
     * R-36 (lot-F3-security) — Signe électroniquement le PDF d'une facture.
     *
     * <p>Endpoint : {@code POST /api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/sign}.
     *
     * <p>Flow :
     * <ol>
     *   <li>Génère le PDF de la facture (via {@link InvoicingService#getInvoicePdf}).</li>
     *   <li>Appelle {@link ElectronicSignatureService#sign} avec le type {@link DocumentType#INVOICE}.</li>
     *   <li>Retourne le PDF signé avec les métadonnées de signature en headers
     *       ({@code X-Signature-Cert-Serial}, {@code X-Signature-Cert-Issuer},
     *       {@code X-Signature-Algorithm}, {@code X-Signature-Timestamp},
     *       {@code X-Signature-TSA-Timestamp}).</li>
     * </ol>
     *
     * <p><b>Comportement par défaut</b> : si aucune implémentation réelle n'est configurée
     * (XAdES désactivé), le service {@link jo.accountant.invoicing.signature.NoOpElectronicSignatureService}
     * retourne le PDF non signé avec un WARNING dans les logs. L'endpoint répond 200 OK
     * mais le PDF retourné n'a aucune valeur juridique — l'en-tête {@code X-Signature-Algorithm=noop}
     * permet au client de détecter ce cas.
     *
     * <p><b>Cadre légal</b> : Décret du 12 février 2002 (Haïti) — signature électronique
     * avec certificat qualifié = signature manuscrite. Arrêté DGI 4 octobre 2017 impose
     * la signature électronique pour les factures électroniques transmises à la DGI.
     * Voir {@code docs/ELECTRONIC_SIGNATURE.md}.
     */
    @Operation(summary = "Signer électroniquement le PDF d'une facture",
        description = "R-36 — Signe le PDF de la facture avec un certificat qualifié (XAdES/PAdES). "
            + "Cadre légal Haïti : Décret 12 février 2002 + arrêté DGI 4 octobre 2017. "
            + "Comportement par défaut : NoOp (retourne le PDF non signé avec WARNING). "
            + "Pour activer la vraie signature : app.signature.xades.enabled=true + keystore. "
            + "Voir docs/ELECTRONIC_SIGNATURE.md.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF signé (ou non signé si NoOp)",
            content = @Content(mediaType = "application/pdf",
                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER minimum)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Facture introuvable",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Facture DRAFT (non émise) — ne peut pas être signée",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "501", description = "XAdES activé mais lib de signature non intégrée (squelette)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/invoices/{invoiceId}/sign")
    public ResponseEntity<byte[]> signInvoice(@PathVariable UUID companyId,
                                                @PathVariable UUID invoiceId,
                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        // 1. Génère le PDF de la facture (peut lever ConflictException si DRAFT,
        //    NotFoundException si la facture n'existe pas ou n'appartient pas à la company).
        byte[] pdf = service.getInvoicePdf(companyId, invoiceId);
        // 2. Signe le PDF avec ElectronicSignatureService (NoOp par défaut, XAdES si activé).
        try {
            SignatureResult result = signatureService.sign(pdf, DocumentType.INVOICE, companyId);
            // 3. Retourne le PDF signé avec les métadonnées en headers.
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"invoice-" + invoiceId + "-signed.pdf\"")
                .header("X-Signature-Cert-Serial", safeHeader(result.certificateSerialNumber()))
                .header("X-Signature-Cert-Issuer", safeHeader(result.certificateIssuer()))
                .header("X-Signature-Algorithm", safeHeader(result.signatureAlgorithm()))
                .header("X-Signature-Timestamp",
                    result.signedAt() != null ? result.signedAt().toString() : "")
                .header("X-Signature-TSA-Timestamp",
                    result.tsaTimestamp() != null ? result.tsaTimestamp().toString() : "")
                .body(result.signedBytes());
        } catch (UnsupportedOperationException ex) {
            // XAdES squelette activé mais lib non intégrée — on retourne 501.
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .header("X-Error-Reason", "XADES_LIBRARY_NOT_INTEGRATED")
                .header(org.springframework.http.HttpHeaders.WARNING,
                    "199 - \"XAdES skeleton activated but xades4j/jsign library not integrated — "
                    + "see XAdESSignatureService javadoc\"")
                .body(null);
        }
    }

    /**
     * Assainit une valeur pour un header HTTP (évite les CRLF injection et tronque si trop long).
     * Les valeurs de métadonnées de certificat (issuer DN) peuvent contenir des virgules et
     * des espaces mais pas de CR/LF (interdit par RFC 7230).
     */
    private static String safeHeader(String value) {
        if (value == null) {
            return "";
        }
        // Rejeter les CR/LF (RFC 7230 — header values must not contain CR/LF)
        String safe = value.replaceAll("[\\r\\n]", "");
        // Tronquer à 256 chars pour éviter de dépasser la limite des reverse proxies (8KB header max).
        if (safe.length() > 256) {
            return safe.substring(0, 253) + "...";
        }
        return safe;
    }
}

package jo.accountant.documentgeneration.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.CompanyCountryPort;
import jo.accountant.core.port.CompanyInfoPort;
import jo.accountant.core.port.FileStoragePort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentgeneration.dto.CreateTemplateRequest;
import jo.accountant.documentgeneration.dto.GeneratedDocumentResponse;
import jo.accountant.documentgeneration.dto.TemplateResponse;
import jo.accountant.documentgeneration.entity.DocumentTemplate;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.entity.GeneratedDocument;
import jo.accountant.documentgeneration.event.DocumentGeneratedEvent;
import jo.accountant.documentgeneration.repository.DocumentTemplateRepository;
import jo.accountant.documentgeneration.repository.GeneratedDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Service de génération de documents PDF (§8, §13.
 *
 * <p>Utilise Thymeleaf pour le rendu HTML, puis openhtmltopdf pour la conversion HTML → PDF.
 * Le PDF généré est stocké via {@link FileStoragePort} avec une clé opaque.
 *
 * <p>Règles métier :
 * <ol>
 * <li>Un PDF lié à un document déjà définitif est <strong>immuable</strong> — si un
 * {@link GeneratedDocument} existe déjà pour ce resourceId, on le sert tel quel.</li>
 * <li>Logo et en-tête d'entreprise stockés via FileStoragePort, avec repli sur un
 * gabarit neutre si non configuré.</li>
 * <li>Le PDF généré doit contenir le numéro de document, les montants et le tiers —
 * testé explicitement par extraction de texte.</li>
 * </ol>
 
 *
 * @author jo@Dev


*/
@Service
public class DocumentGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentGenerationService.class);

    private final DocumentTemplateRepository templateRepository;
    private final GeneratedDocumentRepository documentRepository;
    private final FileStoragePort fileStorage;
    private final CompanyCountryPort companyCountryPort;
    private final CompanyInfoPort companyInfoPort;
    private final QrCodeService qrCodeService;
    private final ApplicationEventPublisher events;
    private final TemplateEngine stringTemplateEngine;

    public DocumentGenerationService(DocumentTemplateRepository templateRepository,
                                     GeneratedDocumentRepository documentRepository,
                                     FileStoragePort fileStorage,
                                     CompanyCountryPort companyCountryPort,
                                     CompanyInfoPort companyInfoPort,
                                     QrCodeService qrCodeService,
                                     ApplicationEventPublisher events) {
        this.templateRepository = templateRepository;
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
        this.companyCountryPort = companyCountryPort;
        this.companyInfoPort = companyInfoPort;
        this.qrCodeService = qrCodeService;
        this.events = events;

        // Configurer un TemplateEngine avec StringTemplateResolver pour les templates stockés en DB
        this.stringTemplateEngine = new TemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setOrder(1);
        this.stringTemplateEngine.addTemplateResolver(resolver);
    }

    // --- Gabarits ---

    @Transactional
    public TemplateResponse createTemplate(UUID companyId, CreateTemplateRequest req) {
        DocumentTemplate template = new DocumentTemplate();
        template.setId(UUID.randomUUID());
        template.setCompanyId(companyId);
        template.setDocumentType(req.documentType());
        template.setHtmlTemplate(req.htmlTemplate());
        template.setActive(true);
        template.setDefault(req.isDefault());
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(Instant.now());
        DocumentTemplate saved = templateRepository.save(template);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> listTemplates(UUID companyId) {
        return templateRepository.findByCompanyIdOrderByDocumentType(companyId).stream()
            .map(DocumentGenerationService::toResponse).toList();
    }

    // --- Génération ---

    /**
     * Génère un PDF de manière synchrone.
     *
     * <p>Si un {@link GeneratedDocument} existe déjà pour ce resourceId, on le sert tel
     * quel (immuabilité — règle §8). Sinon, on rend le template Thymeleaf avec les variables
     * fournies, on convertit en PDF via openhtmltopdf, on stocke via FileStoragePort.
     *
     * @param companyId identifiant du tenant
     * @param documentType type de document (INVOICE, CREDIT_NOTE, etc.)
     * @param resourceId ID de l'entité cible (ex. ID d'une facture)
     * @param variables variables Thymeleaf (numéro, montants, tiers, etc.)
     * @return le document généré avec storageKey + checksum
     */
    @Transactional
    public GeneratedDocumentResponse generateDocument(UUID companyId, GeneratedDocumentType documentType,
                                                       UUID resourceId, Map<String, Object> variables) {
        if (resourceId == null) {
            throw new ValidationException("RESOURCE_ID_REQUIRED", "resourceId est requis");
        }

        // Règle d'immuabilité : si un PDF existe déjà, le servir tel quel
        Optional<GeneratedDocument> existing = documentRepository
            .findByCompanyIdAndResourceId(companyId, resourceId);
        if (existing.isPresent()) {
            LOG.debug("Document déjà généré pour resourceId={} — sert l'existant", resourceId);
            return toResponse(existing.get());
        }

        // Trouver le gabarit : d'abord spécifique à l'entreprise, sinon global.
        // Fix Dim 3 C1 (audit v9.4) : prise en compte du country_code pour sélectionner
        // les templates Haïti (country_code='HT', mentions Code Fiscal art. 196) au lieu
        // de toujours tomber sur les templates France (country_code IS NULL).
        //
        // Ordre de résolution :
        //   1. Template spécifique à l'entreprise (companyId match)
        //   2. Template global pour le pays de l'entreprise (companyId IS NULL, country_code = pays)
        //   3. Template global historique (companyId IS NULL, country_code IS NULL = France)
        DocumentTemplate template = templateRepository
            .findByCompanyIdAndDocumentTypeAndIsDefaultTrueAndActiveTrue(companyId, documentType)
            .orElseGet(() -> {
                // Résoudre le pays de l'entreprise via le port (sans dépendre de :company)
                String countryCode = companyCountryPort.resolveCountryCode(companyId).orElse(null);
                // Étape 2 : template global pour le pays (ex. HT)
                if (countryCode != null) {
                    Optional<DocumentTemplate> byCountry = templateRepository
                        .findByCompanyIdIsNullAndDocumentTypeAndCountryCodeAndIsDefaultTrueAndActiveTrue(
                            documentType, countryCode);
                    if (byCountry.isPresent()) {
                        return byCountry.get();
                    }
                }
                // Étape 3 : fallback sur le template global historique (country_code IS NULL)
                return templateRepository
                    .findByCompanyIdIsNullAndDocumentTypeAndIsDefaultTrueAndActiveTrue(documentType)
                    .orElseThrow(() -> new ValidationException("TEMPLATE_NOT_FOUND",
                        "Aucun gabarit actif pour documentType=" + documentType
                        + " (ni spécifique à l'entreprise ni global par défaut"
                        + (countryCode != null ? " pour country=" + countryCode : "")
                        + ")"));
            });

        // Rendre le HTML avec Thymeleaf
        Context context = new Context();
        if (variables != null) {
            variables.forEach(context::setVariable);
        }

        // Fix PDF v9.4 — Injecter automatiquement companyInfo (name, address, nif, logo)
        // Avant ce fix, tous les rapports Reports Hub passait companyName = "" (vide).
        CompanyInfoPort.CompanyInfo companyInfo = companyInfoPort.resolveCompanyInfo(companyId)
            .orElse(null);
        if (companyInfo != null) {
            if (!context.containsVariable("companyName")) {
                context.setVariable("companyName", companyInfo.name() != null ? companyInfo.name() : "");
            }
            if (!context.containsVariable("companyAddress")) {
                context.setVariable("companyAddress", companyInfo.address() != null ? companyInfo.address() : "");
            }
            if (!context.containsVariable("companyNif")) {
                context.setVariable("companyNif", companyInfo.nif() != null ? companyInfo.nif() : "");
            }
            if (!context.containsVariable("companySiret")) {
                context.setVariable("companySiret", companyInfo.siret());
            }
            if (!context.containsVariable("companyVatNumber")) {
                context.setVariable("companyVatNumber", companyInfo.vatNumber());
            }
            if (!context.containsVariable("companyLogoBase64")) {
                context.setVariable("companyLogoBase64", companyInfo.logoBase64());
            }
            context.setVariable("companyCountryCode", companyInfo.countryCode());
        } else if (!context.containsVariable("companyName")) {
            context.setVariable("companyName", "");
        }

        // Fix PDF v9.4 — Injecter automatiquement un QR-code de vérification si un paymentUrl
        // est fourni dans les variables. Permet aux clients de scanner pour payer.
        if (variables != null && variables.containsKey("paymentUrl") && !context.containsVariable("qrCodeBase64")) {
            String paymentUrl = String.valueOf(variables.get("paymentUrl"));
            String qrBase64 = qrCodeService.generatePaymentUrlQrCode(paymentUrl);
            context.setVariable("qrCodeBase64", qrBase64);
        }

        // Fix PDF v9.4 — Injecter la date de génération et le numéro de page (pour @page CSS)
        if (!context.containsVariable("generationDate")) {
            context.setVariable("generationDate", java.time.LocalDate.now().toString());
        }

        String html = stringTemplateEngine.process(template.getHtmlTemplate(), context);

        // Convertir HTML → PDF via openhtmltopdf
        byte[] pdfBytes = renderPdf(html);

        // Calculer le checksum SHA-256
        String checksum = sha256(pdfBytes);

        // Stocker le PDF via FileStoragePort
        String storageKey = fileStorage.store(pdfBytes, "application/pdf", "pdf");

        // Créer l'enregistrement GeneratedDocument
        GeneratedDocument doc = new GeneratedDocument();
        doc.setCompanyId(companyId);
        doc.setDocumentType(documentType);
        doc.setResourceId(resourceId);
        doc.setStorageKey(storageKey);
        doc.setGeneratedAt(Instant.now());
        doc.setGeneratedBy(TenantContext.getUserId());
        doc.setChecksum(checksum);
        GeneratedDocument saved = documentRepository.save(doc);

        events.publishEvent(new DocumentGeneratedEvent(saved, TenantContext.getUserId()));
        LOG.info("Document généré : type={} resourceId={} storageKey={} checksum={}",
            documentType, resourceId, storageKey, checksum.substring(0, 12) + "...");

        return toResponse(saved);
    }

    /**
     * Récupère le contenu PDF d'un document déjà généré.
     *
     * @return les bytes du PDF
     * @throws NotFoundException si aucun document n'existe pour ce resourceId
     */
    @Transactional(readOnly = true)
    public byte[] getDocumentContent(UUID companyId, UUID resourceId) {
        GeneratedDocument doc = documentRepository
            .findByCompanyIdAndResourceId(companyId, resourceId)
            .orElseThrow(() -> new NotFoundException("GeneratedDocument",
                "Aucun document généré pour resourceId=" + resourceId));
        return fileStorage.load(doc.getStorageKey());
    }

    /**
     * Récupère les métadonnées d'un document déjà généré (sans le contenu).
     */
    @Transactional(readOnly = true)
    public GeneratedDocumentResponse getDocument(UUID companyId, UUID resourceId) {
        GeneratedDocument doc = documentRepository
            .findByCompanyIdAndResourceId(companyId, resourceId)
            .orElseThrow(() -> new NotFoundException("GeneratedDocument",
                "Aucun document généré pour resourceId=" + resourceId));
        return toResponse(doc);
    }

    /**
     * Fix PDF v9.4 — Prévisualisation PDF (génère un PDF sans le persister).
     *
     * <p>Contrairement à {@link #generateDocument}, cette méthode :
     * <ul>
     *   <li>Ne vérifie pas l'immuabilité (pas de resourceId)</li>
     *   <li>Ne crée pas de {@link GeneratedDocument}</li>
     *   <li>Ne stocke pas via {@link FileStoragePort}</li>
     *   <li>Retourne directement les bytes du PDF</li>
     * </ul>
     *
     * @param companyId identifiant du tenant
     * @param documentType type de document à prévisualiser
     * @param variables variables Thymeleaf (peuvent être vides pour un aperçu minimal)
     * @return les bytes du PDF généré
     */
    @Transactional(readOnly = true)
    public byte[] previewDocument(UUID companyId, GeneratedDocumentType documentType,
                                    Map<String, Object> variables) {
        // Résoudre le template (même logique que generateDocument)
        String countryCode = companyCountryPort.resolveCountryCode(companyId).orElse(null);
        DocumentTemplate template = templateRepository
            .findByCompanyIdAndDocumentTypeAndIsDefaultTrueAndActiveTrue(companyId, documentType)
            .orElseGet(() -> {
                if (countryCode != null) {
                    Optional<DocumentTemplate> byCountry = templateRepository
                        .findByCompanyIdIsNullAndDocumentTypeAndCountryCodeAndIsDefaultTrueAndActiveTrue(
                            documentType, countryCode);
                    if (byCountry.isPresent()) {
                        return byCountry.get();
                    }
                }
                return templateRepository
                    .findByCompanyIdIsNullAndDocumentTypeAndIsDefaultTrueAndActiveTrue(documentType)
                    .orElseThrow(() -> new ValidationException("TEMPLATE_NOT_FOUND",
                        "Aucun gabarit actif pour documentType=" + documentType));
            });

        // Rendre le HTML avec Thymeleaf (même injection que generateDocument)
        Context context = new Context();
        if (variables != null) {
            variables.forEach(context::setVariable);
        }
        CompanyInfoPort.CompanyInfo companyInfo = companyInfoPort.resolveCompanyInfo(companyId)
            .orElse(null);
        if (companyInfo != null) {
            context.setVariable("companyName", companyInfo.name() != null ? companyInfo.name() : "");
            context.setVariable("companyAddress", companyInfo.address() != null ? companyInfo.address() : "");
            context.setVariable("companyNif", companyInfo.nif() != null ? companyInfo.nif() : "");
            context.setVariable("companyLogoBase64", companyInfo.logoBase64());
        }
        context.setVariable("generationDate", java.time.LocalDate.now().toString());

        String html = stringTemplateEngine.process(template.getHtmlTemplate(), context);
        return renderPdf(html);
    }

    // --- Helpers ---

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // Fix PDF v9.4 — CSS "Corporate sobre" centralisé (généré via StringBuilder pour
            // éviter les problèmes d'échappement avec les text blocks Java).
            String today = java.time.LocalDate.now().toString();
            String css = buildCorporateCss(today);
            String fullHtml = "<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
                + "<head>\n"
                + "    <meta charset=\"UTF-8\"/>\n"
                + "    <style>\n" + css + "\n    </style>\n"
                + "</head>\n"
                + "<body>\n"
                + html
                + "\n</body>\n"
                + "</html>";
            builder.useFastMode();
            builder.withHtmlContent(fullHtml, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render PDF", e);
        }
    }

    /**
     * Fix PDF v9.4 — Génère le CSS "Corporate sobre" centralisé.
     *
     * <p>Style : Bleu marine (#1a3a5c) + gris (#6c757d) + espace blanc.
     * Inclut : @page (numéros de page), tableaux zébrés, filigrane, QR-code, classes utilitaires.
     */
    private String buildCorporateCss(String today) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("/* @page : marges A4 + numéros de page */\n");
        sb.append("@page {\n");
        sb.append("    size: A4;\n");
        sb.append("    margin: 25mm 18mm 22mm 18mm;\n");
        sb.append("    @bottom-right {\n");
        sb.append("        content: \"Page \" counter(page) \" / \" counter(pages);\n");
        sb.append("        font-family: 'Helvetica', sans-serif; font-size: 8pt; color: #6c757d;\n");
        sb.append("    }\n");
        sb.append("    @bottom-left {\n");
        sb.append("        content: \"Genere le ").append(today).append("\";\n");
        sb.append("        font-family: 'Helvetica', sans-serif; font-size: 8pt; color: #6c757d;\n");
        sb.append("    }\n");
        sb.append("}\n\n");

        sb.append("/* Base */\n");
        sb.append("* { box-sizing: border-box; }\n");
        sb.append("body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 10pt; line-height: 1.5; color: #212529; margin: 0; padding: 0; }\n");
        sb.append("h1 { font-size: 18pt; color: #1a3a5c; margin: 0 0 8pt 0; font-weight: 700; }\n");
        sb.append("h2 { font-size: 14pt; color: #1a3a5c; margin: 16pt 0 6pt 0; font-weight: 600; }\n");
        sb.append("h3 { font-size: 11pt; color: #1a3a5c; margin: 12pt 0 4pt 0; font-weight: 600; }\n");
        sb.append("p { margin: 0 0 6pt 0; }\n");
        sb.append("small { font-size: 8pt; color: #6c757d; }\n\n");

        sb.append("/* En-tete entreprise */\n");
        sb.append(".doc-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20pt; padding-bottom: 10pt; border-bottom: 2pt solid #1a3a5c; }\n");
        sb.append(".doc-header .company-logo { max-height: 50pt; max-width: 150pt; object-fit: contain; }\n");
        sb.append(".doc-header .company-info { text-align: right; font-size: 9pt; color: #6c757d; }\n");
        sb.append(".doc-header .company-info .name { font-size: 11pt; font-weight: 700; color: #1a3a5c; }\n\n");

        sb.append("/* Pied de page */\n");
        sb.append(".doc-footer { margin-top: 20pt; padding-top: 8pt; border-top: 1pt solid #dee2e6; font-size: 8pt; color: #6c757d; text-align: center; }\n");
        sb.append(".mention-legal { font-size: 8pt; color: #6c757d; margin-top: 12pt; padding: 6pt 8pt; background-color: #f8f9fa; border-left: 3pt solid #1a3a5c; }\n\n");

        sb.append("/* Tableaux : lignes zebrees + en-tete bleu marine */\n");
        sb.append("table { width: 100%; border-collapse: collapse; margin: 10pt 0; font-size: 9pt; }\n");
        sb.append("thead th { background-color: #1a3a5c; color: white; font-weight: 600; text-align: left; padding: 7pt 8pt; border: none; }\n");
        sb.append("tbody td { padding: 6pt 8pt; border-bottom: 1pt solid #e9ecef; }\n");
        sb.append("tbody tr:nth-child(even) { background-color: #f8f9fa; }\n");
        sb.append("tfoot td { padding: 7pt 8pt; border-top: 2pt solid #1a3a5c; font-weight: 700; background-color: #f8f9fa; }\n");
        sb.append(".totals-row td { font-size: 10pt; color: #1a3a5c; }\n\n");

        sb.append("/* Classes utilitaires */\n");
        sb.append(".text-right { text-align: right; }\n");
        sb.append(".text-center { text-align: center; }\n");
        sb.append(".text-left { text-align: left; }\n");
        sb.append(".amount { font-family: 'Courier', monospace; text-align: right; white-space: nowrap; }\n");
        sb.append(".highlight { background-color: #fff3cd; padding: 2pt 4pt; }\n");
        sb.append(".badge { display: inline-block; padding: 2pt 8pt; font-size: 8pt; font-weight: 700; border-radius: 3pt; color: white; }\n");
        sb.append(".badge-success { background-color: #198754; }\n");
        sb.append(".badge-warning { background-color: #ffc107; color: #212529; }\n");
        sb.append(".badge-danger { background-color: #dc3545; }\n");
        sb.append(".badge-info { background-color: #0dcaf0; color: #212529; }\n\n");

        sb.append("/* Box info (callout) */\n");
        sb.append(".info-box { padding: 10pt 12pt; margin: 10pt 0; border-radius: 4pt; font-size: 9pt; }\n");
        sb.append(".info-box-primary { background-color: #e7f1ff; border-left: 4pt solid #0d6efd; }\n");
        sb.append(".info-box-success { background-color: #d1e7dd; border-left: 4pt solid #198754; }\n");
        sb.append(".info-box-warning { background-color: #fff3cd; border-left: 4pt solid #ffc107; }\n\n");

        sb.append("/* Filigrane statut */\n");
        sb.append(".watermark { position: fixed; top: 40%; left: 50%; transform: translate(-50%, -50%) rotate(-45deg); font-size: 80pt; font-weight: 900; color: rgba(26, 58, 92, 0.08); z-index: -1; pointer-events: none; white-space: nowrap; text-transform: uppercase; letter-spacing: 8pt; }\n\n");

        sb.append("/* QR-code */\n");
        sb.append(".qr-code { max-width: 100pt; max-height: 100pt; margin: 8pt 0; }\n");
        sb.append(".qr-section { display: flex; align-items: center; gap: 12pt; margin: 10pt 0; padding: 8pt; background-color: #f8f9fa; border-radius: 4pt; }\n");
        sb.append(".qr-section .qr-text { font-size: 8pt; color: #6c757d; }\n\n");

        sb.append("/* Sauts de page */\n");
        sb.append(".page-break { page-break-before: always; }\n");
        sb.append(".avoid-break { page-break-inside: avoid; }\n\n");

        sb.append("/* Document title block */\n");
        sb.append(".doc-title-block { text-align: center; margin: 0 0 16pt 0; padding: 12pt; background-color: #1a3a5c; color: white; border-radius: 4pt; }\n");
        sb.append(".doc-title-block h1 { color: white; margin: 0; }\n");
        sb.append(".doc-title-block .subtitle { font-size: 10pt; opacity: 0.9; margin-top: 4pt; }\n\n");

        sb.append("/* Two-column layout */\n");
        sb.append(".two-column { display: flex; gap: 16pt; }\n");
        sb.append(".two-column .col { flex: 1; }\n\n");

        sb.append("/* Signature block */\n");
        sb.append(".signature-block { display: flex; justify-content: space-between; margin-top: 40pt; }\n");
        sb.append(".signature-block .sig { text-align: center; font-size: 9pt; color: #6c757d; width: 45%; }\n");
        sb.append(".signature-block .sig-line { border-top: 1pt solid #212529; margin-top: 40pt; padding-top: 4pt; }\n");

        return sb.toString();
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SHA-256", e);
        }
    }

    private static TemplateResponse toResponse(DocumentTemplate t) {
        return new TemplateResponse(t.getId(), t.getCompanyId(), t.getDocumentType(),
            t.isActive(), t.isDefault(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private static GeneratedDocumentResponse toResponse(GeneratedDocument d) {
        return new GeneratedDocumentResponse(d.getId(), d.getCompanyId(), d.getDocumentType(),
            d.getResourceId(), d.getStorageKey(), d.getGeneratedAt(), d.getGeneratedBy(),
            d.getChecksum());
    }
}

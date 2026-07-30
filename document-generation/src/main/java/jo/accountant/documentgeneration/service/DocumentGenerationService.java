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
import jo.accountant.core.port.FileStoragePort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentgeneration.dto.CreateTemplateRequest;
import jo.accountant.documentgeneration.dto.GeneratedDocumentResponse;
import jo.accountant.documentgeneration.dto.TemplateResponse;
import jo.accountant.documentgeneration.entity.DocumentTemplate;
import jo.accountant.documentgeneration.entity.DocumentType;
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
 * Service de génération de documents PDF (§8, §13 Phase 11).
 *
 * <p>Utilise Thymeleaf pour le rendu HTML, puis openhtmltopdf pour la conversion HTML → PDF.
 * Le PDF généré est stocké via {@link FileStoragePort} avec une clé opaque.
 *
 * <p>Règles métier :
 * <ol>
 *   <li>Un PDF lié à un document déjà définitif est <strong>immuable</strong> — si un
 *       {@link GeneratedDocument} existe déjà pour ce resourceId, on le sert tel quel.</li>
 *   <li>Logo et en-tête d'entreprise stockés via FileStoragePort, avec repli sur un
 *       gabarit neutre si non configuré.</li>
 *   <li>Le PDF généré doit contenir le numéro de document, les montants et le tiers —
 *       testé explicitement par extraction de texte.</li>
 * </ol>
 */
@Service
public class DocumentGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentGenerationService.class);

    private final DocumentTemplateRepository templateRepository;
    private final GeneratedDocumentRepository documentRepository;
    private final FileStoragePort fileStorage;
    private final ApplicationEventPublisher events;
    private final TemplateEngine stringTemplateEngine;

    public DocumentGenerationService(DocumentTemplateRepository templateRepository,
                                     GeneratedDocumentRepository documentRepository,
                                     FileStoragePort fileStorage,
                                     ApplicationEventPublisher events) {
        this.templateRepository = templateRepository;
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
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
    public GeneratedDocumentResponse generateDocument(UUID companyId, DocumentType documentType,
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

        // Trouver le gabarit : d'abord spécifique à l'entreprise, sinon global
        DocumentTemplate template = templateRepository
            .findByCompanyIdAndDocumentTypeAndIsDefaultTrueAndActiveTrue(companyId, documentType)
            .orElseGet(() -> templateRepository
                .findByCompanyIdIsNullAndDocumentTypeAndIsDefaultTrueAndActiveTrue(documentType)
                .orElseThrow(() -> new ValidationException("TEMPLATE_NOT_FOUND",
                    "Aucun gabarit actif pour documentType=" + documentType
                    + " (ni spécifique à l'entreprise ni global par défaut)")));

        // Rendre le HTML avec Thymeleaf
        Context context = new Context();
        if (variables != null) {
            variables.forEach(context::setVariable);
        }
        // Ajouter des variables par défaut (en-tête neutre)
        if (!variables.containsKey("companyName")) {
            context.setVariable("companyName", "");
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

    // --- Helpers ---

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // Envelopper le HTML dans une structure complète avec CSS minimal
            String fullHtml = """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <meta charset="UTF-8"/>
                    <style>
                        body { font-family: 'Helvetica', sans-serif; font-size: 11pt; margin: 2cm; }
                        h1 { font-size: 16pt; color: #333; }
                        table { width: 100%; border-collapse: collapse; margin-top: 1cm; }
                        th, td { border: 1px solid #ddd; padding: 6px; text-align: left; }
                        th { background-color: #f5f5f5; }
                        .header { margin-bottom: 1cm; }
                        .footer { margin-top: 2cm; font-size: 9pt; color: #999; }
                    </style>
                </head>
                <body>
                """ + html + """
                </body>
                </html>
                """;
            builder.useFastMode();
            builder.withHtmlContent(fullHtml, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render PDF", e);
        }
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

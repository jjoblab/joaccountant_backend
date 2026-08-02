package jo.accountant.documentgeneration.util;

import java.util.Map;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Helper utilitaire pour les endpoints PDF des controllers Reports Hub (0-task8).
 *
 * <p>Extrait le boilerplate commun aux 12+ endpoints PDF qui suivent le même pattern
 * introduit antérieurement :
 * <ol>
 * <li>génère un {@code resourceId = UUID.randomUUID()} (contournement de la règle
 * d'immuabilité de {@link DocumentGenerationService#generateDocument} — voir
 * commentaire dans {@code FinancialStatementsController.getBalanceSheetPdf}) ;</li>
 * <li>appelle {@link DocumentGenerationService#generateDocument} pour le rendu
 * Thymeleaf → openhtmltopdf et le stockage via {@code FileStoragePort} ;</li>
 * <li>appelle {@link DocumentGenerationService#getDocumentContent} pour récupérer
 * les bytes du PDF ;</li>
 * <li>construit un {@link ResponseEntity} avec les headers
 * {@code Content-Type: application/pdf} et
 * {@code Content-Disposition: attachment; filename="<filename>"}.</li>
 * </ol>
 *
 * <p><strong>Behavior preservation</strong> — ne wrappe PAS les exceptions dans un
 * {@code ResponseStatusException(500)}. Les controllers originaux laissent les
 * {@code ValidationException} (TEMPLATE_NOT_FOUND → 422) et autres remonter au
 * {@code GlobalExceptionHandler} ; les wrapper changerait le code HTTP et violerait
 * la contrainte « DO NOT change the endpoint behavior ».
 *
 * <p><strong>Écart par rapport à la spécification</strong> — la task spec
 * propose une signature {@code generatePdf(docService, type, contextMap, filename)}
 * sans {@code companyId}, mais {@link DocumentGenerationService#generateDocument}
 * exige un {@code companyId} (tenant) en premier argument. On ajoute donc
 * {@code companyId} à la signature du helper.
 *
 * @see CsvEndpointHelper pour le symétrique CSV.
 
 *
 * @author jo@Dev


*/
public final class PdfEndpointHelper {

    private PdfEndpointHelper() {
        // utilitaire — pas d'instanciation
    }

    /**
     * Génère un PDF via {@link DocumentGenerationService} et retourne un
     * {@link ResponseEntity} binaire prêt à être retourné par le controller.
     *
     * @param docService le service de génération de documents (injecté dans le controller)
     * @param companyId identifiant du tenant (passé au service pour lookup de template + RLS)
     * @param type type de document (détermine le template Thymeleaf à utiliser)
     * @param contextMap variables Thymeleaf (DTO + dates + companyName + totaux…) — spécifique au domaine
     * @param filename nom de fichier pour l'en-tête {@code Content-Disposition}
     * (ex. {@code "bilan-<companyId>-<period>.pdf"})
     * @return {@code 200 OK} avec body PDF binaire et en-têtes
     * {@code Content-Type: application/pdf} +
     * {@code Content-Disposition: attachment; filename="<filename>"}
     */
    public static ResponseEntity<byte[]> generatePdf(DocumentGenerationService docService,
                                                     UUID companyId,
                                                     GeneratedDocumentType type,
                                                     Map<String, Object> contextMap,
                                                     String filename) {
        // Règle d'immuabilité contournée : on passe un UUID aléatoire comme resourceId
        // pour forcer la régénération à chaque appel (les données sous-jacentes
        // peuvent changer tant que l'exercice n'est pas clôturé).
        UUID resourceId = UUID.randomUUID();
        docService.generateDocument(companyId, type, resourceId, contextMap);
        byte[] pdf = docService.getDocumentContent(companyId, resourceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }
}

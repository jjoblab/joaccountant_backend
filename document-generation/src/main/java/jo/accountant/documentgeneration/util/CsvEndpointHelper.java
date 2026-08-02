package jo.accountant.documentgeneration.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Helper utilitaire pour les endpoints CSV des controllers Reports Hub (0-task8).
 *
 * <p>Extrait le boilerplate commun aux 3 endpoints CSV (1) :
 * <ol>
 * <li>préfixe le contenu CSV avec le <strong>BOM UTF-8</strong> ({@code EF BB BF})
 * pour compatibilité Excel français (sinon Excel ouvre le CSV en ANSI et les
 * accents sont mojibakisés) ;</li>
 * <li>construit un {@link ResponseEntity} avec les headers
 * {@code Content-Type: text/csv; charset=UTF-8} et
 * {@code Content-Disposition: attachment; filename="<filename>"}.</li>
 * </ol>
 *
 * <p>Le contrôleur reste responsable de la génération du contenu CSV (séparateur
 * point-virgule, CRLF {@code \r\n}, colonnes spécifiques au domaine) — le helper
 * ne fait que le BOM + les headers.
 *
 * <p><strong>Behavior preservation</strong> — reproduit exactement le pattern
 * historique : {@code ByteArrayOutputStream.write(0xEF); write(0xBB); write(0xBF);}
 * suivi de {@code "text/csv; charset=UTF-8"} et
 * {@code "attachment; filename=\"" + filename + "\""}. Les tests d'intégration
 * ({@code ChartOfAccountsCsvIntegrationTest}, {@code LettrageAndCsvIntegrationTest},
 * {@code AccountingEnginePdfCsvIntegrationTest}) assertent un Content-Type
 * <em>compatible with</em> {@code text/csv} et un Content-Disposition qui commence
 * par {@code "attachment; filename="}.
 *
 * <p><strong>Note arch</strong> — ce helper vit dans {@code :document-generation} bien
 * qu'il ne dépende que de Spring Web. C'est pour co-localiser les deux helpers
 * PDF/CSV des endpoints Reports Hub (0-task8) et parce qu'aucune règle ArchUnit
 * n'interdit à un module en aval (ex. :chart-of-accounts) de dépendre de
 * {@code :document-generation} (Rule 24 n'interdit que la direction inverse).
 
 *
 * @author jo@Dev


*/
public final class CsvEndpointHelper {

    /** BOM UTF-8 ({@code U+FEFF}) — 3 bytes {@code EF BB BF} pour compatibilité Excel FR. */
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvEndpointHelper() {
        // utilitaire — pas d'instanciation
    }

    /**
     * Construit un {@link ResponseEntity} CSV binaire à partir du contenu CSV textuel.
     *
     * <p>Le contenu est encodé en UTF-8 puis préfixé du BOM UTF-8 (3 bytes
     * {@code EF BB BF}) pour qu'Excel France ouvre le fichier avec le bon charset.
     *
     * @param csvContent contenu CSV textuel (le contrôleur doit déjà utiliser
     * {@code \r\n} comme séparateur de lignes et {@code ;} comme
     * séparateur de colonnes — le helper ne modifie pas le contenu)
     * @param filename nom de fichier pour l'en-tête {@code Content-Disposition}
     * (ex. {@code "ecritures-<companyId>-<period>.csv"})
     * @return {@code 200 OK} avec body CSV binaire (BOM + contenu UTF-8) et en-têtes
     * {@code Content-Type: text/csv; charset=UTF-8} +
     * {@code Content-Disposition: attachment; filename="<filename>"}
     */
    public static ResponseEntity<byte[]> buildCsvResponse(String csvContent, String filename) {
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[UTF8_BOM.length + bytes.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(bytes, 0, withBom, UTF8_BOM.length, bytes.length);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(withBom);
    }
}

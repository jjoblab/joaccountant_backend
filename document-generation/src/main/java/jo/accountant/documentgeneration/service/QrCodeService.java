package jo.accountant.documentgeneration.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fix PDF v9.4 — Service de génération de QR-codes pour les PDF.
 *
 * <p>Utilise ZXing (licence Apache 2.0) pour générer des QR-codes PNG encodés en base64,
 * directement embarqués dans les templates HTML via {@code data:image/png;base64,...}.
 *
 * <p>Cas d'usage :
 * <ul>
 *   <li><b>QR-code de paiement</b> sur les factures (format EPC SEPA ou URL de paiement locale)</li>
 *   <li><b>QR-code de vérification</b> sur les reçus de don (URL de vérification d'authenticité)</li>
 *   <li><b>QR-code d'identification</b> sur les bulletins de paie (NIF employé + période)</li>
 * </ul>
 *
 * <p>Le QR-code est généré en 200×200 px par défaut (suffisant pour scan mobile), avec
 * correction d'erreur niveau M (15% de redondance).
 *
 * @author jo@Dev
 */
@Service
public class QrCodeService {

    private static final Logger LOG = LoggerFactory.getLogger(QrCodeService.class);
    private static final int DEFAULT_SIZE = 200;

    /**
     * Génère un QR-code à partir d'un texte et retourne le PNG en base64 (sans préfixe data:).
     *
     * @param content le contenu à encoder (ex: "https://pay.example.com/invoice/123",
     *                ou payload EPC SEPA, ou NIF+période)
     * @return le PNG encodé en base64, ou null si la génération échoue
     */
    public String generateQrCodeBase64(String content) {
        return generateQrCodeBase64(content, DEFAULT_SIZE);
    }

    /**
     * Génère un QR-code avec une taille personnalisée.
     *
     * @param content le contenu à encoder
     * @param size taille en pixels (carré)
     * @return le PNG encodé en base64, ou null si la génération échoue
     */
    public String generateQrCodeBase64(String content, int size) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new MultiFormatWriter()
                .encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", os);
            return Base64.getEncoder().encodeToString(os.toByteArray());
        } catch (Exception e) {
            LOG.warn("Échec génération QR-code (content='{}...') : {}",
                content.substring(0, Math.min(50, content.length())), e.getMessage());
            return null;
        }
    }

    /**
     * Génère un QR-code de paiement EPC SEPA (European Payments Council Quick Response Code).
     *
     * <p>Format EPC : permet à un client de scanner le QR-code avec son app bancaire pour
     * pré-remplir un virement SEPA. Format standardisé pour l'Europe.
     *
     * <p>Pour Haïti, on utilise plutôt une URL de paiement (mobile money, etc.) — voir
     * {@link #generatePaymentUrlQrCode(String, String, String)}.
     *
     * @param iban IBAN du bénéficiaire (ex: "FR7612345678901234567890123")
     * @param name nom du bénéficiaire
     * @param amount montant (ex: "150.00")
     * @param reference référence de paiement (ex: numéro de facture)
     * @return le PNG encodé en base64, ou null si échec
     */
    public String generateEpcQrCode(String iban, String name, String amount, String reference) {
        // Format EPC 002 v2.1 (Quick Response Code: Guidelines to Enable Data Capture
        // for the Initiation of a SEPA Credit Transfer)
        StringBuilder epc = new StringBuilder();
        epc.append("BCD\n");           // Service tag
        epc.append("002\n");           // Version
        epc.append("1\n");             // Character set (UTF-8)
        epc.append("SCT\n");           // Identification (SEPA Credit Transfer)
        epc.append(name != null ? name : "").append("\n");
        epc.append(iban != null ? iban : "").append("\n");
        epc.append("EUR").append("\n"); // Currency
        epc.append(amount != null ? amount : "").append("\n");
        epc.append("\n");              // Remittance reference (empty)
        epc.append(reference != null ? reference : "").append("\n");
        epc.append("\n");              // Remittance text
        epc.append("1\n");             // Purpose
        return generateQrCodeBase64(epc.toString());
    }

    /**
     * Génère un QR-code contenant une URL de paiement (pour Haïti / mobile money).
     *
     * @param paymentUrl URL complète de paiement (ex: "https://natcash.me/pay/inv/12345")
     * @return le PNG encodé en base64, ou null si échec
     */
    public String generatePaymentUrlQrCode(String paymentUrl) {
        return generateQrCodeBase64(paymentUrl);
    }
}

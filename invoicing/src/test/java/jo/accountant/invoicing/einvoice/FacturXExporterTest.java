package jo.accountant.invoicing.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link FacturXExporter} — R-16 (lot-D-qualite-arch).
 *
 * <p>Couverture de la génération XML Factur-X BASICWL (CII D16B). Pas de Spring, pas de
 * Mockito : le composant n'a aucune dépendance injectée.
 *
 * <p>Scénarios :
 * <ul>
 *   <li>Cas nominal : facture complète → XML valide contenant les éléments clés.</li>
 *   <li>Cas edge : deliveryDate null → utilise issueDate.</li>
 *   <li>Cas edge : buyerReference null → valeur "N/A".</li>
 *   <li>v8-2 : embedFacturXInPdf — embarque réellement le XML dans un PDF (openpdf 1.4.2).</li>
 *   <li>v8-2 : embedFacturXInPdf — arguments invalides → IllegalArgumentException.</li>
 *   <li>v8-2 : embedFacturXInPdf — PDF source corrompu → IllegalStateException.</li>
 * </ul>
 */
class FacturXExporterTest {

    private FacturXExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new FacturXExporter();
    }

    private FacturXExporter.FacturXInvoice buildInvoice() {
        return new FacturXExporter.FacturXInvoice(
            "INV-2026-001",
            LocalDate.of(2026, 3, 15),
            LocalDate.of(2026, 3, 14),
            "EUR",
            new FacturXExporter.TradeParty(
                "FR12345678901", "JOAccountant SARL", "FR12345678901",
                "12345678900015", "12 rue de la Paix, Paris", "FR"),
            new FacturXExporter.TradeParty(
                "FR98765432109", "Client SAS", "FR98765432109",
                "98765432100012", "5 avenue des Champs, Lyon", "FR"),
            "CLIENT-REF-001",
            new BigDecimal("1000.00"),
            new BigDecimal("200.00"),
            new BigDecimal("1200.00"),
            List.of(new FacturXExporter.TaxBreakdown(
                new BigDecimal("20.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("200.00")))
        );
    }

    @Test
    @DisplayName("exportFacturXBasicWL — nominal : génère un XML contenant les éléments clés")
    void exportFacturXBasicWL_nominal() {
        byte[] xml = exporter.exportFacturXBasicWL(buildInvoice());
        assertThat(xml).isNotEmpty();

        String xmlStr = new String(xml, StandardCharsets.UTF_8);

        // Élément racine CII D16B
        assertThat(xmlStr).contains("CrossIndustryInvoice");
        // Profil Factur-X BASICWL
        assertThat(xmlStr).contains("urn:fnfe-mpe.org:factur-x:1.0.07:basicwl");
        // Numéro de facture
        assertThat(xmlStr).contains("INV-2026-001");
        // TypeCode 380 (Commercial Invoice)
        assertThat(xmlStr).contains("<ram:TypeCode>380</ram:TypeCode>");
        // Parties seller / buyer
        assertThat(xmlStr).contains("JOAccountant SARL");
        assertThat(xmlStr).contains("Client SAS");
        // Buyer reference
        assertThat(xmlStr).contains("CLIENT-REF-001");
        // Devise
        assertThat(xmlStr).contains("EUR");
        // NIF HT (numéro de TVA — SIRET doit aussi apparaître)
        assertThat(xmlStr).contains("FR12345678901");
        assertThat(xmlStr).contains("FR98765432109");
        // TVA 20% — rate applicable percent
        assertThat(xmlStr).contains("20.00");
        // Total
        assertThat(xmlStr).contains("1200.00");
    }

    @Test
    @DisplayName("exportFacturXBasicWL — edge : deliveryDate null → utilise issueDate")
    void exportFacturXBasicWL_deliveryDateNull() {
        FacturXExporter.FacturXInvoice invoice = buildInvoice();
        // Recréer l'invoice avec deliveryDate = null via le constructeur
        FacturXExporter.FacturXInvoice noDelivery = new FacturXExporter.FacturXInvoice(
            invoice.invoiceNumber(), invoice.issueDate(), null, invoice.currency(),
            invoice.seller(), invoice.buyer(), invoice.buyerReference(),
            invoice.subtotal(), invoice.taxAmount(), invoice.totalAmount(),
            invoice.taxBreakdowns());

        byte[] xml = exporter.exportFacturXBasicWL(noDelivery);
        String xmlStr = new String(xml, StandardCharsets.UTF_8);

        // ActualDeliverySupplyChainEvent doit contenir la date d'émission (15/03/2026) comme fallback
        assertThat(xmlStr).contains("2026-03-15");
    }

    @Test
    @DisplayName("exportFacturXBasicWL — edge : buyerReference null → valeur \"N/A\"")
    void exportFacturXBasicWL_buyerReferenceNull() {
        FacturXExporter.FacturXInvoice invoice = new FacturXExporter.FacturXInvoice(
            "INV-2026-002", LocalDate.of(2026, 3, 15), null, "EUR",
            buildInvoice().seller(), buildInvoice().buyer(), null,
            new BigDecimal("500.00"), new BigDecimal("100.00"), new BigDecimal("600.00"),
            List.of(new FacturXExporter.TaxBreakdown(
                new BigDecimal("20.00"), new BigDecimal("500.00"), new BigDecimal("100.00"))));

        byte[] xml = exporter.exportFacturXBasicWL(invoice);
        String xmlStr = new String(xml, StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("<ram:BuyerReference>N/A</ram:BuyerReference>");
    }

    @Test
    @DisplayName("v8-2 — embedFacturXInPdf : embarque le XML dans un PDF source valide (openpdf 1.4.2)")
    void embedFacturXInPdf_embedsXmlIntoValidPdf() {
        // Given : un PDF source minimal généré par openpdf (simule le PDF openhtmltopdf produit
        // par :document-generation) + un XML Factur-X BASICWL.
        byte[] pdfSource = FacturXExporter.generateMinimalPdfForTest();
        assertThat(pdfSource).isNotEmpty();
        assertThat(new String(pdfSource, StandardCharsets.US_ASCII)).startsWith("%PDF-");

        byte[] xmlBytes = exporter.exportFacturXBasicWL(buildInvoice());

        // When : embarquement
        byte[] pdfResult = exporter.embedFacturXInPdf(pdfSource, xmlBytes);

        // Then : le PDF résultant est non vide, plus gros que le source (contient le XML + l'embedded file dict),
        // démarre par %PDF- (signature PDF valide) et contient le nom de fichier standardisé "factur-x.xml".
        assertThat(pdfResult).isNotEmpty();
        assertThat(pdfResult.length).isGreaterThan(pdfSource.length);
        String pdfAscii = new String(pdfResult, StandardCharsets.ISO_8859_1);
        assertThat(pdfAscii).startsWith("%PDF-");
        // Le nom de fichier standardisé Factur-X doit apparaître dans le PDF (dans la file spec dict).
        assertThat(pdfAscii).contains("factur-x.xml");
        // Le MIME type est URL-encodé par openpdf ("application#2fxml") — on vérifie plutôt la
        // présence du type /EmbeddedFile qui est plus sémantique.
        assertThat(pdfAscii).contains("/EmbeddedFile");
        // /AFRelationship = /Data doit être présent dans la file spec dict (requis Factur-X / EN 16931).
        assertThat(pdfAscii).contains("/AFRelationship");
        assertThat(pdfAscii).contains("/Data");
        // Le catalogue doit référencer l'array /AF (Associated Files — requis PDF/A-3).
        assertThat(pdfAscii).contains("/AF");
        // Le XMP doit contenir pdfaid:part=3 + pdfaid:conformance=B (best-effort PDF/A-3).
        assertThat(new String(pdfResult, StandardCharsets.UTF_8))
            .contains("pdfaid:part=\"3\"")
            .contains("pdfaid:conformance=\"B\"");
    }

    @Test
    @DisplayName("v8-2 — embedFacturXInPdf : arguments null/vides → IllegalArgumentException")
    void embedFacturXInPdf_rejectsInvalidArguments() {
        byte[] pdf = FacturXExporter.generateMinimalPdfForTest();
        byte[] xml = exporter.exportFacturXBasicWL(buildInvoice());

        // pdfBytes null ou vide
        assertThatThrownBy(() -> exporter.embedFacturXInPdf(null, xml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pdfBytes");
        assertThatThrownBy(() -> exporter.embedFacturXInPdf(new byte[0], xml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pdfBytes");

        // xmlBytes null ou vide
        assertThatThrownBy(() -> exporter.embedFacturXInPdf(pdf, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("xmlBytes");
        assertThatThrownBy(() -> exporter.embedFacturXInPdf(pdf, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("xmlBytes");
    }

    @Test
    @DisplayName("v8-2 — embedFacturXInPdf : PDF source corrompu → IllegalStateException (openpdf)")
    void embedFacturXInPdf_corruptedPdfThrowsIllegalStateException() {
        // Un byte[] {1,2,3} n'est pas un PDF valide — PdfReader doit lever IOException,
        // wrapper en IllegalStateException par embedFacturXInPdf (mentionne openpdf dans le message).
        byte[] corrupted = new byte[]{1, 2, 3};
        byte[] xml = exporter.exportFacturXBasicWL(buildInvoice());

        assertThatThrownBy(() -> exporter.embedFacturXInPdf(corrupted, xml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openpdf");
    }
}

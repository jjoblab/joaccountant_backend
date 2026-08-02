package jo.accountant.invoicing.einvoice;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfArray;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfFileSpecification;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Générateur de factures électroniques au format Factur-X / UBL 2.1
 * (audit v4.7 §4.1 FIX CRITIQUE pour conformité 2026).
 *
 * <p><b>Problème</b> : la v4.7 produisait uniquement des PDF non structurés via openhtmltopdf.
 * Depuis le 1er septembre 2026, la facturation électronique est obligatoire en B2B France
 * (Loi 2023-314). Sans format structuré, le SaaS est inutilisable pour les clients français B2B.
 *
 * <p><b>Solution</b> : génération de XML Factur-X (Cross Industry Invoice D16B, profil BASICWL)
 * embarqué dans le PDF/A-3 via une attachment. Format cible :
 * <ul>
 * <li><b>Factur-X BASICWL</b> : profil minimal — informations comptables uniquement (sans lignes détaillées).</li>
 * <li><b>Factur-X BASIC</b> : + lignes de facturation.</li>
 * <li><b>Factur-X COMFORT</b> : + TVA détaillée, dates de livraison, références commande.</li>
 * <li><b>Factur-X EXTENDED</b> : + infos logistiques, douane.</li>
 * </ul>
 *
 * <p>Cette implémentation génère le profil <b>BASICWL</b> (suffisant pour la conformité minimale).
 * Passage à BASIC/COMFORT en v4.8 : enrichir le XML avec les lignes de facturation.
 *
 * <p><b>Limitation v4.7.2</b> : génère le XML uniquement, NE l'embarque PAS encore dans le PDF/A-3
 * (nécessite openpdf + PDF/A-3 attachment — travail à finaliser en v4.8). Pour l'instant, le XML
 * est stocké à côté du PDF et l'API expose un endpoint /api/v1/companies/{id}/invoices/{id}/factur-x
 * pour récupérer le XML séparément. L'embarquement PDF/A-3 est l'étape suivante.
 *
 * @see <a href="https://www.impots.gouv.fr/facturation-electronique">Facturation électronique - impots.gouv.fr</a>
 * @see <a href="https://www.fnfe-mpe.org/factur-x/">Factur-X - FNFE-MPE</a>
 */
@Component
public class FacturXExporter {

 private static final Logger LOG = LoggerFactory.getLogger(FacturXExporter.class);
 private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

 // Namespaces CII D16B — conformes à la spec UN/CEFACT
 private static final String NS_RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
 private static final String NS_RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
 private static final String NS_UDT = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";

 /**
 * Génère le XML Factur-X BASICWL pour une facture donnée.
 *
 * @param invoice les données de la facture à sérialiser
 * @return bytes du XML UTF-8
 */
 public byte[] exportFacturXBasicWL(FacturXInvoice invoice) {
 try {
 ByteArrayOutputStream out = new ByteArrayOutputStream();
 XMLOutputFactory factory = XMLOutputFactory.newFactory();
 XMLStreamWriter writer = factory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());

 writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
 writer.writeStartElement("rsm", "CrossIndustryInvoice", NS_RSM);
 writer.writeNamespace("rsm", NS_RSM);
 writer.writeNamespace("ram", NS_RAM);
 writer.writeNamespace("udt", NS_UDT);

 writeExchangedDocumentContext(writer, invoice);
 writeExchangedDocument(writer, invoice);
 writeSupplyChainTradeTransaction(writer, invoice);

 writer.writeEndElement(); // CrossIndustryInvoice
 writer.writeEndDocument();
 writer.flush();
 writer.close();

 byte[] xml = out.toByteArray();
 LOG.info("Factur-X BASICWL généré pour facture {} : {} bytes",
 invoice.invoiceNumber(), xml.length);
 return xml;
 } catch (XMLStreamException ex) {
 throw new IllegalStateException("Échec de génération Factur-X pour " + invoice.invoiceNumber(), ex);
 }
 }

 /**
 * Embarque le XML Factur-X comme pièce jointe (EmbeddedFile) dans un PDF/A-3.
 *
 * <p><b>Audit v4.7 §4.1 PDF/A-3 Factur-X embarqué.</b>
 *
 * <p>Conformément à la spec Factur-X (FNFE-MPE / GS1), le XML Factur-X DOIT être embarqué
 * comme {@code /EmbeddedFile} dans le PDF, avec les métadonnées PDF/A-3 (AFRelationship = /Data,
 * /AF + /Desc + /MIMEType application/xml). Cela produit un {@code factur-x.pdf} unique
 * conforme à la norme, lisible par les humains (rendu visuel) ET par les machines (XML parsé).
 *
 * <p><b>v8-2 — implémentation finale avec openpdf 1.4.2 (LGPL).</b>
 * Dépendance ajoutée dans {@code invoicing/build.gradle.kts} :
 * <pre>{@code
 * implementation("com.github.librepdf:openpdf:1.4.2")
 * }</pre>
 *
 * <p>Étapes :
 * <ol>
 * <li>Charge le PDF source (généré par openhtmltopdf via {@code :document-generation}) avec
 * {@link PdfReader}.</li>
 * <li>Crée un {@link PdfStamper} en mode <b>append</b> (préserve la structure existante,
 * ajoute juste l'embedded file sans réécrire tout le PDF).</li>
 * <li>Crée une {@link PdfFileSpecification} encapsulant les bytes XML avec :
 * <ul>
 * <li>filename = {@code factur-x.xml} (nom standardisé spec FNFE-MPE).</li>
 * <li>MIME type = {@code application/xml}.</li>
 * <li>{@code /AFRelationship} = {@code /Data} (requis Factur-X / EN 16931 — indique
 * que l'embedded file est le payload structuré, pas une simple annexe).</li>
 * </ul>
 * </li>
 * <li>Attache le fichier via {@link PdfStamper#addFileAttachment} (ajout dans le name tree
 * {@code /EmbeddedFiles} du catalogue).</li>
 * <li>Référence l'embedded file depuis le catalogue via la clé {@code /AF} (array de refs
 * vers les fichiers associés) — requis par PDF/A-3 pour la découverte du payload.</li>
 * <li>Injecte un paquet XMP déclarant {@code pdfaid:part=3} + {@code pdfaid:conformance=B}
 * (best-effort — openpdf ne produit pas de PDF/A-3 strict certifiable, mais les
 * lecteurs compatibles PDF/A-3 reconnaissent l'intention et le lien /AF → /Data).</li>
 * <li>Ferme le stamper (flush des dicts dans le catalogue) et retourne les bytes du PDF
 * modifié.</li>
 * </ol>
 *
 * <p><b>Limitation v8-2</b> : openpdf 1.4.2 ne produit pas un PDF/A-3 strictement conforme
 * (pas de validation PDF/A-3 byte-level, pas de ColorProfile ICC obligatoire, pas de
 * compression object-stream contrainte). Pour une conformité PDF/A-3 auditée (signature
 * qualifiée, archivage légal long terme), il faudrait basculer sur iText 7 + pdfa-io
 * (commercial / AGPL). Le contrat principal Factur-X — un PDF unique contenant le XML CII
 * D16B BASICWL embarqué comme /Data — est ici honoré : l'endpoint
 * {@code /invoices/{id}/factur-x-pdf} retourne un PDF (content-type {@code application/pdf})
 * et non un 501.
 *
 * @param pdfBytes le PDF généré via document-generation (openhtmltopdf)
 * @param xmlBytes le XML Factur-X (CII D16B) à embarquer
 * @return le PDF/A-3 (best-effort) avec le XML embarqué
 * @throws IllegalArgumentException si pdfBytes ou xmlBytes sont null/vides
 * @throws IllegalStateException si openpdf échoue à lire/stamper le PDF (PDF source corrompu)
 */
 public byte[] embedFacturXInPdf(byte[] pdfBytes, byte[] xmlBytes) {
 if (pdfBytes == null || pdfBytes.length == 0) {
 throw new IllegalArgumentException(
 "pdfBytes ne peut pas être null ou vide — le PDF source est requis");
 }
 if (xmlBytes == null || xmlBytes.length == 0) {
 throw new IllegalArgumentException(
 "xmlBytes (XML Factur-X) ne peut pas être null ou vide");
 }
 try {
 // 1. Charger le PDF source (généré par openhtmltopdf)
 PdfReader reader = new PdfReader(pdfBytes);
 ByteArrayOutputStream out = new ByteArrayOutputStream();
 // 2. PdfStamper en mode append (5e arg=true) : préserve la structure existante,
 // ajoute juste l'embedded file. '\0' = conserve la version PDF source.
 PdfStamper stamper = new PdfStamper(reader, out, '\0', true);
 PdfWriter writer = stamper.getWriter();

 // 3. Spécification du fichier embarqué : bytes XML + filename + MIME type.
 // openpdf 1.4.2 : surcharge 7-args (writer, filePath=null, fileDisplay, fileStore,
 // compressed=false, mimeType="application/xml", fileParameter=null).
 // filePath=null indique qu'on fournit les bytes directement via fileStore.
 // fileDisplay="factur-x.xml" est le nom standardisé Factur-X (spec FNFE-MPE).
 PdfFileSpecification fs = PdfFileSpecification.fileEmbedded(
 writer, null, "factur-x.xml", xmlBytes, false,
 "application/xml", null);
 // /AFRelationship = /Data (requis par Factur-X / EN 16931 — payload structuré).
 // openpdf 1.4.2 expose PdfName.DATA mais pas PdfName.AFRELATIONSHIP — on l'instancie.
 fs.put(PDF_NAME_AFRELATIONSHIP, PdfName.DATA);

 // 4. Attacher le fichier au PDF (ajoute dans le name tree /EmbeddedFiles du catalogue).
 stamper.addFileAttachment("Factur-X XML (CII D16B BASICWL)", fs);

 // 5. Référencer l'embedded file depuis le catalogue (/AF array) — requis PDF/A-3
 // pour que le reader identifie l'embedded file comme "Associated File" payload.
 // Note v8-2 : en mode append, PdfStamperImp close() ne merge PAS writer.getExtraCatalog()
 // dans le catalogue final — il faut modifier directement reader.getCatalog() qui est
 // le dict persisté par le stamper (cf. bytecode PdfStamperImp.close @ offset 70-76).
 PdfDictionary catalog = reader.getCatalog();
 PdfArray afArray = new PdfArray();
 afArray.add(fs);
 catalog.put(PdfName.AF, afArray);

 // 6. Métadonnées PDF/A-3 (best-effort) — openpdf ne fait pas de PDF/A-3 strict,
 // mais on déclare pdfaid:part=3 + pdfaid:conformance=B dans le XMP pour signaler
 // l'intention PDF/A-3 aux lecteurs compatibles (Acrobat, veraPDF). On passe par
 // PdfStamper.setXmpMetadata (et non writer.setXmpMetadata qui n'est pas flushé
 // par PdfStamperImp.close() en mode append).
 try {
 stamper.setXmpMetadata(buildPdfA3Xmp());
 } catch (Exception xmpEx) {
 LOG.warn("XMP metadata generation failed (non-blocking, best-effort) : {}",
 xmpEx.getMessage());
 }

 // 7. Fermer le stamper (flush /AF + /EmbeddedFiles dans le catalogue) + reader.
 stamper.close();
 reader.close();
 byte[] result = out.toByteArray();
 LOG.info("Factur-X PDF/A-3 généré : pdf source={} octets → pdf+embedded={} octets " +
 "(xml Factur-X={} octets, filename=factur-x.xml, AFRelationship=/Data)",
 pdfBytes.length, result.length, xmlBytes.length);
 return result;
 } catch (IOException | DocumentException ex) {
 throw new IllegalStateException(
 "Échec de l'embarquement Factur-X dans le PDF (openpdf 1.4.2) : "
 + ex.getMessage(), ex);
 }
 }

 /**
 * Construit un paquet XMP minimal déclarant la conformance PDF/A-3 niveau B.
 *
 * <p>openpdf ne génère pas nativement le XMP pdfaid — on l'injecte manuellement via
 * {@link PdfWriter#setXmpMetadata(byte[])}. Le paquet inclut :
 * <ul>
 * <li>{@code pdfaid:part=3} + {@code pdfaid:conformance=B} (conformance PDF/A-3 niveau B).</li>
 * <li>{@code xmp:CreatorTool} + {@code xmp:CreateDate} (provenance).</li>
 * <li>{@code pdf:Producer} (chaîne de production).</li>
 * </ul>
 *
 * <p><b>Limitation</b> : sans ICC color profile embarqué et sans validation byte-level,
 * le PDF n'est pas un PDF/A-3 strict certifiable — c'est une déclaration d'intention.
 */
 private static byte[] buildPdfA3Xmp() {
 String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
 String xmp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
 + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
 + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
 + "<rdf:Description xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" "
 + "pdfaid:part=\"3\" pdfaid:conformance=\"B\"/>"
 + "<rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" "
 + "xmp:CreatorTool=\"JOAccountant v8 (openpdf 1.4.2)\" xmp:CreateDate=\"" + now + "\"/>"
 + "<rdf:Description xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" "
 + "pdf:Producer=\"JOAccountant v8 - Factur-X exporter\"/>"
 + "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
 + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Facture Factur-X</rdf:li>"
 + "</rdf:Alt></dc:title></rdf:Description>"
 + "</rdf:RDF></x:xmpmeta>";
 return xmp.getBytes(StandardCharsets.UTF_8);
 }

 // --- Constante PdfName non exposée par openpdf 1.4.2 (PDF/A-3 + Factur-X) ---

 /** Clé /AFRelationship sur la PdfFileSpecification — indique /Data pour Factur-X. */
 private static final PdfName PDF_NAME_AFRELATIONSHIP = new PdfName("AFRelationship");
 // Note : PdfName.AF et PdfName.DATA sont des constantes natives openpdf 1.4.2.

 /**
 * Helper de test : génère un PDF minimal (1 page blanche) avec openpdf, sans dépendre
 * du module :document-generation. Utilisé par {@link FacturXExporterTest} pour produire
 * un PDF source valide à passer à {@link #embedFacturXInPdf}.
 *
 * <p>Package-private — pas d'utilité hors des tests unitaires.
 *
 * @return bytes d'un PDF minimal valide (commence par {@code %PDF-}).
 * @throws IllegalStateException si la génération échoue (ne devrait pas arriver)
 */
 static byte[] generateMinimalPdfForTest() {
 try {
 ByteArrayOutputStream out = new ByteArrayOutputStream();
 Document document = new Document(PageSize.A4);
 PdfWriter writer = PdfWriter.getInstance(document, out);
 document.open();
 document.add(new Paragraph("Factur-X minimal PDF source"));
 document.close();
 writer.close();
 return out.toByteArray();
 } catch (DocumentException ex) {
 throw new IllegalStateException("Échec de génération du PDF minimal de test", ex);
 }
 }

 private void writeExchangedDocumentContext(XMLStreamWriter w, FacturXInvoice inv) throws XMLStreamException {
 w.writeStartElement(NS_RSM, "ExchangedDocumentContext");
 w.writeStartElement(NS_RAM, "BusinessProcessSpecifiedDocumentContextParameter");
 writeElement(w, NS_RAM, "ID", "A1");
 w.writeEndElement();
 w.writeStartElement(NS_RAM, "GuidelineSpecifiedDocumentContextParameter");
 writeElement(w, NS_RAM, "ID", "urn:cen.eu:en16931:2017#compliant#urn:fnfe-mpe.org:factur-x:1.0.07:basicwl");
 w.writeEndElement();
 w.writeEndElement();
 }

 private void writeExchangedDocument(XMLStreamWriter w, FacturXInvoice inv) throws XMLStreamException {
 w.writeStartElement(NS_RSM, "ExchangedDocument");
 writeElement(w, NS_RAM, "ID", inv.invoiceNumber());
 writeElement(w, NS_RAM, "TypeCode", "380"); // 380 = Commercial Invoice
 w.writeStartElement(NS_RAM, "IssueDateTime");
 // (lot-D-qualite-arch) — fix : writeAttribute doit être appelé AVANT writeCharacters
 // (sinon XMLStreamException "Attribute not associated with any element"). Bug révélé par
 // FacturXExporterTest — la génération n'avait jamais été testée unitairement avant ce lot.
 w.writeStartElement(NS_UDT, "DateTimeString");
 writeAttribute(w, "format", "102");
 w.writeCharacters(inv.issueDate().format(DATE_FMT));
 w.writeEndElement();
 w.writeEndElement();
 w.writeEndElement();
 }

 private void writeSupplyChainTradeTransaction(XMLStreamWriter w, FacturXInvoice inv) throws XMLStreamException {
 w.writeStartElement(NS_RSM, "SupplyChainTradeTransaction");

 w.writeStartElement(NS_RAM, "ApplicableHeaderTradeAgreement");
 writeTradeParty(w, "SellerTradeParty", inv.seller());
 writeTradeParty(w, "BuyerTradeParty", inv.buyer());
 writeElement(w, NS_RAM, "BuyerReference", inv.buyerReference() != null ? inv.buyerReference() : "N/A");
 w.writeEndElement();

 w.writeStartElement(NS_RAM, "ApplicableHeaderTradeDelivery");
 w.writeStartElement(NS_RAM, "ActualDeliverySupplyChainEvent");
 w.writeStartElement(NS_RAM, "OccurrenceDateTime");
 // fix : même correction que pour IssueDateTime (writeAttribute avant writeCharacters).
 w.writeStartElement(NS_UDT, "DateTimeString");
 writeAttribute(w, "format", "102");
 w.writeCharacters((inv.deliveryDate() != null ? inv.deliveryDate() : inv.issueDate()).format(DATE_FMT));
 w.writeEndElement();
 w.writeEndElement();
 w.writeEndElement();
 w.writeEndElement();

 w.writeStartElement(NS_RAM, "ApplicableHeaderTradeSettlement");
 writeElement(w, NS_RAM, "InvoiceCurrencyCode", inv.currency());
 for (TaxBreakdown tax : inv.taxBreakdowns()) {
 w.writeStartElement(NS_RAM, "ApplicableTradeTax");
 writeElement(w, NS_RAM, "CalculatedAmount", tax.taxAmount().toPlainString());
 writeElement(w, NS_RAM, "TypeCode", "VAT");
 writeElement(w, NS_RAM, "BasisAmount", tax.taxableBase().toPlainString());
 writeElement(w, NS_RAM, "CategoryCode", "S");
 writeElement(w, NS_RAM, "RateApplicablePercent", tax.rate().toPlainString());
 w.writeEndElement();
 }
 w.writeStartElement(NS_RAM, "SpecifiedTradeSettlementHeaderMonetarySummation");
 writeElement(w, NS_RAM, "LineTotalAmount", inv.subtotal().toPlainString());
 writeElement(w, NS_RAM, "TaxBasisTotalAmount", inv.subtotal().toPlainString());
 // fix : writeAttribute doit précéder writeCharacters pour les éléments à attribut
 // (currencyID). Bug révélé par FacturXExporterTest.
 w.writeStartElement(NS_RAM, "TaxTotalAmount");
 writeAttribute(w, "currencyID", inv.currency());
 w.writeCharacters(inv.taxAmount().toPlainString());
 w.writeEndElement();
 w.writeStartElement(NS_RAM, "GrandTotalAmount");
 writeAttribute(w, "currencyID", inv.currency());
 w.writeCharacters(inv.totalAmount().toPlainString());
 w.writeEndElement();
 w.writeStartElement(NS_RAM, "DuePayableAmount");
 writeAttribute(w, "currencyID", inv.currency());
 w.writeCharacters(inv.totalAmount().toPlainString());
 w.writeEndElement();
 w.writeEndElement();

 w.writeEndElement();
 w.writeEndElement();
 }

 private void writeTradeParty(XMLStreamWriter w, String elementName, TradeParty party) throws XMLStreamException {
 w.writeStartElement(NS_RAM, elementName);
 writeElement(w, NS_RAM, "ID", party.id());
 writeElement(w, NS_RAM, "Name", party.name());
 if (party.vatNumber() != null) {
 w.writeStartElement(NS_RAM, "SpecifiedLegalOrganization");
 writeElement(w, NS_RAM, "ID", party.vatNumber());
 w.writeEndElement();
 }
 // Lot B NIF haïtien sérialisé comme SpecifiedTaxRegistration avec schemeID="NIF_HT".
 // Le format Factur-X CII accepte plusieurs SpecifiedTaxRegistration ; on ajoute le NIF en
 // plus du VAT s'il existe. Quand l'entreprise est en Haïti (country='HT') ou que vatNumber
 // est null mais nif est présent (entreprise non assujettie TVA mais avec NIF obligatoire
 // — Code Fiscal art. 196), on sérialise le NIF pour conformité DGI.
 if (party.nif() != null && !party.nif().isBlank()) {
 w.writeStartElement(NS_RAM, "SpecifiedTaxRegistration");
 w.writeStartElement(NS_RAM, "ID");
 // schemeID="NIF_HT" pour indiquer qu'il s'agit d'un NIF Haïtien (vs VA pour TVA UE).
 String schemeId = resolveNifSchemeId(party);
 writeAttribute(w, "schemeID", schemeId);
 w.writeCharacters(party.nif());
 w.writeEndElement(); // ID
 w.writeEndElement(); // SpecifiedTaxRegistration
 }
 if (party.siret() != null) {
 writeElement(w, NS_RAM, "Description", "SIRET: " + party.siret());
 }
 w.writeEndElement();
 }

 /**
 * Résout le schemeID du NIF pour le SpecifiedTaxRegistration Factur-X (Lot B ).
 *
 * <p>Par défaut "NIF_HT" pour les entreprises haïtiennes. Si le tiers n'a pas de country
 * renseigné mais a un NIF (cas d'une entreprise hors France non assujettie TVA), on utilise
 * "NIF" comme schemeID générique (compatible CII).
 */
 private static String resolveNifSchemeId(TradeParty party) {
 String country = party.country();
 if (country != null && !country.isBlank()) {
 return "NIF_" + country.toUpperCase(java.util.Locale.ROOT);
 }
 return "NIF";
 }

 private void writeElement(XMLStreamWriter w, String ns, String localName, String text) throws XMLStreamException {
 w.writeStartElement(ns, localName);
 if (text != null) w.writeCharacters(text);
 w.writeEndElement();
 }

 private void writeAttribute(XMLStreamWriter w, String localName, String value) throws XMLStreamException {
 w.writeAttribute(localName, value);
 }

 // --- DTOs ---

 public record FacturXInvoice(
 String invoiceNumber,
 LocalDate issueDate,
 LocalDate deliveryDate,
 String currency,
 TradeParty seller,
 TradeParty buyer,
 String buyerReference,
 BigDecimal subtotal,
 BigDecimal taxAmount,
 BigDecimal totalAmount,
 List<TaxBreakdown> taxBreakdowns
 ) {}

 public record TradeParty(
 String id,
 String name,
 String vatNumber,
 String siret,
 String address,
 String country,
 /** Lot B NIF (Numéro d'Identification Fiscale) pour Haïti et pays non UE. */
 String nif
 ) {
 /**
 * Constructeur de compatibilité 6-args (avant ) — délègue avec {@code nif=null}.
 * @deprecated utiliser le constructeur 7-args avec nif explicite pour conformité DGI Haïti.
 */
 @Deprecated
 public TradeParty(String id, String name, String vatNumber, String siret,
 String address, String country) {
 this(id, name, vatNumber, siret, address, country, null);
 }
 }

 public record TaxBreakdown(
 BigDecimal rate,
 BigDecimal taxableBase,
 BigDecimal taxAmount
 ) {}
}

package jo.accountant.bankreconciliation.parser;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parseur de fichiers OFX 2.x (Open Financial Exchange, format XML) — .
 *
 * <p>OFX 2.x est le format moderne (XML bien-formé) pour l'échange de relevés bancaires entre
 * institutions financières et clients. Il succède à OFX 1.x (SGML, géré par
 * {@code BankReconciliationService.parseOfx}). La structure XML type :
 *
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <OFX>
 * <BANKMSGSRSV1>
 * <STMTTRNRS>
 * <STMTRS>
 * <BANKACCTFROM>
 * <ACCTID>1234567890</ACCTID>
 * </BANKACCTFROM>
 * <BANKTRANLIST>
 * <DTSTART>20260101</DTSTART>
 * <DTEND>20260131</DTEND>
 * <STMTTRN>
 * <TRNTYPE>CREDIT</TRNTYPE>
 * <DTPOSTED>20260115</DTPOSTED>
 * <TRNAMT>5000.00</TRNAMT>
 * <FITID>TXN001</FITID>
 * <NAME>Vente client X</NAME>
 * <MEMO>Facture FAC-2026-001</MEMO>
 * </STMTTRN>
 * <STMTTRN>...</STMTTRN>
 * </BANKTRANLIST>
 * <LEDGERBAL>
 * <BALAMT>12500.00</BALAMT>
 * <DTASOF>20260131</DTASOF>
 * </LEDGERBAL>
 * </STMTRS>
 * </STMTTRNRS>
 * </BANKMSGSRSV1>
 * </OFX>
 * }</pre>
 *
 * <p><b>Tags extraits</b> pour chaque {@code <STMTTRN>} :
 * <ul>
 * <li>{@code <TRNAMT>} — montant signé (positif = crédit / entrée, négatif = débit / sortie).
 * Aligné sur la convention {@link BankStatementLine#getAmount()}.</li>
 * <li>{@code <DTPOSTED>} — date de valeur. Format OFX : {@code YYYYMMDD} ou
 * {@code YYYYMMDDHHMMSS} (on prend les 8 premiers caractères).</li>
 * <li>{@code <NAME>} — nom du tiers / libellé court.</li>
 * <li>{@code <MEMO>} — mémo / libellé long. Concaténé au NAME dans la description.</li>
 * <li>{@code <FITID>} — identifiant unique de transaction (Financial Institution Transaction
 * ID). Concaténé à la description pour audit.</li>
 * </ul>
 *
 * <p><b>Parsing StAX</b> : on utilise {@link XMLStreamReader} (API pull — JDK standard, module
 * {@code java.xml}). StAX est plus performant que DOM pour les gros fichiers (pas de construction
 * d'arbre en mémoire) et plus facile à utiliser que SAX (modèle pull itératif). On scanne le
 * fichier en une seule passe, en accumulant les éléments de chaque {@code <STMTTRN>}.
 *
 * <p><b>Robustesse</b> : les transactions illisibles (montant non numérique, date malformée)
 * sont journalisées (WARN) et ignorées — le parsing continue. Si le contenu n'est pas un XML
 * bien-formé (ex: OFX 1.x SGML qui fuit dans le parseur 2.x), on lève une
 * {@link IllegalArgumentException} plutôt que de retourner une liste vide silencieuse (l'appelant
 * doit utiliser le parseur OFX 1.x pour ce contenu).
 *
 * <p>Bean Spring {@code @Component} — injectable dans {@code BankReconciliationService}. Le
 * parseur est stateless et thread-safe.
 
 *
 * @author jo@Dev


*/
@Component
public class Ofx2Parser {

 private static final Logger LOG = LoggerFactory.getLogger(Ofx2Parser.class);

 private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

 /**
 * Parse un contenu OFX 2.x (XML) en {@link List} de {@link BankStatementLine}.
 *
 * <p>Le parsing utilise StAX ({@link XMLStreamReader}) — modèle pull, performant pour les
 * gros relevés. Une seule passe sur le flux, accumulation des champs par transaction.
 *
 * <p>Les lignes retournées ne sont pas encore persistées — l'appelant (typiquement
 * {@code BankReconciliationService.importStatement}) les hydrate avec {@code importId},
 * {@code bankAccountId}, {@code companyId} avant persistance.
 *
 * @param ofxContent contenu brut OFX 2.x (XML bien-formé)
 * @return liste des transactions (une par {@code <STMTTRN>}), vide si aucune transaction
 * @throws IllegalArgumentException si le contenu n'est pas un XML bien-formé
 */
 public List<BankStatementLine> parse(String ofxContent) {
 if (ofxContent == null || ofxContent.isBlank()) {
 return List.of();
 }

 // OFX 2.x est du XML pur — on peut le passer directement à StAX.
 // (OFX 1.x SGML n'est pas géré ici — voir BankReconciliationService.parseOfx.)
 List<BankStatementLine> lines = new ArrayList<>();
 XMLInputFactory factory = XMLInputFactory.newFactory();
 // Sécurité : désactiver la résolution d'entités externes (XXE protection).
 factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
 factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

 try (StringReader reader = new StringReader(ofxContent)) {
 XMLStreamReader xsr = factory.createXMLStreamReader(reader);
 try {
 while (xsr.hasNext()) {
 int event = xsr.next();
 if (event == XMLStreamConstants.START_ELEMENT
 && "STMTTRN".equalsIgnoreCase(xsr.getLocalName())) {
 BankStatementLine line = parseStatementTransaction(xsr);
 if (line != null) {
 lines.add(line);
 }
 }
 }
 } finally {
 xsr.close();
 }
 } catch (XMLStreamException e) {
 throw new IllegalArgumentException("OFX 2.x illisible (XML mal formé). "
 + "Si le fichier est au format OFX 1.x (SGML), utiliser le parseur SGML. "
 + "Cause : " + e.getMessage(), e);
 }

 LOG.info("OFX 2.x parsé : {} transactions extraites", lines.size());
 return lines;
 }

 /**
 * Parse un élément {@code <STMTTRN>} complet.
 *
 * <p>Appelée alors que le {@link XMLStreamReader} est positionné sur l'événement
 * {@code START_ELEMENT} de {@code <STMTTRN>}. Consomme tous les événements jusqu'au
 * {@code END_ELEMENT} correspondant.
 */
 private BankStatementLine parseStatementTransaction(XMLStreamReader xsr) throws XMLStreamException {
 String trnType = null;
 String datePosted = null;
 String trnAmt = null;
 String fitid = null;
 String name = null;
 String memo = null;

 // On est sur <STMTTRN>. On itère jusqu'au </STMTTRN> correspondant.
 int depth = 1;
 while (depth > 0 && xsr.hasNext()) {
 int event = xsr.next();
 if (event == XMLStreamConstants.START_ELEMENT) {
 String localName = xsr.getLocalName();
 if ("STMTTRN".equalsIgnoreCase(localName)) {
 depth++;
 } else {
 String text = readElementText(xsr, localName);
 switch (localName.toUpperCase()) {
 case "TRNTYPE": trnType = text; break;
 case "DTPOSTED": datePosted = text; break;
 case "TRNAMT": trnAmt = text; break;
 case "FITID": fitid = text; break;
 case "NAME": name = text; break;
 case "MEMO": memo = text; break;
 default:
 // Autres tags (CHECKNUM, REFNUM, etc.) — ignorés.
 break;
 }
 }
 } else if (event == XMLStreamConstants.END_ELEMENT) {
 if ("STMTTRN".equalsIgnoreCase(xsr.getLocalName())) {
 depth--;
 }
 }
 }

 // Construire la BankStatementLine — ignorer la transaction si champs requis manquants.
 if (datePosted == null || datePosted.isBlank() || trnAmt == null || trnAmt.isBlank()) {
 LOG.warn("Transaction OFX ignorée (DTPOSTED ou TRNAMT manquant) : name={}", name);
 return null;
 }

 LocalDate date = parseOfxDate(datePosted);
 if (date == null) {
 LOG.warn("Transaction OFX ignorée (DTPOSTED illisible '{}') : name={}", datePosted, name);
 return null;
 }

 BigDecimal amount;
 try {
 amount = new BigDecimal(trnAmt.trim());
 } catch (NumberFormatException e) {
 LOG.warn("Transaction OFX ignorée (TRNAMT non numérique '{}') : name={}", trnAmt, name);
 return null;
 }

 // Construire la description : on combine NAME + MEMO + FITID pour maximiser les chances
 // de rapprochement (le libellé bancaire est souvent dans NAME, le détail dans MEMO).
 StringBuilder desc = new StringBuilder();
 if (name != null && !name.isBlank()) desc.append(name.trim());
 if (memo != null && !memo.isBlank()) {
 if (desc.length() > 0) desc.append(" — ");
 desc.append(memo.trim());
 }
 if (fitid != null && !fitid.isBlank()) {
 if (desc.length() > 0) desc.append(" ");
 desc.append("[").append(fitid.trim()).append("]");
 }
 if (desc.length() == 0 && trnType != null) desc.append(trnType);

 BankStatementLine line = new BankStatementLine();
 line.setLineDate(date);
 line.setAmount(amount);
 line.setDescription(desc.toString());
 line.setMatched(false);
 return line;
 }

 /**
 * Lit le contenu textuel d'un élément feuille (sans sous-éléments).
 *
 * <p>Appelée alors que le {@link XMLStreamReader} est positionné sur l'événement
 * {@code START_ELEMENT} de l'élément à lire. Consomme tous les événements jusqu'au
 * {@code END_ELEMENT} correspondant et retourne le texte concaténé.
 */
 private String readElementText(XMLStreamReader xsr, String elementName) throws XMLStreamException {
 StringBuilder sb = new StringBuilder();
 int depth = 1;
 while (depth > 0 && xsr.hasNext()) {
 int event = xsr.next();
 if (event == XMLStreamConstants.START_ELEMENT) {
 depth++;
 } else if (event == XMLStreamConstants.END_ELEMENT) {
 depth--;
 } else if (event == XMLStreamConstants.CHARACTERS) {
 sb.append(xsr.getText());
 }
 }
 return sb.toString().trim();
 }

 /**
 * Parse une date OFX au format {@code YYYYMMDD} ou {@code YYYYMMDDHHMMSS}.
 *
 * <p>OFX accepte aussi un suffixe {@code [XXX]} pour le fuseau horaire (ex:
 * {@code 20260115143000[-5:EST]}) — on l'ignore en prenant les 8 premiers caractères.
 */
 private LocalDate parseOfxDate(String raw) {
 if (raw == null || raw.length() < 8) return null;
 try {
 return LocalDate.parse(raw.substring(0, 8), OFX_DATE);
 } catch (DateTimeParseException e) {
 return null;
 }
 }
}

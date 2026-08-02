package jo.accountant.bankreconciliation.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jo.accountant.bankreconciliation.dto.Mt940ParseResult;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parseur de fichiers MT940 SWIFT (relevés bancaires européens) — .
 *
 * <p>Le format MT940 (SWIFT User-to-Bank Message) est le standard international pour l'échange
 * de relevés bancaires électroniques entre banques et clients. Il est structuré en tags
 * délimités par {@code :NN:} où NN est un code numérique (éventuellement suivi d'une lettre) :
 *
 * <ul>
 * <li>{@code :20:} — Transaction reference (référence du fichier, niveau banque)</li>
 * <li>{@code :25:} — Account identification (IBAN ou numéro local)</li>
 * <li>{@code :60F:} — Opening Balance (solde d'ouverture). Format : {@code D/C YYMMDD CUR AMOUNT}</li>
 * <li>{@code :61:} — Statement Line (une ligne par transaction). Format :
 * {@code YYMMDD [MMDD] D/C[R] AMOUNT[,F] N TRTYPE // REFERENCE}</li>
 * <li>{@code :62F:} — Closing Balance (solde de clôture). Même format que :60F:</li>
 * <li>{@code :86:} — Transaction details (informations supplémentaires — libellé, tiers, etc.)</li>
 * </ul>
 *
 * <p><b>Extraction par tag :61:</b> (voir spec SWIFT MT940):
 * <ul>
 * <li><b>date</b> : 6 premiers caractères (YYMMDD) — date de valeur</li>
 * <li><b>signe</b> : caractère D (débit, sortie) ou C (crédit, entrée) — converti en montant
 * signé : positif pour un crédit, négatif pour un débit (convention BankStatementLine)</li>
 * <li><b>montant</b> : décimal avec virgule comme séparateur (ex: {@code 1000,00})</li>
 * <li><b>code transaction</b> : 3 caractères après le montant (ex: NTR = transfer, NCHK = cheque)</li>
 * <li><b>reference</b> : chaîne après le code transaction, potentiellement précédée de {@code //}</li>
 * </ul>
 *
 * <p><b>Convention de signe</b> : un crédit (C) devient montant positif (entrée banque), un débit
 * (D) devient montant négatif (sortie banque). Aligné sur la convention existante de
 * {@link BankStatementLine#getAmount()} (positif = crédit, négatif = débit) — voir
 * {@code BankReconciliationService.autoMatch} qui exploite cette convention.
 *
 * <p><b>Gestion des lignes multi-lignes</b> : MT940 peut couper un tag sur plusieurs lignes
 * physiques avec {@code -} en fin de ligne. Le parseur supprime d'abord ces {@code -\n} pour
 * reconnecter les lignes avant l'extraction des tags.
 *
 * <p><b>Robustesse</b> : les lignes :61: illisibles sont journalisées (WARN) et ignorées — le
 * parseur ne lève pas d'exception pour un tag malformé, il continue avec le reste du relevé
 * (principe de « best-effort » pour les relevés bancaires réels souvent légèrement non-conformes).
 *
 * <p>Bean Spring {@code @Component} — injectable dans {@code BankReconciliationService} (à venir
 * en intégration). Le parseur est stateless et thread-safe.
 */
@Component
public class Mt940Parser {

 private static final Logger LOG = LoggerFactory.getLogger(Mt940Parser.class);

 /**
 * Capture un tag MT940 et sa valeur : {@code :NN[A]:} suivi du contenu jusqu'au prochain
 * tag ou fin de chaîne. Le code de tag est en groupe 1, la valeur en groupe 2.
 */
 private static final Pattern TAG_PATTERN = Pattern.compile(
 ":([0-9]{2}[A-Z]?):(.*?)(?=:|[0-9]{2}[A-Z]?:|\\z)",
 Pattern.DOTALL
 );

 /**
 * Décompose une ligne de statement :61:.
 * Groupes :
 * 1 = YYMMDD (value date, 6 chiffres)
 * 2 = MMDD optionnel (entry date, 4 chiffres)
 * 3 = D ou C (signe), éventuellement suivi de R (reversal) ou autres flags
 * 4 = montant (digits + virgule décimale)
 * 5 = code fonds (1 char, optionnel) + code transaction (3 chars)
 * 6 = référence (le reste)
 */
 private static final Pattern LINE_61_PATTERN = Pattern.compile(
 "^(\\d{6})" // 1 : YYMMDD value date
 + "(?:\\d{4})?" // MMDD entry date (optionnel, non capturé)
 + "(?:([A-Z]{1,2}))" // 2 : D ou C (éventuellement suivi de R, EC, etc.)
 + "([0-9]+(?:,[0-9]+)?)" // 3 : montant avec virgule décimale
 + "(?:[A-Z0-9])?" // funds code (1 char, optionnel)
 + "([A-Z0-9]{3})" // 4 : code transaction (3 chars) — ex: TRF, CHK, DD
 + "(.*)$" // 5 : référence
 );

 /**
 * Décompose un tag de balance :60F: ou :62F:.
 * Groupes :
 * 1 = D/C (signe)
 * 2 = YYMMDD (date)
 * 3 = code devise (3 lettres)
 * 4 = montant (avec virgule décimale)
 */
 private static final Pattern BALANCE_PATTERN = Pattern.compile(
 "^([DC])" // 1 : signe
 + "(\\d{6})" // 2 : date YYMMDD
 + "([A-Z]{3})" // 3 : code devise
 + "([0-9]+(?:,[0-9]+)?)" // 4 : montant
 );

 private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

 /**
 * Parse un contenu MT940 en {@link Mt940ParseResult}.
 *
 * <p>Le résultat contient :
 * <ul>
 * <li>le numéro de compte (tag :25:) ;</li>
 * <li>le solde d'ouverture (tag :60F:) et le solde de clôture (tag :62F:), avec leur
 * signe (positif = crédit, négatif = débit) ;</li>
 * <li>la liste des lignes de transaction (une par tag :61:), enrichies du tag :86:
 * suivant dans la description si présent.</li>
 * </ul>
 *
 * <p>Les lignes :61: illisibles sont ignorées (WARN log) — le parsing continue.
 *
 * @param mt940Content contenu brut du fichier MT940 (UTF-8)
 * @return résultat du parsing, jamais null (champs null si tags absents, liste vide si
 * aucune ligne :61: valide)
 */
 public Mt940ParseResult parse(String mt940Content) {
 if (mt940Content == null || mt940Content.isBlank()) {
 return new Mt940ParseResult(null, null, null, List.of());
 }

 // Reconnecter les lignes de continuation MT940 (terminées par "-\n" ou "-\r\n").
 // Le tiret en fin de ligne est un caractère de continuation, pas un signe moins.
 String normalized = mt940Content
 .replace("-\r\n", "")
 .replace("-\n", "");

 String account = null;
 BigDecimal openingBalance = null;
 BigDecimal closingBalance = null;
 List<BankStatementLine> lines = new ArrayList<>();
 // Mémorise la dernière ligne :61: parsée pour attacher le tag :86: suivant.
 BankStatementLine lastLine = null;

 Matcher m = TAG_PATTERN.matcher(normalized);
 while (m.find()) {
 String tag = m.group(1);
 String value = m.group(2) != null ? m.group(2).trim() : "";

 switch (tag) {
 case "25":
 // Account — parfois préfixé par la devise (ex: "EUR/12345"). On garde tout.
 account = value.replaceAll("\\s+", "");
 break;
 case "60F":
 case "60M":
 openingBalance = parseBalance(value);
 break;
 case "62F":
 case "62M":
 closingBalance = parseBalance(value);
 break;
 case "61":
 BankStatementLine parsed = parseStatementLine(value);
 if (parsed != null) {
 lines.add(parsed);
 lastLine = parsed;
 } else {
 // Ligne illisible — on détache lastLine pour ne pas y attacher un :86: orphelin.
 lastLine = null;
 }
 break;
 case "86":
 if (lastLine != null) {
 // Le tag :86: contient les détails de la transaction précédente.
 // On l'ajoute à la description (en plus du code/reference déjà présent).
 String existingDesc = lastLine.getDescription() != null
 ? lastLine.getDescription() : "";
 String enriched = existingDesc.isEmpty()
 ? value : existingDesc + " | " + value;
 lastLine.setDescription(enriched);
 }
 break;
 default:
 // Tags non gérés (:20:, :28C:, :13D:, etc.) — ignorés silencieusement.
 break;
 }
 }

 LOG.info("MT940 parsé : account={} lines={} opening={} closing={}",
 account, lines.size(), openingBalance, closingBalance);
 return new Mt940ParseResult(account, openingBalance, closingBalance, lines);
 }

 /**
 * Parse un tag de balance (:60F: ou :62F:) en {@link BigDecimal} signé.
 *
 * <p>Format SWIFT : {@code D/C YYMMDD CUR AMOUNT} où D = débit (solde négatif), C = crédit
 * (solde positif), AMOUNT utilise la virgule comme séparateur décimal.
 */
 BigDecimal parseBalance(String value) {
 if (value == null || value.isBlank()) return null;
 Matcher m = BALANCE_PATTERN.matcher(value.trim());
 if (!m.find()) {
 LOG.warn("Tag balance illisible : {}", value);
 return null;
 }
 String sign = m.group(1);
 BigDecimal amount = parseAmount(m.group(4));
 if (amount == null) return null;
 return "D".equals(sign) ? amount.negate() : amount;
 }

 /**
 * Parse une ligne de statement :61: en {@link BankStatementLine}.
 *
 * <p>Extraction : date (YYMMDD), signe (D/C), montant, code transaction (3 chars),
 * référence. La description est construite comme {@code "CODE — REFERENCE"}.
 */
 BankStatementLine parseStatementLine(String value) {
 if (value == null || value.isBlank()) return null;
 Matcher m = LINE_61_PATTERN.matcher(value.trim());
 if (!m.find()) {
 LOG.warn("Ligne :61: illisible (ignorée) : {}", value);
 return null;
 }

 LocalDate date;
 try {
 date = LocalDate.parse(m.group(1), YYMMDD);
 } catch (DateTimeParseException e) {
 LOG.warn("Ligne :61: date illisible (ignorée) : {}", value);
 return null;
 }

 String sign = m.group(2);
 BigDecimal amount = parseAmount(m.group(3));
 if (amount == null) {
 LOG.warn("Ligne :61: montant illisible (ignorée) : {}", value);
 return null;
 }
 // Convention BankStatementLine : positif = crédit (entrée), négatif = débit (sortie).
 // Une ligne :61: peut commencer par "D" (débit), "C" (crédit), "DR" (débit reversal),
 // "CR" (crédit reversal), "EC" (equivalent credit), etc. — on ne regarde que le 1er char.
 char signChar = sign.isEmpty() ? 'C' : sign.charAt(0);
 if (signChar == 'D') {
 amount = amount.negate();
 }

 String txCode = m.group(4);
 String reference = m.group(5) != null ? m.group(5).trim() : "";
 // Nettoyer la référence : retirer le préfixe "//" éventuel et les slashs superflus.
 if (reference.startsWith("//")) reference = reference.substring(2);
 String description = txCode + " — " + reference;

 BankStatementLine line = new BankStatementLine();
 line.setLineDate(date);
 line.setAmount(amount);
 line.setDescription(description);
 line.setMatched(false);
 return line;
 }

 /**
 * Parse un montant MT940 (virgule comme séparateur décimal, pas de séparateur de milliers).
 */
 private BigDecimal parseAmount(String raw) {
 if (raw == null || raw.isBlank()) return null;
 try {
 return new BigDecimal(raw.replace(',', '.').trim());
 } catch (NumberFormatException e) {
 return null;
 }
 }
}

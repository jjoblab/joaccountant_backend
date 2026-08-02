package jo.accountant.tax.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.tax.dto.CorporateTaxProjection;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.dto.TaxDeclaration.TaxLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service d'export des déclarations fiscales vers les formats administratifs français
 * (audit mobile #8 — Export CA3/DES/EFI).
 *
 * <p>Formats supportés :
 * <ul>
 * <li><b>CA3</b> (formulaire 3517-S-SD) — Déclaration de TVA mensuelle/trimestrielle française.
 * Agrégation par taux de TVA des opérations taxables (ventes + achats) sur la période,
 * calcul de la TVA due (TVA collectée − TVA déductible).</li>
 * <li><b>DES</b> (Déclaration d'Échanges de Services intra-UE B2B, art. 289 B CGI) — Non implémenté : ,
 * nécessite l'agrégation par pays UE du tiers.</li>
 * <li><b>EFI</b> (Échange de Formulaires Informatisé) — Non implémenté : , génère le XML EDI pour
 * télédéclaration directe via le PPF.</li>
 * </ul>
 *
 * <p><b>Format de sortie CA3</b> : CSV (séparateur point-virgule, encodage UTF-8 BOM pour Excel
 * français). Colonnes :
 * <ol>
 * <li>{@code base_ht} — base HT (taxableBase collectée + déductible, agrégée par taux)</li>
 * <li>{@code taux_tva} — taux de TVA (ex. 20.00, 10.00, 5.50, 2.10)</li>
 * <li>{@code tva_collectee} — TVA collectée sur ventes (débit)</li>
 * <li>{@code tva_deductible} — TVA déductible sur achats</li>
 * <li>{@code tva_due} — TVA due = TVA collectée − TVA déductible (plancher 0)</li>
 * </ol>
 *
 * <p>Une ligne d'en-tête + une ligne par taux de TVA + une ligne TOTAL. Le format est compatible
 * avec l'import EFI (copier-coller dans le formulaire CA3 de la DGFiP).
 *
 * <p><b>Limitation v1</b> : export CSV brut, sans génération du XML EDI ni télédéclaration
 * directe. Le client (mobile/web) télécharge le CSV et le saisit manuellement dans le formulaire
 * CA3 sur impots.gouv.fr. L'automatisation EFI/EDI est planifiée en v4.9 (Troubleshooting #8 —
 * 8 jh).
 */
@Service
public class TaxExportService {

 private static final Logger LOG = LoggerFactory.getLogger(TaxExportService.class);
 private static final String LINE_SEP = "\r\n"; // CRLF pour compat Windows / Excel

 private final TaxService taxService;

 // v8-9 — Injectés via setter (ajoutés après le constructeur existant pour ne pas casser
 // les tests unitaires pré-v8-9). Utilisés par exportDgiDcr pour calculer les soldes réels
 // par classe PCN (agrégats JournalLineRepository + lookup AccountRepository).
 private JournalLineRepository journalLineRepository;
 private AccountRepository accountRepository;

 public TaxExportService(TaxService taxService) {
 this.taxService = taxService;
 }

 /**
 * v8-9 — Injection optionnelle des repositories d'agrégation comptable pour
 * {@link #exportDgiDcr(UUID, int)}. Si ces repositories ne sont pas injectés
 * (tests unitaires pré-v8-9), exportDgiDcr dégrade gracieusement vers le squelette
 * à 0 (comportement historique) avec un warning loggué.
 */
 @org.springframework.beans.factory.annotation.Autowired
 public void setAccountingRepositories(
 JournalLineRepository journalLineRepository,
 AccountRepository accountRepository) {
 this.journalLineRepository = journalLineRepository;
 this.accountRepository = accountRepository;
 }

 /**
 * Exporte la déclaration TVA au format CA3 (CSV français).
 *
 * <p>Le CSV contient une ligne d'en-tête, une ligne par taux de TVA, et une ligne TOTAL.
 * Encodage UTF-8 avec BOM (pour Excel français qui sinon interprète mal l'UTF-8).
 *
 * @param companyId l'entreprise
 * @param from date de début de période (inclusive)
 * @param to date de fin de période (inclusive)
 * @return bytes du CSV UTF-8 (avec BOM Excel)
 */
 @Transactional(readOnly = true)
 public byte[] exportCa3(UUID companyId, LocalDate from, LocalDate to) {
 // Réutilise TaxService.getDeclaration qui agrège déjà par taux de TVA (collecté + déductible).
 TaxDeclaration declaration = taxService.getDeclaration(companyId, from, to);

 // Fusionner par taux : pour chaque taux, additionner base HT, TVA collectée, TVA déductible.
 Map<BigDecimal, Ca3Row> rowsByRate = new TreeMap<>();
 for (TaxLine line : declaration.collectedLines()) {
 Ca3Row row = rowsByRate.computeIfAbsent(line.rate(), k -> new Ca3Row(k));
 row.baseHt = row.baseHt.add(line.taxableBase());
 row.tvaCollectee = row.tvaCollectee.add(line.taxAmount());
 }
 for (TaxLine line : declaration.deductibleLines()) {
 Ca3Row row = rowsByRate.computeIfAbsent(line.rate(), k -> new Ca3Row(k));
 row.baseHt = row.baseHt.add(line.taxableBase());
 row.tvaDeductible = row.tvaDeductible.add(line.taxAmount());
 }
 // Calculer la TVA due par taux
 List<Ca3Row> rows = new ArrayList<>(rowsByRate.values());
 for (Ca3Row row : rows) {
 BigDecimal due = row.tvaCollectee.subtract(row.tvaDeductible);
 row.tvaDue = due.compareTo(BigDecimal.ZERO) >= 0 ? due : BigDecimal.ZERO;
 }

 // Génération du CSV
 ByteArrayOutputStream baos = new ByteArrayOutputStream();
 // BOM UTF-8 pour Excel français
 try {
 baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
 } catch (java.io.IOException e) {
 throw new IllegalStateException("Écriture BOM échouée", e);
 }
 // CRLF pour compat Windows / Excel
 try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
 pw.print("base_ht;taux_tva;tva_collectee;tva_deductible;tva_due" + LINE_SEP);
 BigDecimal totalBase = BigDecimal.ZERO;
 BigDecimal totalCollectee = BigDecimal.ZERO;
 BigDecimal totalDeductible = BigDecimal.ZERO;
 BigDecimal totalDue = BigDecimal.ZERO;
 for (Ca3Row row : rows) {
 pw.print(formatCsv(row.baseHt) + ";"
 + formatRate(row.rate) + ";"
 + formatCsv(row.tvaCollectee) + ";"
 + formatCsv(row.tvaDeductible) + ";"
 + formatCsv(row.tvaDue) + LINE_SEP);
 totalBase = totalBase.add(row.baseHt);
 totalCollectee = totalCollectee.add(row.tvaCollectee);
 totalDeductible = totalDeductible.add(row.tvaDeductible);
 totalDue = totalDue.add(row.tvaDue);
 }
 // Ligne TOTAL
 pw.print(formatCsv(totalBase) + ";"
 + ";" // taux TVA vide pour la ligne total
 + formatCsv(totalCollectee) + ";"
 + formatCsv(totalDeductible) + ";"
 + formatCsv(totalDue) + LINE_SEP);
 }

 byte[] csv = baos.toByteArray();
 LOG.info("Export CA3 {} [{} à {}] : {} octets, {} taux de TVA",
 companyId, from, to, csv.length, rows.size());
 return csv;
 }

 /** Formate un montant en notation française (virgule décimale) — requis par Excel FR. */
 private String formatCsv(BigDecimal amount) {
 if (amount == null) return "0,00";
 return amount.setScale(2, java.math.RoundingMode.HALF_UP)
 .toString().replace('.', ',');
 }

 /** Formate un taux de TVA (ex. 20 → "20.00", 5.5 → "5.50"). */
 private String formatRate(BigDecimal rate) {
 if (rate == null) return "0.00";
 return rate.setScale(2, java.math.RoundingMode.HALF_UP).toString();
 }

 /** Ligne CSV CA3 par taux de TVA. */
 private static final class Ca3Row {
 final BigDecimal rate;
 BigDecimal baseHt = BigDecimal.ZERO;
 BigDecimal tvaCollectee = BigDecimal.ZERO;
 BigDecimal tvaDeductible = BigDecimal.ZERO;
 BigDecimal tvaDue = BigDecimal.ZERO;

 Ca3Row(BigDecimal rate) { this.rate = rate; }
 }

 // ════════════════════════════════════════════════════════════════════════
 // R-F-validation (lot-G) — Exports DGI Haïti (TVA / TCA / RS / DCR / DCLS)
 // Conforme aux exigences formulées par :
 // - Maître Jean-Robert Pierre-Louis (expert-comptable DGI Haïti) — P0
 // - Mme Marie-Carmel Joseph (PME1 Boutik Lakay) — fastidieux sans export DGI
 // - M. Frantz Moïse (PME2 Moïse & Associés) — 4 formulaires DGI mensuels
 // - Mme Nadège Saintilus (PME3 Espwa pou Ayiti) — BLOQUANT pour DGI mensuelle
 //
 // Implémentation : squelette CSV structuré (format DGI Haïti).
 // L'automatisation via télédéclaration API DGI (si elle existe à terme) est
 // planifiée en v6 — pour l'instant, l'utilisateur télécharge le CSV et le
 // saisit manuellement sur https://dgi.gouv.ht.
 // ════════════════════════════════════════════════════════════════════════

 /**
 * Export DGI TVA mensuelle Haïti (Code Fiscal art. 191 — TVA 10% sur débits).
 *
 * <p>Format CSV structuré avec en-têtes DGI normalisés :
 * <pre>
 * DGI Haïti — Déclaration TVA mensuelle (art. 191 Code Fiscal)
 * Période;{YYYY-MM}
 * NIF déclarant;{NIF}
 * Raison sociale;{name}
 * Date échéance;{15 du mois M+1}
 * Opérations taxables;base_ht;taux_tva;tva_collectee
 * Ventes locales;{base};{rate};{amount}
 * ...
 * Achats;tva_deductible
 * TVA déductible;{amount}
 * TVA due;{tva_collectee - tva_deductible, plancher 0}
 * Crédit reporté M-1;{credit_carried_forward}
 * Crédit à reporter M+1;{credit_to_carry_forward}
 * </pre>
 *
 * @param companyId ID de l'entreprise
 * @param year année de la période
 * @param month mois de la période (1-12)
 * @return bytes du CSV UTF-8 BOM (Excel-compatible)
 */
 @Transactional(readOnly = true)
 public byte[] exportDgiTva(UUID companyId, int year, int month) {
 LocalDate from = LocalDate.of(year, month, 1);
 LocalDate to = from.plusMonths(1).minusDays(1);
 // v6-1-multi-tax-invoice-line — filtrer par TaxType=VAT pour ne pas fusionner TVA + TCA
 TaxDeclaration declaration = taxService.getDeclaration(companyId, from, to, "VAT");

 ByteArrayOutputStream baos = new ByteArrayOutputStream();
 // BOM UTF-8 pour Excel
 baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);
 PrintWriter w = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

 w.print("DGI Haïti — Déclaration TVA mensuelle (art. 191 Code Fiscal)" + LINE_SEP);
 w.print("Période;" + String.format("%04d-%02d", year, month) + LINE_SEP);
 w.print("Date échéance;15 du mois M+1" + LINE_SEP);
 w.print(LINE_SEP);
 w.print("Opérations taxables;base_ht;taux_tva;tva_collectee" + LINE_SEP);

 List<TaxLine> collected = declaration.collectedLines() != null
 ? declaration.collectedLines() : List.<TaxLine>of();
 for (TaxLine line : collected) {
 w.print((line.taxLabel() != null ? line.taxLabel() : "TVA")
 + " " + formatRate(line.rate()) + "%;"
 + formatCsv(line.taxableBase()) + ";"
 + formatRate(line.rate()) + ";"
 + formatCsv(line.taxAmount()) + LINE_SEP);
 }

 w.print(LINE_SEP);
 w.print("TVA déductible;" + formatCsv(declaration.totalTaxDeductible()) + LINE_SEP);
 BigDecimal tvaDue = declaration.taxDue() != null
 ? declaration.taxDue() : BigDecimal.ZERO;
 w.print("TVA due;" + formatCsv(tvaDue) + LINE_SEP);
 w.print("Crédit reporté M-1;" + formatCsv(declaration.taxCreditCarriedForward() == null
 ? BigDecimal.ZERO : declaration.taxCreditCarriedForward()) + LINE_SEP);
 w.print("Crédit à reporter M+1;" + formatCsv(declaration.taxCreditToCarryForward() == null
 ? BigDecimal.ZERO : declaration.taxCreditToCarryForward()) + LINE_SEP);

 w.flush();
 LOG.info("Export DGI TVA Haïti généré : companyId={} période={}{}",
 companyId, year, String.format("-%02d", month));
 return baos.toByteArray();
 }

 /**
 * Export DGI TCA mensuelle Haïti (Code Fiscal art. 196 — TCA 2%/5%/10%).
 *
 * <p>Identique au format TVA mais filtre sur {@code TaxType.TCA} au lieu de {@code VAT}.
 * <p><b>v6-1-multi-tax-invoice-line</b> : la TCA est désormais calculée à partir des
 * {@code InvoiceLineTax} (type=TCA) — la multi-taxe par ligne est supportée depuis V78.
 * La fusion avec la TVA dans la même déclaration (gap P0 signalé par les validateurs
 * lot-G) est corrigée : {@code getDeclaration(companyId, from, to, "TCA")} filtre strictement
 * par {@code tax_type='TCA'}.
 *
 * @param companyId ID de l'entreprise
 * @param year année
 * @param month mois (1-12)
 * @return bytes CSV UTF-8 BOM
 */
 @Transactional(readOnly = true)
 public byte[] exportDgiTca(UUID companyId, int year, int month) {
 LocalDate from = LocalDate.of(year, month, 1);
 LocalDate to = from.plusMonths(1).minusDays(1);
 // v6-1-multi-tax-invoice-line — filtrer par TaxType=TCA pour ne plus fusionner avec la TVA.
 // Retire le TODO "v6 : filtrer par TaxType.TCA" — la multi-taxe par ligne est maintenant
 // supportée (V78 + InvoiceLineTax + TaxService.getDeclaration(taxType)).
 TaxDeclaration declaration = taxService.getDeclaration(companyId, from, to, "TCA");

 ByteArrayOutputStream baos = new ByteArrayOutputStream();
 baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);
 PrintWriter w = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

 w.print("DGI Haïti — Déclaration TCA mensuelle (art. 196 Code Fiscal)" + LINE_SEP);
 w.print("Période;" + String.format("%04d-%02d", year, month) + LINE_SEP);
 w.print("Date échéance;15 du mois M+1" + LINE_SEP);
 w.print(LINE_SEP);
 w.print("Opérations TCA;base_ht;taux_tca;tca_collectee" + LINE_SEP);

 // v6-1 — la déclaration est déjà filtrée par taxType=TCA via getDeclaration(companyId, from, to, "TCA")
 BigDecimal total = BigDecimal.ZERO;
 List<TaxLine> collected = declaration.collectedLines() != null
 ? declaration.collectedLines() : List.<TaxLine>of();
 for (TaxLine line : collected) {
 w.print((line.taxLabel() != null ? line.taxLabel() : "TCA")
 + " " + formatRate(line.rate()) + "%;"
 + formatCsv(line.taxableBase()) + ";"
 + formatRate(line.rate()) + ";"
 + formatCsv(line.taxAmount()) + LINE_SEP);
 total = total.add(line.taxAmount() == null ? BigDecimal.ZERO : line.taxAmount());
 }

 w.print(LINE_SEP);
 w.print("TCA due;" + formatCsv(total) + LINE_SEP);
 w.flush();
 LOG.info("Export DGI TCA Haïti généré : companyId={} période={}{}", companyId, year,
 String.format("-%02d", month));
 return baos.toByteArray();
 }

 /**
 * Export DGI RS mensuelle Haïti (Code Fiscal art. 156 — RS 2%/10%/30%).
 *
 * <p>R-F-validation v6-2 — implémentation réelle : agrège les retenues à la source
 * appliquées sur les factures de ventes de la période (SalesInvoice.withholdingAmount > 0),
 * via {@link TaxService#getWithholdingDeclaration(UUID, LocalDate, LocalDate)}. Une ligne
 * par taux de RS (2% prestations locales, 10% royalties, 30% non-résidents, 10% loyers).
 *
 * <p>Les avoirs (CREDIT_NOTE) sont traités en négatif (ils inversent la RS de la facture
 * originale) — ils peuvent générer des lignes négatives ou un crédit à reporter sur M+1.
 *
 * <p>Format CSV DGI Haïti (UTF-8 BOM, séparateur point-virgule) :
 * <pre>
 * DGI Haïti — Déclaration RS mensuelle (art. 156 Code Fiscal)
 * Période;{YYYY-MM}
 * Date échéance;15 du mois M+1
 *
 * Retenues à la source;base_ht;taux_rs;rs_retenu;tiers_type
 * RS 2.00% — ventes (art. 156-1 Code Fiscal);{base};{rate};{amount};CLIENT
 * ...
 *
 * Total RS due;{total}
 * Crédit à reporter M+1;{creditToCarryForward}
 * </pre>
 *
 * @param companyId ID de l'entreprise
 * @param year année
 * @param month mois (1-12)
 * @return bytes CSV UTF-8 BOM
 */
 @Transactional(readOnly = true)
 public byte[] exportDgiRs(UUID companyId, int year, int month) {
 LocalDate from = LocalDate.of(year, month, 1);
 LocalDate to = from.plusMonths(1).minusDays(1);
 TaxDeclaration declaration = taxService.getWithholdingDeclaration(companyId, from, to);

 ByteArrayOutputStream baos = new ByteArrayOutputStream();
 baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);
 PrintWriter w = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

 w.print("DGI Haïti — Déclaration RS mensuelle (art. 156 Code Fiscal)" + LINE_SEP);
 w.print("Période;" + String.format("%04d-%02d", year, month) + LINE_SEP);
 w.print("Date échéance;15 du mois M+1" + LINE_SEP);
 w.print(LINE_SEP);
 w.print("Retenues à la source;base_ht;taux_rs;rs_retenu;tiers_type" + LINE_SEP);

 // Une ligne par taux de RS — l'agrégation est déjà faite par TaxService.getWithholdingDeclaration
 List<TaxLine> collected = declaration.collectedLines() != null
 ? declaration.collectedLines() : List.<TaxLine>of();
 BigDecimal total = BigDecimal.ZERO;
 for (TaxLine line : collected) {
 w.print((line.taxLabel() != null ? line.taxLabel() : "RS")
 + ";"
 + formatCsv(line.taxableBase()) + ";"
 + formatRate(line.rate()) + ";"
 + formatCsv(line.taxAmount()) + ";"
 + "CLIENT" + LINE_SEP);
 total = total.add(line.taxAmount() == null ? BigDecimal.ZERO : line.taxAmount());
 }

 w.print(LINE_SEP);
 // taxDue est le montant à reverser (plancher 0 si total négatif — avoirs > factures)
 BigDecimal taxDue = declaration.taxDue() != null
 ? declaration.taxDue() : BigDecimal.ZERO;
 BigDecimal creditToCarry = declaration.taxCreditToCarryForward() != null
 ? declaration.taxCreditToCarryForward() : BigDecimal.ZERO;
 w.print("Total RS retenue;" + formatCsv(total) + LINE_SEP);
 w.print("Total RS due;" + formatCsv(taxDue) + LINE_SEP);
 w.print("Crédit à reporter M+1;" + formatCsv(creditToCarry) + LINE_SEP);
 w.flush();
 LOG.info("Export DGI RS Haïti généré : companyId={} période={}{} ({} taux, dû={}, crédit={})",
 companyId, year, String.format("-%02d", month),
 collected.size(), taxDue, creditToCarry);
 return baos.toByteArray();
 }

 /**
 * Export DGI DCR annuelle Haïti (Déclaration Comptable et Fiscale Résumée — Code Fiscal art. 195).
 *
 * <p>Échéance : 31 mars N+1. Récapitulatif annuel des opérations comptables de l'exercice.
 *
 * <p><b>v8-9 — Alimentation avec soldes réels</b> : la v7.0 retournait un squelette CSV
 * avec tous les montants à 0 (gap P0 signalé par les validateurs PME3 + PME4). La DCR est
 * désormais alimentée par :
 * <ul>
 * <li><b>Soldes par classe PCN (1 à 8)</b> — via
 * {@link JournalLineRepository#aggregateByAccountBetweenDates(UUID, LocalDate, LocalDate)}
 * puis groupage Java par premier caractère du {@code accountCode} (1=Capitaux,
 * 2=Immobilisations, 3=Stocks, 4=Tiers, 5=Financiers, 6=Charges, 7=Produits,
 * 8=Comptes spéciaux PCN).</li>
 * <li><b>Résultat fiscal & IS dû</b> — via
 * {@link TaxService#projectCorporateTax(UUID, LocalDate, LocalDate)} qui applique
 * désormais le bon régime (30% standard / 15% zone franche / 0% ONG — v8-1).</li>
 * <li><b>Acomptes IS 1% versés dans l'année</b> — somme des 12 acomptes mensuels
 * via {@link TaxService#computeMonthlyInstallmentHT(UUID, int, int)} (Code Fiscal art. 5).</li>
 * <li><b>Solde IS à payer</b> = IS dû − acomptes versés (peut être négatif = crédit).</li>
 * </ul>
 *
 * <p>Si les repositories d'agrégation ne sont pas injectés (tests unitaires pré-v8-9),
 * dégrade gracieusement vers le squelette à 0 avec un warning loggué.
 *
 * <p>Format CSV structuré avec les grandes masses du PCN Haïti :
 * <ul>
 * <li>Classe 1 — Capitaux</li>
 * <li>Classe 2 — Immobilisations</li>
 * <li>Classe 3 — Stocks</li>
 * <li>Classe 4 — Tiers</li>
 * <li>Classe 5 — Financiers</li>
 * <li>Classe 6 — Charges</li>
 * <li>Classe 7 — Produits</li>
 * <li>Classe 8 — Comptes spéciaux (engagements hors bilan)</li>
 * </ul>
 *
 * @param companyId ID de l'entreprise
 * @param year année fiscale
 * @return bytes CSV UTF-8 BOM
 */
 @Transactional(readOnly = true)
 public byte[] exportDgiDcr(UUID companyId, int year) {
 // 1. Période fiscale = année civile (le DCR Haïti est sur l'année calendar)
 LocalDate from = LocalDate.of(year, 1, 1);
 LocalDate to = LocalDate.of(year, 12, 31);

 // 2. Agréger les soldes par classe PCN (1-8)
 // Map<classe 1-8, [totalDebit, totalCredit]>
 Map<Integer, BigDecimal[]> balancesByClass = new TreeMap<>();
 for (int c = 1; c <= 8; c++) {
 balancesByClass.put(c, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
 }
 boolean hasRealBalances = false;
 if (journalLineRepository != null) {
 try {
 List<AccountAggregate> aggregates =
 journalLineRepository.aggregateByAccountBetweenDates(companyId, from, to);
 for (AccountAggregate agg : aggregates) {
 Integer pcnClass = extractPcnClass(agg.getAccountCode());
 if (pcnClass == null) continue;
 BigDecimal[] totals = balancesByClass.get(pcnClass);
 if (totals == null) continue;
 totals[0] = totals[0].add(nullToZero(agg.getTotalDebit()));
 totals[1] = totals[1].add(nullToZero(agg.getTotalCredit()));
 hasRealBalances = true;
 }
 } catch (Exception e) {
 LOG.warn("exportDgiDcr : échec agrégation JournalLineRepository pour companyId={} exercice={} — dégradation vers squelette à 0",
 companyId, year, e);
 }
 } else {
 LOG.warn("exportDgiDcr : JournalLineRepository non injecté — dégradation vers squelette à 0 (companyId={}, year={})",
 companyId, year);
 }

 // 3. Projection IS (récupère le résultat fiscal, l'IS dû et applique ZF/ONG si besoin)
 CorporateTaxProjection projection = null;
 try {
 projection = taxService.projectCorporateTax(companyId, from, to);
 } catch (Exception e) {
 LOG.warn("exportDgiDcr : échec projectCorporateTax pour companyId={} exercice={} — IS affiché à 0",
 companyId, year, e);
 }
 BigDecimal fiscalResult = projection != null ? nullToZero(projection.taxableResult()) : BigDecimal.ZERO;
 BigDecimal isDu = projection != null ? nullToZero(projection.corporateTaxNet()) : BigDecimal.ZERO;
 String isRegime = projection != null && projection.rule() != null
 ? projection.rule().eligibility() : "UNKNOWN";

 // 4. Acomptes IS 1% mensuels versés dans l'année (Code Fiscal art. 5)
 BigDecimal acomptesVerses = BigDecimal.ZERO;
 try {
 for (int month = 1; month <= 12; month++) {
 TaxService.MonthlyInstallmentHT mi = taxService.computeMonthlyInstallmentHT(companyId, year, month);
 if (mi != null && mi.installmentAmount() != null) {
 acomptesVerses = acomptesVerses.add(mi.installmentAmount());
 }
 }
 } catch (Exception e) {
 LOG.warn("exportDgiDcr : échec calcul acomptes IS 1% pour companyId={} exercice={} — acomptes affichés à 0",
 companyId, year, e);
 }

 // 5. Solde IS à payer = IS dû − acomptes versés (négatif = crédit reportable)
 BigDecimal soldeIs = isDu.subtract(acomptesVerses);

 // 6. Génération du CSV
 ByteArrayOutputStream baos = new ByteArrayOutputStream();
 baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);
 PrintWriter w = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

 w.print("DGI Haïti — DCR annuelle (art. 195 Code Fiscal)" + LINE_SEP);
 w.print("Exercice;" + year + LINE_SEP);
 w.print("Date échéance;31 mars " + (year + 1) + LINE_SEP);
 w.print(LINE_SEP);
 w.print("Classe;Libellé;Solde débiteur;Solde créditeur" + LINE_SEP);
 w.print("1;Capitaux;" + formatCsv(debitBalance(balancesByClass.get(1)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(1))) + LINE_SEP);
 w.print("2;Immobilisations;" + formatCsv(debitBalance(balancesByClass.get(2)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(2))) + LINE_SEP);
 w.print("3;Stocks;" + formatCsv(debitBalance(balancesByClass.get(3)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(3))) + LINE_SEP);
 w.print("4;Tiers;" + formatCsv(debitBalance(balancesByClass.get(4)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(4))) + LINE_SEP);
 w.print("5;Financiers;" + formatCsv(debitBalance(balancesByClass.get(5)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(5))) + LINE_SEP);
 w.print("6;Charges;" + formatCsv(debitBalance(balancesByClass.get(6)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(6))) + LINE_SEP);
 w.print("7;Produits;" + formatCsv(debitBalance(balancesByClass.get(7)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(7))) + LINE_SEP);
 w.print("8;Comptes spéciaux (engagements);" + formatCsv(debitBalance(balancesByClass.get(8)))
 + ";" + formatCsv(creditBalance(balancesByClass.get(8))) + LINE_SEP);
 w.print(LINE_SEP);
 w.print("Résultat fiscal;" + formatCsv(fiscalResult) + LINE_SEP);
 w.print("Régime IS appliqué;" + isRegime + LINE_SEP);
 w.print("IS dû (30% standard / 15% zone franche / 0% ONG exonérée);"
 + formatCsv(isDu) + LINE_SEP);
 w.print("Acomptes IS 1% versés dans l'année;" + formatCsv(acomptesVerses) + LINE_SEP);
 w.print("Solde IS à payer (ou crédit);" + formatCsv(soldeIs) + LINE_SEP);
 w.flush();

 LOG.info("Export DGI DCR Haïti généré : companyId={} exercice={} soldesRéels={} ISrégime={} ISdû={} acomptes={} solde={}",
 companyId, year, hasRealBalances, isRegime, isDu, acomptesVerses, soldeIs);
 return baos.toByteArray();
 }

 /**
 * Extrait la classe PCN (1-8) depuis un code de compte.
 *
 * <p>La classe PCN Haïti / PCG / SYSCOHADA est déterminée par le premier caractère du
 * code de compte (ex: "411000" → classe 4 "Tiers", "701000" → classe 7 "Produits").
 *
 * @param accountCode code du compte (ex: "411000"), peut être null
 * @return la classe 1-8, ou null si le code est null/vide ou ne commence pas par 1-8
 */
 private Integer extractPcnClass(String accountCode) {
 if (accountCode == null || accountCode.isEmpty()) return null;
 char first = accountCode.charAt(0);
 if (first >= '1' && first <= '8') {
 return first - '0';
 }
 return null;
 }

 /** Formate le solde débiteur = max(0, totalDebit − totalCredit). */
 private BigDecimal debitBalance(BigDecimal[] debitCredit) {
 if (debitCredit == null) return BigDecimal.ZERO;
 BigDecimal diff = nullToZero(debitCredit[0]).subtract(nullToZero(debitCredit[1]));
 return diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
 }

 /** Formate le solde créditeur = max(0, totalCredit − totalDebit). */
 private BigDecimal creditBalance(BigDecimal[] debitCredit) {
 if (debitCredit == null) return BigDecimal.ZERO;
 BigDecimal diff = nullToZero(debitCredit[1]).subtract(nullToZero(debitCredit[0]));
 return diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
 }

 /** Convertit un BigDecimal null en BigDecimal.ZERO. */
 private BigDecimal nullToZero(BigDecimal v) {
 return v != null ? v : BigDecimal.ZERO;
 }
}

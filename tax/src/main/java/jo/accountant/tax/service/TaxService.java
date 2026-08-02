package jo.accountant.tax.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.invoicing.entity.InvoiceLine;
import jo.accountant.invoicing.entity.InvoiceLineTaxType;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.InvoiceLineTaxRepository;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.purchasing.entity.PurchaseInvoice;
import jo.accountant.purchasing.entity.PurchaseInvoiceLine;
import jo.accountant.purchasing.entity.PurchaseInvoiceStatus;
import jo.accountant.purchasing.repository.PurchaseInvoiceLineRepository;
import jo.accountant.purchasing.repository.PurchaseInvoiceRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.TaxExemptionStatus;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.tax.dto.CorporateTaxProjection;
import jo.accountant.tax.entity.CorporateTaxEligibility;
import jo.accountant.tax.entity.CorporateTaxRule;
import jo.accountant.tax.repository.CorporateTaxRuleRepository;
import jo.accountant.tax.dto.CreateTaxRuleRequest;
import jo.accountant.tax.dto.CreateWithholdingRuleRequest;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.dto.TaxDeclarationSchedule;
import jo.accountant.tax.entity.TaxRule;
import jo.accountant.tax.entity.WithholdingRule;
import jo.accountant.tax.repository.TaxRuleRepository;
import jo.accountant.tax.repository.WithholdingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service fiscal (§13 Phase 16).
 *
 * <p>Gestion des règles de TVA et des retenues à la source. Export déclaratif simple
 * par période (agrégation par taux).
 
 *
 * @author jo@Dev
*/
@Service
public class TaxService {

 private static final Logger LOG = LoggerFactory.getLogger(TaxService.class);
 private static final BigDecimal HUNDRED = new BigDecimal("100");

 private final TaxRuleRepository taxRuleRepository;
 private final WithholdingRuleRepository withholdingRuleRepository;
 private final SalesInvoiceRepository invoiceRepository;
 private final InvoiceLineRepository invoiceLineRepository;
 private final PurchaseInvoiceRepository purchaseInvoiceRepository; // Audit v4.7 §4.1 — TVA déductible
 private final PurchaseInvoiceLineRepository purchaseInvoiceLineRepository;
 private final ObjectMapper objectMapper;

 // v6-1 — Repository des lignes de taxe multi-taxes par InvoiceLine (null dans les tests
 // unitaires pré-v6-1 qui ne l'injectent pas — fallback gracieux vers BigDecimal.ZERO).
 private InvoiceLineTaxRepository invoiceLineTaxRepository;

 public TaxService(TaxRuleRepository taxRuleRepository,
 WithholdingRuleRepository withholdingRuleRepository,
 SalesInvoiceRepository invoiceRepository,
 InvoiceLineRepository invoiceLineRepository,
 PurchaseInvoiceRepository purchaseInvoiceRepository,
 PurchaseInvoiceLineRepository purchaseInvoiceLineRepository,
 ObjectMapper objectMapper) {
 this.taxRuleRepository = taxRuleRepository;
 this.withholdingRuleRepository = withholdingRuleRepository;
 this.invoiceRepository = invoiceRepository;
 this.invoiceLineRepository = invoiceLineRepository;
 this.purchaseInvoiceRepository = purchaseInvoiceRepository;
 this.purchaseInvoiceLineRepository = purchaseInvoiceLineRepository;
 this.objectMapper = objectMapper;
 }

 /**
 * v6-1 — Setter pour l'injection du repository multi-taxes (évite de casser le constructeur
 * existant à 7 paramètres utilisé par les tests unitaires pré-v6-1).
 */
 @org.springframework.beans.factory.annotation.Autowired(required = false)
 public void setInvoiceLineTaxRepository(InvoiceLineTaxRepository invoiceLineTaxRepository) {
 this.invoiceLineTaxRepository = invoiceLineTaxRepository;
 }

 // --- Règles de TVA ---

 @Transactional
 public TaxRule createTaxRule(UUID companyId, CreateTaxRuleRequest req) {
 TaxRule rule = new TaxRule();
 rule.setId(UUID.randomUUID());
 rule.setCompanyId(companyId);
 rule.setCode(req.code().trim());
 rule.setLabel(req.label().trim());
 rule.setRate(req.rate());
 rule.setPayableAccountId(req.payableAccountId());
 rule.setReceivableAccountId(req.receivableAccountId());
 rule.setApplicableFrom(req.applicableFrom());
 rule.setApplicableTo(req.applicableTo());
 rule.setActive(true);
 // TVA sur encaissement : propager le mode d'exigibilité (DEBIT par défaut).
 rule.setVatMode(req.vatMode());
 TaxRule saved = taxRuleRepository.save(rule);
 LOG.info("Règle fiscale créée : code={} rate={}% vatMode={}",
 saved.getCode(), saved.getRate(), saved.getVatMode());
 return saved;
 }

 @Transactional(readOnly = true)
 public List<TaxRule> listTaxRules(UUID companyId) {
 return taxRuleRepository.findByCompanyIdOrCompanyIdIsNull(companyId);
 }

 // --- Règles de retenue à la source ---

 @Transactional
 public WithholdingRule createWithholdingRule(UUID companyId, CreateWithholdingRuleRequest req) {
 WithholdingRule rule = new WithholdingRule();
 rule.setId(UUID.randomUUID());
 rule.setCompanyId(companyId);
 rule.setCode(req.code().trim());
 rule.setLabel(req.label().trim());
 rule.setRate(req.rate());
 try {
 rule.setApplicableThirdPartyTypes(
 objectMapper.writeValueAsString(req.applicableThirdPartyTypes() != null
 ? req.applicableThirdPartyTypes() : List.of()));
 } catch (Exception e) {
 throw new IllegalStateException("Failed to serialize types", e);
 }
 // ── barème progressif (PAS FR) ──
 // bracketType défaut = FLAT si non précisé. Si PROGRESSIVE, on sérialise brackets en JSON.
 jo.accountant.core.tax.WithholdingBracketType bracketType =
 req.bracketType() != null ? req.bracketType()
 : jo.accountant.core.tax.WithholdingBracketType.FLAT;
 rule.setBracketType(bracketType);
 if (bracketType == jo.accountant.core.tax.WithholdingBracketType.PROGRESSIVE) {
 if (req.brackets() == null || req.brackets().isEmpty()) {
 throw new ValidationException("WITHHOLDING_PROGRESSIVE_NO_BRACKETS",
 "bracketType=PROGRESSIVE requiert une liste de tranches non vide " +
 "(champ 'brackets'). Ex: [{\"threshold\":0,\"rate\":0}," +
 "{\"threshold\":50000,\"rate\":10},{\"threshold\":100000,\"rate\":15}].");
 }
 try {
 rule.setBracketsJson(objectMapper.writeValueAsString(req.brackets()));
 } catch (Exception e) {
 throw new IllegalStateException("Failed to serialize brackets", e);
 }
 } else {
 rule.setBracketsJson(null);
 }
 rule.setActive(true);
 WithholdingRule saved = withholdingRuleRepository.save(rule);
 LOG.info("Règle de retenue créée : code={} rate={}{} bracketType={}",
 saved.getCode(), saved.getRate(),
 saved.getBracketType() == jo.accountant.core.tax.WithholdingBracketType.PROGRESSIVE
 ? " (PROGRESSIVE, " + (req.brackets() != null ? req.brackets().size() : 0) + " tranches)"
 : "",
 saved.getBracketType());
 return saved;
 }

 @Transactional(readOnly = true)
 public List<WithholdingRule> listWithholdingRules(UUID companyId) {
 return withholdingRuleRepository.findByCompanyId(companyId);
 }

 // --- Déclaration fiscale ---

 /**
 * Génère une déclaration fiscale pour une période : agrégation par taux de TVA
 * des factures ISSUED/PARTIALLY_PAID/PAID émises sur la période.
 *
 * <p>Export déclaratif simple — pas d'intégration de télédéclaration en v1 (§13 Phase 16).
 *
 * <p><b>(lot-C-perf-devops) — Réécriture en SQL GROUP BY</b> : l'ancien code faisait
 * 3 SELECT par statut puis bouclait sur chaque facture avec
 * {@code findByInvoiceIdOrderByCreatedAt(inv.getId())} → N+1 (1000 factures = 1003 requêtes).
 * Désormais, l'agrégation par taux de TVA est poussée en SQL via
 * {@link InvoiceLineRepository#aggregateByTaxRate} (ventes) et
 * {@link PurchaseInvoiceLineRepository#aggregateByTaxRate} (achats) : 2 requêtes SQL au total.
 * Gain attendu : ~500× sur 1000 factures.
 *
 * <p>La signature publique et le format de retour ({@link TaxDeclaration}) sont inchangés
 * — backward-compatible.
 *
 * <p><b>v6-1 — backward compat</b> : cette méthode délègue à
 * {@link #getDeclaration(UUID, LocalDate, LocalDate, String)} avec {@code taxType=null}
 * (comportement historique — agrège toutes les taxes sans filtrer par type).
 */
 @Transactional
 public TaxDeclaration getDeclaration(UUID companyId, LocalDate from, LocalDate to) {
 return getDeclaration(companyId, from, to, null);
 }

 /**
 * v6-1-multi-tax-invoice-line — Génère une déclaration fiscale filtrée par type de taxe.
 *
 * <p>Cas d'usage principal : produire 2 déclarations DGI Haïti distinctes (TVA + TCA) au lieu
 * d'une seule fusionnée. En Haïti, la TVA 10% (art. 191) et la TCA 10% (art. 196) sont
 * cumulatives sur une même ligne de facture de prestation de services, mais elles sont
 * déclarées séparément à la DGI (2 formulaires distincts).
 *
 * <p>Comportement selon {@code taxType} :
 * <ul>
 * <li><b>{@code null}</b> : comportement historique — agrège par taux via
 * {@code InvoiceLineRepository.aggregateByTaxRate} (utilise {@code invoice_line.tax_rate}
 * sans distinction de type). Tous les taux sont considérés comme de la TVA.
 * Backward-compatible avec les factures pré-v6-1 (sans {@code invoice_line_tax}).</li>
 * <li><b>{@code "VAT"}</b> : agrège les {@code InvoiceLineTax} de type VAT par taux via
 * {@link InvoiceLineTaxRepository#aggregateByTaxType}. Utilisé par
 * {@code TaxExportService.exportDgiTva}.</li>
 * <li><b>{@code "TCA"}</b> : agrège les {@code InvoiceLineTax} de type TCA par taux.
 * Utilisé par {@code TaxExportService.exportDgiTca}.</li>
 * <li><b>{@code "TURNOVER_TAX"}</b> / <b>{@code "EXCISE"}</b> : idem pour les autres types.</li>
 * </ul>
 *
 * <p><b>Crédit reporté</b> : actuellement, seul le crédit de TVA est persisté
 * (table {@code tax_credit_carried_forward} — ). Pour {@code taxType=VAT} ou {@code null},
 * on lit/écrit le crédit comme avant. Pour les autres types, on ne lit ni n'écrit de crédit
 * (la TCA et les accises n'ont pas de mécanisme de crédit reporté en Haïti).
 *
 * <p><b>TVA déductible</b> : actuellement, les achats ({@code PurchaseInvoiceLine}) ne supportent
 * pas encore le multi-taxe par ligne (uniquement {@code tax_rate} unique). Pour
 * {@code taxType=VAT} ou {@code null}, on agrège la TVA déductible comme avant. Pour les
 * autres types, {@code deductibleLines} est vide et {@code totalTaxDeductible} = 0.
 *
 * @param companyId identifiant de l'entreprise
 * @param from date de début de période (inclusive)
 * @param to date de fin de période (inclusive)
 * @param taxType type de taxe à filtrer ("VAT", "TCA", "TURNOVER_TAX", "EXCISE") ou null
 * pour le comportement historique (toutes taxes agrégées comme TVA)
 * @return la déclaration fiscale agrégée
 */
 @Transactional
 public TaxDeclaration getDeclaration(UUID companyId, LocalDate from, LocalDate to,
 String taxType) {
 // v6-1 — Si taxType est null : comportement historique (agrégation par taxRate sans
 // filtrer par taxType). Le code existant est conservé tel quel pour la backward compat.
 if (taxType == null || taxType.isBlank()) {
 return getDeclarationLegacy(companyId, from, to);
 }

 // v6-1 — Si taxType est spécifié : agrégation filtrée par taxType via InvoiceLineTaxRepository
 InvoiceLineTaxType lineTaxType;
 try {
 lineTaxType = InvoiceLineTaxType.valueOf(taxType);
 } catch (IllegalArgumentException e) {
 throw new ValidationException("TAX_TYPE_INVALID",
 "taxType invalide : " + taxType + ". Valeurs acceptées : VAT, TCA, TURNOVER_TAX, EXCISE, ou null.");
 }

 // Si le repo multi-taxes n'est pas injecté (tests pré-v6-1), retourner une déclaration vide.
 if (invoiceLineTaxRepository == null) {
 LOG.warn("InvoiceLineTaxRepository non injecté — déclaration {} vide pour company {}",
 taxType, companyId);
 return new TaxDeclaration(companyId, from, to,
 List.of(), List.of(),
 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
 BigDecimal.ZERO, BigDecimal.ZERO);
 }

 // ── TVA / TCA / autres COLLECTÉE (factures de ventes ISSUED + PARTIALLY_PAID + PAID) ──
 // Agrégation SQL filtrée par taxType — 1 seule requête.
 List<InvoiceStatus> salesStatuses = List.of(
 InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID);
 List<InvoiceLineTaxRepository.TaxTypeRateAggregate> salesAggregates =
 invoiceLineTaxRepository.aggregateByTaxType(companyId, from, to, salesStatuses, lineTaxType);

 String taxLabelPrefix = taxType.equals("VAT") ? "TVA collectée"
 : taxType.equals("TCA") ? "TCA collectée"
 : taxType + " collectée";
 List<TaxDeclaration.TaxLine> collectedLines = new ArrayList<>();
 BigDecimal totalTaxCollected = BigDecimal.ZERO;
 for (InvoiceLineTaxRepository.TaxTypeRateAggregate agg : salesAggregates) {
 BigDecimal rate = agg.getTaxRate() != null ? agg.getTaxRate() : BigDecimal.ZERO;
 if (rate.compareTo(BigDecimal.ZERO) == 0) continue; // taux 0% non déclarable
 BigDecimal base = agg.getTotalHt() != null ? agg.getTotalHt() : BigDecimal.ZERO;
 BigDecimal tax = agg.getTotalTax() != null ? agg.getTotalTax() : BigDecimal.ZERO;
 collectedLines.add(new TaxDeclaration.TaxLine(
 taxType + "-" + rate + "%", taxLabelPrefix + " " + rate + "%", rate, base, tax));
 totalTaxCollected = totalTaxCollected.add(tax);
 }

 // ── TVA / TCA / autres DÉDUCTIBLE (factures d'achats) ──
 // Les achats ne supportent pas encore le multi-taxe (PurchaseInvoiceLine n'a que taxRate).
 // Pour taxType=VAT, on fallback sur l'agrégation existante (tout est considéré TVA).
 // Pour taxType=TCA/autres, on retourne vide (pas de TCA déductible implémenté).
 List<TaxDeclaration.TaxLine> deductibleLines = new ArrayList<>();
 BigDecimal totalTaxDeductible = BigDecimal.ZERO;
 if (lineTaxType == InvoiceLineTaxType.VAT) {
 List<PurchaseInvoiceStatus> purchaseStatuses = List.of(
 PurchaseInvoiceStatus.RECEIVED, PurchaseInvoiceStatus.PARTIALLY_PAID, PurchaseInvoiceStatus.PAID);
 List<PurchaseInvoiceLineRepository.TaxRateAggregate> purchaseAggregates =
 purchaseInvoiceLineRepository.aggregateByTaxRate(companyId, from, to, purchaseStatuses);
 for (PurchaseInvoiceLineRepository.TaxRateAggregate agg : purchaseAggregates) {
 BigDecimal rate = agg.getTaxRate() != null ? agg.getTaxRate() : BigDecimal.ZERO;
 if (rate.compareTo(BigDecimal.ZERO) == 0) continue; // TVA 0% non déductible
 BigDecimal base = agg.getTotalHt() != null ? agg.getTotalHt() : BigDecimal.ZERO;
 BigDecimal tax = agg.getTotalTax() != null ? agg.getTotalTax() : BigDecimal.ZERO;
 deductibleLines.add(new TaxDeclaration.TaxLine(
 "VAT-DED-" + rate + "%", "TVA déductible " + rate + "%", rate, base, tax));
 totalTaxDeductible = totalTaxDeductible.add(tax);
 }
 }

 // ── Crédit reporté : uniquement pour VAT (la TCA et les accises n'ont pas de crédit) ──
 BigDecimal taxCreditCarriedForward = BigDecimal.ZERO;
 BigDecimal taxCreditToCarryForward = BigDecimal.ZERO;
 BigDecimal taxDue;
 if (lineTaxType == InvoiceLineTaxType.VAT) {
 int periodYear = from.getYear();
 int periodMonth = from.getMonthValue();
 java.time.LocalDate prevPeriodDate = from.minusMonths(1);
 int prevYear = prevPeriodDate.getYear();
 int prevMonth = prevPeriodDate.getMonthValue();

 taxCreditCarriedForward = readCarriedForwardCredit(companyId, prevYear, prevMonth);
 BigDecimal netTax = totalTaxCollected.subtract(totalTaxDeductible).subtract(taxCreditCarriedForward);
 if (netTax.compareTo(BigDecimal.ZERO) >= 0) {
 taxDue = netTax;
 taxCreditToCarryForward = BigDecimal.ZERO;
 } else {
 taxDue = BigDecimal.ZERO;
 taxCreditToCarryForward = netTax.negate();
 }
 if (taxCreditToCarryForward.compareTo(BigDecimal.ZERO) > 0) {
 persistCarriedForwardCredit(companyId, periodYear, periodMonth, taxCreditToCarryForward);
 }
 } else {
 // TCA / TURNOVER_TAX / EXCISE : pas de crédit reporté — taxDue = collectée - déductible (0)
 BigDecimal netTax = totalTaxCollected.subtract(totalTaxDeductible);
 taxDue = netTax.compareTo(BigDecimal.ZERO) >= 0 ? netTax : BigDecimal.ZERO;
 }

 LOG.info("Déclaration {} {} [{} à {}] : collecté={}, déductible={}, dû={}",
 taxType, companyId, from, to, totalTaxCollected, totalTaxDeductible, taxDue);

 return new TaxDeclaration(companyId, from, to, collectedLines, deductibleLines,
 totalTaxCollected, totalTaxDeductible, taxCreditCarriedForward, taxDue, taxCreditToCarryForward);
 }

 /**
 * v6-1 — Ancien code de {@link #getDeclaration(UUID, LocalDate, LocalDate)} préservé à
 * l'identique pour la backward compat (taxType=null). Conservé en méthode privée séparée
 * pour ne pas dupliquer la logique de crédit TVA reporté qui y est encapsulée.
 */
 private TaxDeclaration getDeclarationLegacy(UUID companyId, LocalDate from, LocalDate to) {
 // ── Audit v4.7 §4.1 FIX CRITIQUE : calcul de la TVA déductible (achats)
 // en plus de la TVA collectée (ventes). La TVA due = TVA collectée − TVA déductible.
 // Sans ce fix, la déclaration n'affichait que le collecté — l'entreprise ne savait pas
 // combien elle devait réellement (art. 286 CGI).

 // ── TVA COLLECTÉE (factures de ventes ISSUED + PARTIALLY_PAID + PAID) ──
 // Une seule requête SQL GROUP BY au lieu de N+1.
 List<InvoiceStatus> salesStatuses = List.of(
 InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID);
 List<InvoiceLineRepository.TaxRateAggregate> salesAggregates =
 invoiceLineRepository.aggregateByTaxRate(companyId, from, to, salesStatuses);

 List<TaxDeclaration.TaxLine> collectedLines = new ArrayList<>();
 BigDecimal totalTaxCollected = BigDecimal.ZERO;
 for (InvoiceLineRepository.TaxRateAggregate agg : salesAggregates) {
 BigDecimal rate = agg.getTaxRate() != null ? agg.getTaxRate() : BigDecimal.ZERO;
 if (rate.compareTo(BigDecimal.ZERO) == 0) continue; // TVA 0% non déclarable
 BigDecimal base = agg.getTotalHt() != null ? agg.getTotalHt() : BigDecimal.ZERO;
 BigDecimal tax = agg.getTotalTax() != null ? agg.getTotalTax() : BigDecimal.ZERO;
 collectedLines.add(new TaxDeclaration.TaxLine(
 "TAX-" + rate + "%", "TVA collectée " + rate + "%", rate, base, tax));
 totalTaxCollected = totalTaxCollected.add(tax);
 }

 // ── TVA DÉDUCTIBLE (factures d'achats RECEIVED + PARTIALLY_PAID + PAID) ──
 // Audit v4.7 §4.1 calcul de la TVA déductible (côté achats).
 // Une seule requête SQL GROUP BY au lieu de N+1.
 List<PurchaseInvoiceStatus> purchaseStatuses = List.of(
 PurchaseInvoiceStatus.RECEIVED, PurchaseInvoiceStatus.PARTIALLY_PAID, PurchaseInvoiceStatus.PAID);
 List<PurchaseInvoiceLineRepository.TaxRateAggregate> purchaseAggregates =
 purchaseInvoiceLineRepository.aggregateByTaxRate(companyId, from, to, purchaseStatuses);

 List<TaxDeclaration.TaxLine> deductibleLines = new ArrayList<>();
 BigDecimal totalTaxDeductible = BigDecimal.ZERO;
 for (PurchaseInvoiceLineRepository.TaxRateAggregate agg : purchaseAggregates) {
 BigDecimal rate = agg.getTaxRate() != null ? agg.getTaxRate() : BigDecimal.ZERO;
 if (rate.compareTo(BigDecimal.ZERO) == 0) continue; // TVA 0% non déductible
 BigDecimal base = agg.getTotalHt() != null ? agg.getTotalHt() : BigDecimal.ZERO;
 BigDecimal tax = agg.getTotalTax() != null ? agg.getTotalTax() : BigDecimal.ZERO;
 deductibleLines.add(new TaxDeclaration.TaxLine(
 "TAX-DED-" + rate + "%", "TVA déductible " + rate + "%", rate, base, tax));
 totalTaxDeductible = totalTaxDeductible.add(tax);
 }

 // ── TVA DUE = TVA collectée − TVA déductible (plancher à 0) ──
 // Si déductible > collecté : crédit de TVA à reporter sur la prochaine période.
 // Lot B le crédit est désormais PERSISTÉ en table tax_credit_carried_forward.
 // Lecture : crédit reporté de la période précédente (M-1 si déclaration mensuelle).
 // Écriture : si taxCreditToCarryForward > 0, on upsert la ligne pour la période courante
 // afin qu'elle soit lue à la déclaration suivante.
 // Période déterminée depuis `from` (début du mois de la déclaration mensuelle).
 int periodYear = from.getYear();
 int periodMonth = from.getMonthValue();
 // Période précédente (M-1) — pour lire le crédit reporté par la déclaration précédente.
 java.time.LocalDate prevPeriodDate = from.minusMonths(1);
 int prevYear = prevPeriodDate.getYear();
 int prevMonth = prevPeriodDate.getMonthValue();

 BigDecimal taxCreditCarriedForward = readCarriedForwardCredit(companyId, prevYear, prevMonth);
 BigDecimal netTax = totalTaxCollected.subtract(totalTaxDeductible).subtract(taxCreditCarriedForward);
 BigDecimal taxDue;
 BigDecimal taxCreditToCarryForward;
 if (netTax.compareTo(BigDecimal.ZERO) >= 0) {
 taxDue = netTax;
 taxCreditToCarryForward = BigDecimal.ZERO;
 } else {
 taxDue = BigDecimal.ZERO;
 taxCreditToCarryForward = netTax.negate();
 }

 // Lot B persister le crédit pour la période suivante si > 0.
 // Idempotent : si la même déclaration est recalculée, on upsert (même valeur).
 if (taxCreditToCarryForward.compareTo(BigDecimal.ZERO) > 0) {
 persistCarriedForwardCredit(companyId, periodYear, periodMonth, taxCreditToCarryForward);
 }

 LOG.info("Déclaration TVA {} [{} à {}] : collecté={}, déductible={}, crédit reporté (période {}-{})={}, dû={}, crédit à reporter={}",
 companyId, from, to, totalTaxCollected, totalTaxDeductible,
 prevYear, prevMonth, taxCreditCarriedForward, taxDue, taxCreditToCarryForward);

 return new TaxDeclaration(companyId, from, to, collectedLines, deductibleLines,
 totalTaxCollected, totalTaxDeductible, taxCreditCarriedForward, taxDue, taxCreditToCarryForward);
 }

 /**
 * Lot B Lit le crédit de taxe reporté pour une période donnée.
 *
 * <p>Recherche dans {@code tax_credit_carried_forward} la ligne correspondant à
 * {@code (companyId, taxType, year, month)}. Si aucune ligne n'est trouvée (première
 * déclaration de l'entreprise, ou pas de crédit reporté), retourne {@code ZERO}.
 *
 * @param taxType type de taxe (VAT, TCA, WITHHOLDING pour la retenue à la source)
 */
 private BigDecimal readCarriedForwardCredit(UUID companyId, int year, int month,
 jo.accountant.tax.entity.TaxType taxType) {
 return taxCreditCarriedForwardRepository
 .findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(companyId, taxType, year, month)
 .filter(jo.accountant.tax.entity.TaxCreditCarriedForward::isCarriedToNext)
 .map(jo.accountant.tax.entity.TaxCreditCarriedForward::getCreditAmount)
 .orElse(BigDecimal.ZERO);
 }

 /** Variante par défaut pour {@link TaxType#VAT} — préservée pour compatibilité avec
 * {@link #getDeclarationLegacy} et les appels existants. */
 private BigDecimal readCarriedForwardCredit(UUID companyId, int year, int month) {
 return readCarriedForwardCredit(companyId, year, month, jo.accountant.tax.entity.TaxType.VAT);
 }

 /**
 * Lot B Persiste (ou met à jour) le crédit de taxe pour une période donnée.
 *
 * <p>Si une ligne existe déjà pour {@code (companyId, taxType, year, month)}, on met à jour
 * le {@code creditAmount} (idempotence — recalcul de la même déclaration).
 * Sinon, on crée une nouvelle ligne.
 *
 * @param taxType type de taxe (VAT, TCA, WITHHOLDING pour la retenue à la source)
 */
 private void persistCarriedForwardCredit(UUID companyId, int year, int month,
 BigDecimal creditAmount,
 jo.accountant.tax.entity.TaxType taxType) {
 jo.accountant.tax.entity.TaxCreditCarriedForward credit = taxCreditCarriedForwardRepository
 .findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(companyId, taxType, year, month)
 .orElseGet(() -> {
 jo.accountant.tax.entity.TaxCreditCarriedForward c = new jo.accountant.tax.entity.TaxCreditCarriedForward();
 c.setId(UUID.randomUUID());
 c.setCompanyId(companyId);
 c.setTaxType(taxType);
 c.setPeriodYear(year);
 c.setPeriodMonth(month);
 c.setCreatedAt(java.time.Instant.now());
 return c;
 });
 credit.setCreditAmount(creditAmount);
 credit.setCarriedToNext(true);
 taxCreditCarriedForwardRepository.save(credit);
 LOG.info("Crédit {} persisté pour company {} période {}-{} : {} HTG",
 taxType, companyId, year, month, creditAmount);
 }

 /** Variante par défaut pour {@link TaxType#VAT}. */
 private void persistCarriedForwardCredit(UUID companyId, int year, int month,
 BigDecimal creditAmount) {
 persistCarriedForwardCredit(companyId, year, month, creditAmount,
 jo.accountant.tax.entity.TaxType.VAT);
 }

 // --- Impôt sur les Sociétés (IS) — audit v4.7 §4.1 --

 // Injectés via setter car ajoutés après le constructeur existant (audit v4.7 §4.1)
 private CorporateTaxRuleRepository corporateTaxRuleRepository;
 private jo.accountant.financialstatements.service.FinancialStatementsService financialStatementsService;
 // Lot B repository Company pour déterminer le pays et appliquer la règle d'IS par défaut
 // correspondante (HT: 30%, FR: 25%, autre: 25% par défaut conservateur).
 private CompanyRepository companyRepository;
 // Lot B calendrier déclaratif DGI Haïti (routing par pays).
 private HaitianTaxDeclarationSchedule haitianTaxDeclarationSchedule;
 // Lot B repository des crédits de TVA reportés (remplace le BigDecimal.ZERO hardcoded).
 private jo.accountant.tax.repository.TaxCreditCarriedForwardRepository taxCreditCarriedForwardRepository;

 // Injection mise à jour : ajouter corporateTaxRuleRepository et financialStatementsService
 // Note : on les injecte via setter pour éviter de casser le constructeur existant
 @org.springframework.beans.factory.annotation.Autowired
 public void setCorporateTaxDependencies(
 CorporateTaxRuleRepository corporateTaxRuleRepository,
 jo.accountant.financialstatements.service.FinancialStatementsService financialStatementsService,
 CompanyRepository companyRepository,
 HaitianTaxDeclarationSchedule haitianTaxDeclarationSchedule,
 jo.accountant.tax.repository.TaxCreditCarriedForwardRepository taxCreditCarriedForwardRepository) {
 this.corporateTaxRuleRepository = corporateTaxRuleRepository;
 this.financialStatementsService = financialStatementsService;
 this.companyRepository = companyRepository;
 this.haitianTaxDeclarationSchedule = haitianTaxDeclarationSchedule;
 this.taxCreditCarriedForwardRepository = taxCreditCarriedForwardRepository;
 }

 /**
 * Projette l'Impôt sur les Sociétés (IS) pour un exercice fiscal.
 *
 * <p><b>Audit v4.7 §4.1 FIX</b> : la v4.7 ne comportait aucun module IS.
 * Pour toute société commerciale redevable de l'IS (France : 25%, taux PME 15% jusqu'à
 * 42 500 €), l'utilisateur devait sortir du SaaS pour faire ce calcul.
 *
 * <p>Calcul :
 * <ol>
 * <li>Résultat comptable = Produits − Charges (depuis le CR via FinancialStatementsService)</li>
 * <li>Résultat fiscal = Résultat comptable + réintégrations (Charasse, etc.) − déductions (LTPE, etc.)</li>
 * <li>IS brut = Résultat fiscal × taux (15% ou 25% selon éligibilité PME)</li>
 * <li>IS net = IS brut − avoirs fiscaux − crédits d'impôt</li>
 * <li>4 acomptes (mars, juin, septembre, décembre) + solde au 15 mai N+1</li>
 * </ol>
 *
 * <p>Si aucune règle d'IS n'est configurée pour l'entreprise, on utilise les valeurs par
 * défaut françaises 2026 (25% normal, 15% PME jusqu'à 42 500 €).
 */
 @Transactional(readOnly = true)
 public CorporateTaxProjection projectCorporateTax(UUID companyId, LocalDate from, LocalDate to) {
 // 1. Résultat comptable depuis le CR
 jo.accountant.financialstatements.dto.IncomeStatement incomeStatement =
 financialStatementsService.getIncomeStatement(companyId, from, to);
 BigDecimal accountingResult = incomeStatement.netResult();

 // 2. Récupérer la règle d'IS active (ou défaut par pays — Lot B )
 // Lot B la règle d'IS par défaut dépend du pays de la Company.
 // HT (Haïti) : 30% normal, 15% zones franches (pas de seuil).
 // FR (France) : 25% normal, 15% PME jusqu'à 42 500 € (comportement historique).
 // Autres pays : 25% par défaut conservateur.
 //
 // v8-1 — IS Zone Franche 15% + ONG 0% (Code Fiscal art. 195) :
 // si la Company a isFreeZone=true ou taxExemptionStatus=FREE_ZONE → règle ZF 15%,
 // si taxExemptionStatus=NGO_EXEMPT → règle ONG 0%.
 String countryCode = resolveCompanyCountry(companyId);
 CorporateTaxRule rule = resolveCorporateTaxRule(companyId, countryCode);

 // 3. Réintégrations extra-comptables (à enrichir en v4.8 avec saisie utilisateur)
 // Pour l'instant : valeurs à 0 (l'utilisateur devra saisir manuellement les ajustements)
 BigDecimal charasseAddition = BigDecimal.ZERO; // Non implémenté : calculer depuis les dividendes reçus
 BigDecimal otherAdditions = BigDecimal.ZERO;
 BigDecimal longTermCapitalGainDeduction = BigDecimal.ZERO; // Non implémenté : plus-values LTPE
 BigDecimal otherDeductions = BigDecimal.ZERO;
 BigDecimal totalAdditions = charasseAddition.add(otherAdditions);
 BigDecimal totalDeductions = longTermCapitalGainDeduction.add(otherDeductions);

 CorporateTaxProjection.ExtraComptableAdjustments adjustments =
 new CorporateTaxProjection.ExtraComptableAdjustments(
 charasseAddition, otherAdditions, longTermCapitalGainDeduction,
 otherDeductions, totalAdditions, totalDeductions);

 // 4. Résultat fiscal
 BigDecimal taxableResult = accountingResult.add(totalAdditions).subtract(totalDeductions);

 // 5. IS brut selon éligibilité (PME / LARGE / UNKNOWN / FREE_ZONE / NGO_EXEMPT)
 BigDecimal appliedRate;
 BigDecimal corporateTaxBrut;
 CorporateTaxEligibility eligibility = rule.getEligibility();
 if (eligibility == CorporateTaxEligibility.NGO_EXEMPT) {
 // v8-1 — ONG exonérée (Code Fiscal art. 195) : IS = 0, quel que soit le résultat.
 appliedRate = BigDecimal.ZERO;
 corporateTaxBrut = BigDecimal.ZERO;
 } else if (eligibility == CorporateTaxEligibility.FREE_ZONE
 && rule.getReducedRate() != null) {
 // v8-1 — Zone franche (Code Fiscal art. 195) : IS réduit 15% sur la totalité
 // du résultat fiscal (pas de seuil PME — l'agrément CODEVI/SONAPI prime).
 appliedRate = rule.getReducedRate();
 corporateTaxBrut = taxableResult.multiply(appliedRate)
 .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
 } else if (eligibility == CorporateTaxEligibility.SME
 && rule.getReducedRate() != null
 && rule.getReducedRateThreshold() != null
 && taxableResult.compareTo(rule.getReducedRateThreshold()) <= 0) {
 // PME éligible : 15% sur la totalité si sous le seuil
 appliedRate = rule.getReducedRate();
 corporateTaxBrut = taxableResult.multiply(appliedRate)
 .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
 } else if (eligibility == CorporateTaxEligibility.SME
 && rule.getReducedRate() != null
 && rule.getReducedRateThreshold() != null
 && taxableResult.compareTo(BigDecimal.ZERO) > 0) {
 // PME au-delà du seuil : 15% jusqu'au seuil + 25% au-delà
 BigDecimal reducedPortion = rule.getReducedRateThreshold()
 .multiply(rule.getReducedRate()).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
 BigDecimal standardPortion = taxableResult.subtract(rule.getReducedRateThreshold())
 .multiply(rule.getStandardRate()).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
 corporateTaxBrut = reducedPortion.add(standardPortion);
 appliedRate = corporateTaxBrut.multiply(HUNDRED).divide(taxableResult, 4, java.math.RoundingMode.HALF_UP);
 } else {
 // Grande entreprise ou non éligible : taux normal sur la totalité
 appliedRate = rule.getStandardRate();
 corporateTaxBrut = taxableResult.multiply(appliedRate)
 .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
 }

 // 6. IS net (crédits d'impôt à 0 par défaut — Non implémenté : saisie utilisateur)
 BigDecimal taxCredits = BigDecimal.ZERO;
 BigDecimal corporateTaxNet = corporateTaxBrut.subtract(taxCredits);

 // 7. Acomptes (4 par an en France : 15 mars, 15 juin, 15 septembre, 15 décembre)
 // Chaque acompte = 25% de l'IS N-1 (ici, par défaut, 25% de l'IS net de l'exercice courant)
 BigDecimal installmentAmount = corporateTaxNet.divide(new BigDecimal("4"), 2, java.math.RoundingMode.HALF_UP);
 int year = from.getYear();
 List<CorporateTaxProjection.Installment> installments = List.of(
 new CorporateTaxProjection.Installment(LocalDate.of(year, 3, 15), installmentAmount, "Acompte 1er trimestre " + year),
 new CorporateTaxProjection.Installment(LocalDate.of(year, 6, 15), installmentAmount, "Acompte 2e trimestre " + year),
 new CorporateTaxProjection.Installment(LocalDate.of(year, 9, 15), installmentAmount, "Acompte 3e trimestre " + year),
 new CorporateTaxProjection.Installment(LocalDate.of(year, 12, 15), installmentAmount, "Acompte 4e trimestre " + year)
 );

 // 8. Solde à verser au 15 mai N+1 = IS net − somme acomptes
 BigDecimal balanceDue = corporateTaxNet.subtract(installmentAmount.multiply(new BigDecimal("4")));

 CorporateTaxProjection.CorporateTaxRuleSummary ruleSummary =
 new CorporateTaxProjection.CorporateTaxRuleSummary(
 rule.getStandardRate(), rule.getReducedRate(), rule.getReducedRateThreshold(),
 rule.getEligibility() != null ? rule.getEligibility().name() : "UNKNOWN");

 LOG.info("Projection IS {} [{} à {}] : accountingResult={}, taxableResult={}, rate={}%, IS brut={}, IS net={}, balanceDue={}",
 companyId, from, to, accountingResult, taxableResult, appliedRate, corporateTaxBrut, corporateTaxNet, balanceDue);

 return new CorporateTaxProjection(companyId, from, to, accountingResult, adjustments,
 taxableResult, appliedRate, corporateTaxBrut, taxCredits, corporateTaxNet,
 installments, balanceDue, ruleSummary);
 }

 // ════════════════════════════════════════════════════════════════════════
 // V6-5 — Acompte IS 1% mensuel sur encaissements (Code Fiscal Haïti art. 5)
 // ════════════════════════════════════════════════════════════════════════
 //
 // Découlé des validations PME/expert-comptable (29 juillet 2026) :
 // - Maître Jean-Robert Pierre-Louis : acompte IS 1% non calculé (P1)
 // - PME4 Caribbean Textiles : BLOQUANT — calcul 4 trimestres français au lieu du 1% mensuel HT
 //
 // Conformément au Code Fiscal Haïtien art. 5, les entreprises haïtiennes versent
 // un acompte d'IS de 1% sur les encaissements bruts du mois, à verser le 15 du
 // mois suivant (M+1). Cet acompte est distinct des acomptes trimestriels français
 // (15 mars/juin/sept/déc — CGI art. 1668).
 //
 // Implémentation :
 // - Lecture des encaissements bruts du mois = SUM(sales_invoice.total_amount) où
 // status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID') et issue_date dans le mois
 // - Calcul acompte = encaissements × 1% (plancher 0)
 // - Retourne un DTO MonthlyInstallmentHT avec montant + date échéance 15 M+1
 // ════════════════════════════════════════════════════════════════════════

 /**
 * Calcule l'acompte IS mensuel 1% sur encaissements bruts pour une entreprise haïtienne
 * (Code Fiscal art. 5). Applicable uniquement si {@code company.country = 'HT'}.
 *
 * <p>Pour les autres pays (FR, CA), retourne un montant de 0 avec un warning loggué —
 * l'acompte IS 1% est spécifique au régime fiscal haïtien.
 *
 * @param companyId ID de l'entreprise
 * @param year année de la période
 * @param month mois de la période (1-12)
 * @return {@link MonthlyInstallmentHT} contenant le montant, la base et la date d'échéance
 */
 @Transactional(readOnly = true)
 public MonthlyInstallmentHT computeMonthlyInstallmentHT(UUID companyId, int year, int month) {
 // 1. Vérifier que l'entreprise est haïtienne
 String countryCode = resolveCompanyCountry(companyId);
 if (!"HT".equals(countryCode)) {
 LOG.warn("Acompte IS 1% mensuel demandé pour companyId={} pays={} — non applicable (spécifique Haïti). Retourne 0.",
 companyId, countryCode);
 LocalDate dueDate = LocalDate.of(year, month, 1).plusMonths(1).withDayOfMonth(15);
 return new MonthlyInstallmentHT(companyId, year, month, BigDecimal.ZERO, BigDecimal.ZERO,
 dueDate, "NOT_APPLICABLE", "Pays non-HT — acompte IS 1% non applicable");
 }

 // 2. Calculer les encaissements bruts du mois = SUM(sales_invoice.total_amount)
 // où issue_date IN [year-month-01, year-month-dernier jour]
 LocalDate from = LocalDate.of(year, month, 1);
 LocalDate to = from.plusMonths(1).minusDays(1);

 BigDecimal grossReceipts = invoiceRepository
 .sumTotalAmountByCompanyIdAndIssueDateBetweenAndStatusIn(
 companyId, from, to,
 List.of("ISSUED", "PARTIALLY_PAID", "PAID"))
 .orElse(BigDecimal.ZERO);

 // 3. Acompte = encaissements × 1%
 BigDecimal installmentAmount = grossReceipts
 .multiply(new BigDecimal("0.01"))
 .setScale(2, java.math.RoundingMode.HALF_UP);

 // 4. Date échéance : 15 du mois M+1 (Code Fiscal art. 5)
 LocalDate dueDate = from.plusMonths(1).withDayOfMonth(15);

 LOG.info("Acompte IS 1% Haïti {} [{}-{}] : encaissementsBruts={}, acompte={}, échéance={}",
 companyId, String.format("%04d", year), String.format("%02d", month),
 grossReceipts, installmentAmount, dueDate);

 return new MonthlyInstallmentHT(companyId, year, month, grossReceipts, installmentAmount,
 dueDate, "HT_1_PERCENT", "Acompte IS 1% sur encaissements bruts (Code Fiscal art. 5)");
 }

 /**
 * Record DTO — Acompte IS mensuel 1% Haïti (Code Fiscal art. 5).
 *
 * @param companyId ID de l'entreprise
 * @param year année de la période
 * @param month mois de la période (1-12)
 * @param grossReceipts encaissements bruts du mois (base de calcul)
 * @param installmentAmount montant de l'acompte = grossReceipts × 1%
 * @param dueDate date d'échéance (15 du mois M+1)
 * @param installmentType type d'acompte ("HT_1_PERCENT" ou "NOT_APPLICABLE")
 * @param description description lisible
 */
 public record MonthlyInstallmentHT(
 UUID companyId,
 int year,
 int month,
 BigDecimal grossReceipts,
 BigDecimal installmentAmount,
 LocalDate dueDate,
 String installmentType,
 String description
 ) {}

 /**
 * Règle d'IS par défaut selon le pays de la Company (Lot B ).
 *
 * <p>Avant la , cette méthode s'appelait {@code defaultFrenchCorporateTaxRule()} et
 * hardcodait IS=25% (France). Une entreprise haïtienne sans CorporateTaxRule configurée
 * était calculée à 25% au lieu de 30% — sous-évaluation de l'IS de 5 points.
 *
 * <p>Valeurs par pays (à valider par un expert-comptable DGI) :
 * <ul>
 * <li><b>HT</b> (Haïti) : standardRate=30%, reducedRate=15% (zones franches),
 * reducedRateThreshold=null (pas de seuil — le taux réduit s'applique sur la totalité
 * pour les entreprises agréées en zone franche), eligibility=UNKNOWN.</li>
 * <li><b>FR</b> (France) : standardRate=25%, reducedRate=15% PME jusqu'à 42 500 €
 * (comportement historique — CGI art. 219).</li>
 * <li><b>Autres</b> (CA, etc.) : standardRate=25% (conservateur — par défaut l'entreprise
 * n'est pas éligible au taux réduit).</li>
 * </ul>
 *
 * @param countryCode code ISO 3166-1 alpha-2 du pays de la Company ("HT", "FR", "CA", ...)
 * @return une CorporateTaxRule non persistée avec les taux par défaut du pays
 */
 private CorporateTaxRule defaultCorporateTaxRule(String countryCode) {
 CorporateTaxRule rule = new CorporateTaxRule();
 rule.setActive(true);
 rule.setEligibility(CorporateTaxEligibility.UNKNOWN);

 if (countryCode == null) {
 // Pas de country résolu — comportement historique (France) par sécurité.
 countryCode = "FR";
 }
 // v8-1 — renseigne countryCode sur la règle ad-hoc pour cohérence avec
 // les règles persistées (qui ont toutes un country_code non null depuis V76).
 rule.setCountryCode(countryCode.toUpperCase(java.util.Locale.ROOT));
 rule.setFreeZoneRate(false);
 rule.setNgoExemptRate(false);

 switch (countryCode.toUpperCase(java.util.Locale.ROOT)) {
 case "HT": // Haïti — Code Fiscal art. 195
 rule.setStandardRate(new BigDecimal("30"));
 rule.setReducedRate(new BigDecimal("15")); // zones franches
 rule.setReducedRateThreshold(null); // pas de seuil PME en Haïti
 break;
 case "FR": // France — CGI art. 219
 rule.setStandardRate(new BigDecimal("25"));
 rule.setReducedRate(new BigDecimal("15"));
 rule.setReducedRateThreshold(new BigDecimal("42500"));
 break;
 default: // Canada, autres — défaut conservateur 25% sans taux réduit
 rule.setStandardRate(new BigDecimal("25"));
 rule.setReducedRate(null);
 rule.setReducedRateThreshold(null);
 break;
 }
 return rule;
 }

 /**
 * Récupère le code pays (ISO 3166-1 alpha-2) d'une Company. Lot B .
 *
 * <p>Retourne {@code null} si la Company n'existe pas ou si le country n'est pas renseigné
 * (la règle par défaut sera alors "FR" par sécurité — comportement historique).
 */
 private String resolveCompanyCountry(UUID companyId) {
 if (companyRepository == null || companyId == null) {
 return null;
 }
 return companyRepository.findById(companyId)
 .map(Company::getCountry)
 .orElse(null);
 }

 /**
 * v8-1 — Résout la {@link CorporateTaxRule} applicable à une Company en tenant compte
 * de son statut d'exonération fiscale (zone franche / ONG).
 *
 * <p>Ordre de résolution (Code Fiscal Haïti art. 195) :
 * <ol>
 * <li>Si {@code Company.taxExemptionStatus == NGO_EXEMPT} → règle ONG 0%
 * (lookup {@code findByCountryCodeAndNgoExemptRateTrueAndActiveTrue}).</li>
 * <li>Sinon si {@code Company.isFreeZone == true} OU
 * {@code Company.taxExemptionStatus == FREE_ZONE} → règle ZF 15%
 * (lookup {@code findByCountryCodeAndFreeZoneRateTrueAndActiveTrue}).</li>
 * <li>Sinon → comportement historique : règle par entreprise
 * ({@code findByCompanyIdAndActiveTrue}) ou défaut par pays
 * ({@code defaultCorporateTaxRule}).</li>
 * </ol>
 *
 * <p>Si la règle ZF/ONG n'existe pas en base (DB non migrée V90), on construit une
 * règle ad-hoc avec les taux réglementaires (15% ZF / 0% ONG) pour éviter un NPE
 * et garantir que le calcul d'IS aboutisse.
 *
 * @param companyId ID de l'entreprise
 * @param countryCode code pays résolu (peut être null — fallback "FR")
 * @return la CorporateTaxRule à appliquer
 */
 private CorporateTaxRule resolveCorporateTaxRule(UUID companyId, String countryCode) {
 // 1. Charger la Company pour inspecter isFreeZone / taxExemptionStatus
 Company company = null;
 if (companyRepository != null && companyId != null) {
 company = companyRepository.findById(companyId).orElse(null);
 }

 String resolvedCountry = countryCode;
 if (resolvedCountry == null && company != null) {
 resolvedCountry = company.getCountry();
 }
 if (resolvedCountry == null) {
 // Pas de country résolu — comportement historique (France) par sécurité.
 resolvedCountry = "FR";
 }

 // 2. ONG exonérée (Code Fiscal art. 195) — IS 0%
 if (company != null
 && company.getTaxExemptionStatus() == TaxExemptionStatus.NGO_EXEMPT) {
 CorporateTaxRule rule = null;
 if (corporateTaxRuleRepository != null) {
 rule = corporateTaxRuleRepository
 .findByCountryCodeAndNgoExemptRateTrueAndActiveTrue(resolvedCountry)
 .orElse(null);
 }
 if (rule == null) {
 // Fallback défensif : règle ONG ad-hoc (DB non migrée V90)
 rule = new CorporateTaxRule();
 rule.setActive(true);
 rule.setEligibility(CorporateTaxEligibility.NGO_EXEMPT);
 rule.setCountryCode(resolvedCountry);
 rule.setStandardRate(BigDecimal.ZERO);
 rule.setReducedRate(BigDecimal.ZERO);
 rule.setReducedRateThreshold(null);
 rule.setFreeZoneRate(false);
 rule.setNgoExemptRate(true);
 } else if (rule.getEligibility() == null) {
 // Sécurité : forcer eligibility=NGO_EXEMPT même si la seed V76 l'avait omis
 rule.setEligibility(CorporateTaxEligibility.NGO_EXEMPT);
 }
 LOG.info("resolveCorporateTaxRule : companyId={} → règle ONG exonérée (IS 0%, art. 195)",
 companyId);
 return rule;
 }

 // 3. Zone franche (Code Fiscal art. 195) — IS 15%
 boolean isFreeZone = company != null
 && (company.isFreeZone()
 || company.getTaxExemptionStatus() == TaxExemptionStatus.FREE_ZONE);
 if (isFreeZone) {
 CorporateTaxRule rule = null;
 if (corporateTaxRuleRepository != null) {
 rule = corporateTaxRuleRepository
 .findByCountryCodeAndFreeZoneRateTrueAndActiveTrue(resolvedCountry)
 .orElse(null);
 }
 if (rule == null) {
 // Fallback défensif : règle ZF ad-hoc (DB non migrée V90)
 rule = new CorporateTaxRule();
 rule.setActive(true);
 rule.setEligibility(CorporateTaxEligibility.FREE_ZONE);
 rule.setCountryCode(resolvedCountry);
 rule.setStandardRate(new BigDecimal("15"));
 rule.setReducedRate(new BigDecimal("15"));
 rule.setReducedRateThreshold(null);
 rule.setFreeZoneRate(true);
 rule.setNgoExemptRate(false);
 } else if (rule.getEligibility() == null
 || rule.getEligibility() == CorporateTaxEligibility.UNKNOWN) {
 // Sécurité : forcer eligibility=FREE_ZONE (V76 l'avait créée avec UNKNOWN)
 rule.setEligibility(CorporateTaxEligibility.FREE_ZONE);
 }
 LOG.info("resolveCorporateTaxRule : companyId={} → règle Zone Franche (IS 15%, art. 195)",
 companyId);
 return rule;
 }

 // 4. Comportement historique : règle par entreprise ou défaut par pays
 if (corporateTaxRuleRepository != null) {
 CorporateTaxRule companyRule = corporateTaxRuleRepository
 .findByCompanyIdAndActiveTrue(companyId)
 .orElse(null);
 if (companyRule != null) {
 return companyRule;
 }
 }
 return defaultCorporateTaxRule(resolvedCountry);
 }

 // --- Échéancier des déclarations fiscales — audit mobile #7 ---

 /**
 * Génère le planning annuel des échéances fiscales françaises (audit mobile #7).
 *
 * <p>Échéances incluses pour l'année {@code year} :
 * <ul>
 * <li><b>TVA mensuelle</b> (régime normal) — dépôt + paiement avant le 19 du mois M+1
 * (12 échéances : 19 fév, 19 mar, ..., 19 jan N+1).</li>
 * <li><b>TVA trimestrielle</b> (alternative si TVA annuelle &lt; 4 000 €) — 4 échéances :
 * 19 avr (T1), 19 jul (T2), 19 oct (T3), 19 jan N+1 (T4).</li>
 * <li><b>IS acomptes</b> — 15 mars, 15 juin, 15 septembre, 15 décembre (art. 1668 CGI).</li>
 * <li><b>IS solde</b> — 15 mai N+1 (solde de l'IS de l'exercice clos).</li>
 * <li><b>DES mensuelle</b> (Déclaration d'Échanges de Services intra-UE B2B) — 10 du mois M+1.</li>
 * </ul>
 *
 * <p><b>Limitation v1</b> : ne tient pas compte des reports de weekend/jour férié
 * (art. A. 40 A LPF). Le tri est par date croissante. Le {@code vatRegime} est déterminé
 * depuis la première {@link TaxRule} active — par défaut "MENSUEL".
 *
 * @param companyId l'entreprise
 * @param year l'exercice (ex. 2026)
 * @return le planning complet
 */
 @Transactional(readOnly = true)
 public TaxDeclarationSchedule getDeclarationSchedule(UUID companyId, int year) {
 // Lot B route vers le calendrier DGI Haïti si la Company est en Haïti.
 // Sinon, fallback sur le calendrier français historique (CA3/DES/IS).
 String countryCode = resolveCompanyCountry(companyId);
 if (countryCode != null && "HT".equalsIgnoreCase(countryCode)
 && haitianTaxDeclarationSchedule != null) {
 LOG.info("Échéancier DGI Haïti pour company {} année {}", companyId, year);
 return haitianTaxDeclarationSchedule.build(companyId, year);
 }
 return getFrenchDeclarationSchedule(companyId, year);
 }

 /**
 * Génère le planning annuel des échéances fiscales françaises (audit mobile #7).
 *
 * <p>Méthode historique conservée pour compat arrière (Lot B ). Le routing par pays
 * est fait par {@link #getDeclarationSchedule(UUID, int)}.
 */
 @Transactional(readOnly = true)
 public TaxDeclarationSchedule getFrenchDeclarationSchedule(UUID companyId, int year) {
 // Déterminer le régime TVA depuis les règles fiscales actives de l'entreprise.
 // Pour l'instant, on regarde si une règle explicite "TRIMESTRIEL" existe — sinon MENSUEL.
 // Non implémenté : persister un vatRegime au niveau de l'entreprise (champ dédié).
 String vatRegime = "MENSUEL";
 List<TaxRule> rules = taxRuleRepository.findByCompanyIdOrCompanyIdIsNull(companyId);
 if (rules.stream().anyMatch(r -> r.getCode() != null && r.getCode().contains("TRIMESTRIEL"))) {
 vatRegime = "TRIMESTRIEL";
 }

 List<TaxDeclarationSchedule.DeclarationDeadline> deadlines = new ArrayList<>();

 // ── TVA : 12 échéances mensuelles OU 4 trimestrielles ──
 // Pour TVA mensuelle : la TVA du mois M est déclarée avant le 19 du mois M+1.
 // Donc pour l'année N, on a : 19/02/N (TVA janvier), ..., 19/01/N+1 (TVA décembre).
 if ("MENSUEL".equals(vatRegime)) {
 for (int m = 0; m < 12; m++) {
 // Mois concerné par la TVA : m (0-indexed). Échéance : 19 du mois suivant.
 int declaredMonthIdx = m; // 0..11 (janv..déc)
 int deadlineMonthIdx = (declaredMonthIdx + 1) % 12; // 1..11, 0 (=janv N+1)
 int deadlineYear = (declaredMonthIdx + 1) > 11 ? year + 1 : year;
 LocalDate due = LocalDate.of(deadlineYear, deadlineMonthIdx + 1, 19);
 String monthLabel = java.time.Month.of(declaredMonthIdx + 1)
 .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH);
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 due, "VAT_MONTHLY",
 "TVA " + capitalize(monthLabel) + " " + year));
 }
 } else {
 // TVA trimestrielle : 4 échéances (19 avr, 19 jul, 19 oct, 19 jan N+1)
 int[] deadlineMonths = {4, 7, 10, 1}; // avril, juillet, octobre, janvier N+1
 int[] deadlineYears = {year, year, year, year + 1};
 String[] labels = {
 "TVA T1 " + year, "TVA T2 " + year, "TVA T3 " + year, "TVA T4 " + year
 };
 for (int i = 0; i < 4; i++) {
 LocalDate due = LocalDate.of(deadlineYears[i], deadlineMonths[i], 19);
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 due, "VAT_QUARTERLY", labels[i]));
 }
 }

 // ── IS acomptes — 15 mars, 15 juin, 15 sept, 15 déc (art. 1668 CGI) ──
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 LocalDate.of(year, 3, 15), "CORPORATE_TAX_INSTALLMENT",
 "Acompte IS 1er trimestre " + year));
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 LocalDate.of(year, 6, 15), "CORPORATE_TAX_INSTALLMENT",
 "Acompte IS 2e trimestre " + year));
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 LocalDate.of(year, 9, 15), "CORPORATE_TAX_INSTALLMENT",
 "Acompte IS 3e trimestre " + year));
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 LocalDate.of(year, 12, 15), "CORPORATE_TAX_INSTALLMENT",
 "Acompte IS 4e trimestre " + year));

 // ── IS solde — 15 mai N+1 (solde de l'IS N) ──
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 LocalDate.of(year + 1, 5, 15), "CORPORATE_TAX_BALANCE",
 "Solde IS exercice " + year));

 // ── DES mensuelle — 10 du mois M+1 (12 échéances) ──
 for (int m = 0; m < 12; m++) {
 int deadlineMonthIdx = (m + 1) % 12;
 int deadlineYear = (m + 1) > 11 ? year + 1 : year;
 LocalDate due = LocalDate.of(deadlineYear, deadlineMonthIdx + 1, 10);
 String monthLabel = java.time.Month.of(m + 1)
 .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH);
 deadlines.add(new TaxDeclarationSchedule.DeclarationDeadline(
 due, "DES_MONTHLY",
 "DES " + capitalize(monthLabel) + " " + year));
 }

 // Trier par date croissante
 deadlines.sort(java.util.Comparator
 .comparing(TaxDeclarationSchedule.DeclarationDeadline::date));

 LOG.info("Échéancier fiscal {} année {} : {} échéances (régime TVA {})",
 companyId, year, deadlines.size(), vatRegime);
 return new TaxDeclarationSchedule(companyId, year, vatRegime, deadlines);
 }

 private static String capitalize(String s) {
 if (s == null || s.isEmpty()) return s;
 return Character.toUpperCase(s.charAt(0)) + s.substring(1);
 }

 // ════════════════════════════════════════════════════════════════════════
 // R-F-validation v6-2 — Déclaration RS sur ventes (Code Fiscal art. 156-1 Haïti)
 // ════════════════════════════════════════════════════════════════════════

 /**
 * R-F-validation v6-2 — Génère la déclaration mensuelle de retenue à la source (RS) sur
 * ventes pour une période (Code Fiscal art. 156-1 Haïti).
 *
 * <p>Agrège les factures de ventes (STANDARD + CREDIT_NOTE) portant une RS
 * ({@code SalesInvoice.withholdingAmount > 0}) sur la période, par taux de RS. Les avoirs
 * (CREDIT_NOTE) sont traités en négatif (ils inversent la RS de la facture originale).
 *
 * <p>Le {@link TaxDeclaration} retourné contient :
 * <ul>
 * <li>{@code collectedLines} — une ligne par taux de RS (agrégation par
 * {@code withholdingRate}). Pour chaque ligne : {@code taxCode}="RS-{rate}%",
 * {@code taxLabel}=descriptif (ex : "RS 2% — prestations locales"),
 * {@code rate}=taux, {@code taxableBase}=somme des subtotals (HT) des factures à ce
 * taux (négative si avoirs > factures), {@code taxAmount}=somme des
 * {@code withholdingAmount} (négative si avoirs > factures).</li>
 * <li>{@code deductibleLines} — vide (la RS n'est pas déductible, c'est un impôt à
 * reverser pour le compte des clients).</li>
 * <li>{@code totalTaxCollected} — total RS retenue par les clients sur la période
 * (net STANDARD − CREDIT_NOTE).</li>
 * <li>{@code totalTaxDeductible} — 0 (la RS n'est pas déductible).</li>
 * <li>{@code taxCreditCarriedForward} — crédit RS reporté de la période précédente
 * (lu depuis {@code tax_credit_carried_forward} où {@code tax_type = 'WITHHOLDING'}).
 * Vaut 0 si aucune déclaration précédente ou si le crédit précédent a été remboursé
 * ({@code carried_to_next = false}).</li>
 * <li>{@code taxDue} — total RS à reverser à la DGI pour le compte des clients
 * (égale à {@code max(0, totalTaxCollected - taxCreditCarriedForward)}).</li>
 * <li>{@code taxCreditToCarryForward} — part négative (si avoirs > factures sur la période,
 * ou si le crédit reporté de la période précédente fait basculer le solde en négatif)
 * à reporter sur la déclaration RS du mois suivant. Persistée en fin de méthode dans
 * {@code tax_credit_carried_forward} ({@code tax_type = 'WITHHOLDING'}).</li>
 * </ul>
 *
 * <p><b>Période</b> : la déclaration est mensuelle, échéance le 15 du mois M+1. Les
 * factures sont agrégées par {@code issue_date} (date d'émission, pas date de paiement —
 * la RS est exigible à l'émission de la facture).
 *
 * <p><b>Persistance du crédit</b> (depuis la correction du bug "Non implémenté : ") : à la fin
 * de chaque appel, si {@code taxCreditToCarryForward > 0}, le montant est persisté dans
 * {@code tax_credit_carried_forward} pour la période courante (clé
 * {@code (companyId, 'WITHHOLDING', year, month)}). À l'appel suivant pour la période
 * M+1, ce crédit est lu pour réduire la RS due.
 *
 * @param companyId identifiant de l'entreprise
 * @param from date de début de période (inclusive)
 * @param to date de fin de période (inclusive)
 * @return le {@link TaxDeclaration} agrégé par taux de RS
 */
 @Transactional
 public TaxDeclaration getWithholdingDeclaration(UUID companyId, LocalDate from, LocalDate to) {
 // Charger les factures avec RS sur la période (statuses ISSUED + PARTIALLY_PAID + PAID).
 // On exclut les DRAFT (pas encore émises) et VOID (annulées).
 List<jo.accountant.invoicing.entity.InvoiceStatus> salesStatuses = List.of(
 jo.accountant.invoicing.entity.InvoiceStatus.ISSUED,
 jo.accountant.invoicing.entity.InvoiceStatus.PARTIALLY_PAID,
 jo.accountant.invoicing.entity.InvoiceStatus.PAID);
 List<SalesInvoice> invoicesWithRs = invoiceRepository
 .findWithholdingInvoicesInPeriod(companyId, salesStatuses, from, to);

 // Agréger par taux de RS — les avoirs (CREDIT_NOTE) sont en négatif.
 // Map<withholdingRate, AggregatedRs>
 Map<BigDecimal, AggregatedRs> byRate = new java.util.TreeMap<>();
 BigDecimal totalWithholding = BigDecimal.ZERO;
 BigDecimal totalBase = BigDecimal.ZERO;

 for (SalesInvoice inv : invoicesWithRs) {
 BigDecimal rate = inv.getWithholdingRate() != null
 ? inv.getWithholdingRate() : BigDecimal.ZERO;
 if (rate.compareTo(BigDecimal.ZERO) == 0) continue; // taux 0% — ignoré
 BigDecimal sign = (inv.getType() == jo.accountant.invoicing.entity.InvoiceType.CREDIT_NOTE)
 ? new BigDecimal("-1") : BigDecimal.ONE;
 BigDecimal base = inv.getSubtotal() != null ? inv.getSubtotal() : BigDecimal.ZERO;
 BigDecimal amount = inv.getWithholdingAmount() != null
 ? inv.getWithholdingAmount() : BigDecimal.ZERO;
 BigDecimal signedBase = base.multiply(sign);
 BigDecimal signedAmount = amount.multiply(sign);

 AggregatedRs agg = byRate.computeIfAbsent(rate, AggregatedRs::new);
 agg.base = agg.base.add(signedBase);
 agg.amount = agg.amount.add(signedAmount);

 totalWithholding = totalWithholding.add(signedAmount);
 totalBase = totalBase.add(signedBase);
 }

 // Construire les lignes de déclaration (une par taux, trié par taux croissant via TreeMap)
 List<TaxDeclaration.TaxLine> collectedLines = new ArrayList<>();
 for (AggregatedRs agg : byRate.values()) {
 String code = "RS-" + agg.rate.setScale(2, java.math.RoundingMode.HALF_UP) + "%";
 String label = "RS " + agg.rate.setScale(2, java.math.RoundingMode.HALF_UP)
 + "% — ventes (art. 156-1 Code Fiscal)";
 collectedLines.add(new TaxDeclaration.TaxLine(
 code, label, agg.rate, agg.base, agg.amount));
 }

 // La RS est un impôt à reverser (pas déductible). totalTaxDeductible = 0.
 BigDecimal totalTaxDeductible = BigDecimal.ZERO;

 // ── Crédit RS reporté de la période précédente (lit tax_credit_carried_forward) ──
 int periodYear = from.getYear();
 int periodMonth = from.getMonthValue();
 java.time.LocalDate prevPeriodDate = from.minusMonths(1);
 int prevYear = prevPeriodDate.getYear();
 int prevMonth = prevPeriodDate.getMonthValue();

 BigDecimal taxCreditCarriedForward = readCarriedForwardCredit(
 companyId, prevYear, prevMonth, jo.accountant.tax.entity.TaxType.WITHHOLDING);

 // taxDue = max(0, totalWithholding - taxCreditCarriedForward)
 // - si avoirs > factures sur la période : totalWithholding < 0 → crédit à reporter
 // - si crédit reporté de la période précédente > totalWithholding : solde négatif → crédit à reporter
 BigDecimal netTax = totalWithholding.subtract(taxCreditCarriedForward);
 BigDecimal taxDue;
 BigDecimal taxCreditToCarryForward;
 if (netTax.compareTo(BigDecimal.ZERO) >= 0) {
 taxDue = netTax;
 taxCreditToCarryForward = BigDecimal.ZERO;
 } else {
 taxDue = BigDecimal.ZERO;
 taxCreditToCarryForward = netTax.negate();
 }

 // ── Persistance du crédit à reporter pour la période courante ──
 // Idempotent : si la même déclaration est recalculée, le crédit existant est mis à jour
 // (uc_tax_credit_period garantit l'unicité par (companyId, tax_type, year, month)).
 if (taxCreditToCarryForward.compareTo(BigDecimal.ZERO) > 0) {
 persistCarriedForwardCredit(companyId, periodYear, periodMonth,
 taxCreditToCarryForward, jo.accountant.tax.entity.TaxType.WITHHOLDING);
 }

 LOG.info("Déclaration RS {} [{} à {}] : {} factures avec RS, {} taux, total RS = {} HTG, " +
 "crédit reporté (période précédente) = {}, dû = {}, crédit à reporter = {}",
 companyId, from, to, invoicesWithRs.size(), byRate.size(),
 totalWithholding, taxCreditCarriedForward, taxDue, taxCreditToCarryForward);

 return new TaxDeclaration(companyId, from, to, collectedLines, List.of(),
 totalWithholding, totalTaxDeductible, taxCreditCarriedForward, taxDue, taxCreditToCarryForward);
 }

 /**
 * Helper interne pour agréger la RS par taux (R-F-validation v6-2).
 */
 private static final class AggregatedRs {
 final BigDecimal rate;
 BigDecimal base = BigDecimal.ZERO;
 BigDecimal amount = BigDecimal.ZERO;

 AggregatedRs(BigDecimal rate) { this.rate = rate; }
 }
}

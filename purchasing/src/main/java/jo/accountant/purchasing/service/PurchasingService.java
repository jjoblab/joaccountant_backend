package jo.accountant.purchasing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.currency.CurrencyRoundingService;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tax.WithholdingBracketType;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.purchasing.dto.CreatePurchaseInvoiceRequest;
import jo.accountant.purchasing.dto.PurchaseInvoiceResponse;
import jo.accountant.purchasing.dto.RecordPurchasePaymentRequest;
import jo.accountant.purchasing.entity.PurchaseInvoice;
import jo.accountant.purchasing.entity.PurchaseInvoiceLine;
import jo.accountant.purchasing.entity.PurchaseInvoiceStatus;
import jo.accountant.purchasing.entity.PurchaseInvoiceType;
import jo.accountant.purchasing.repository.PurchaseInvoiceLineRepository;
import jo.accountant.purchasing.repository.PurchaseInvoiceRepository;
import jo.accountant.core.port.WithholdingRulePort;
import jo.accountant.core.port.WithholdingRulePort.WithholdingRuleSnapshot;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des achats (module :purchasing).
 *
 * <p>Symétrique de {@code InvoicingService} pour le côté fournisseur. Cycle de vie :
 * DRAFT (création, lignes éditables) → {@code receive()} (attribue le numéro interne via
 * document-numbering, génère l'écriture — voir {@link #generatePurchaseEntry}) →
 * paiements partiels/complets via {@code recordPayment()} → PAID. {@code void()} disponible
 * tant que non payée.
 *
 * <p><b>Résolution des comptes référentiel-agnostique</b> (calquée sur
 * {@code InvoicingService.generateInvoiceEntry} — audit B4) :
 * <ul>
 * <li><b>Compte de charges</b> : si la ligne précise {@code expenseAccountId}, on l'utilise
 * (après validation qu'il est bien de {@code ReportingClass.CHARGES}). Sinon, on cherche
 * un compte {@code CHARGES} marqué {@code taxMappingCode = "PURCHASES"}, à défaut un
 * compte {@code CHARGES} actif quelconque, à défaut (rétro-compatibilité SYSCOHADA/PCG)
 * les codes "601000"/"601".</li>
 * <li><b>Compte de TVA déductible</b> : on cherche un compte {@code ACTIF} marqué
 * {@code taxMappingCode = "VAT_DEDUCTIBLE"}, à défaut (SYSCOHADA) les codes
 * "445000"/"445".</li>
 * <li><b>Compte fournisseur</b> : compte dédié du tiers (ou collectif si pas de dédié),
 * symétrique au compte client côté :invoicing.</li>
 * </ul>
 *
 * <p><b>Code journal</b> : "AC" (achats). Le journal doit exister — sinon
 * {@code 422 JOURNAL_AC_NOT_FOUND}. Pas de fallback (contrairement à la résolution des
 * comptes, le code journal est porté par l'utilisateur qui doit le créer explicitement).
 
 *
 * @author jo@Dev


*/
@Service
public class PurchasingService {

 private static final Logger LOG = LoggerFactory.getLogger(PurchasingService.class);
 private static final BigDecimal HUNDRED = new BigDecimal("100");
 /**
 * Hard cap pour listInvoices — empêche l'OOM sur entreprises matures#5).
 * Sans ce cap, une entreprise avec 5 ans d'historique d'achats pouvait saturer la heap.
 * Les clients qui ont besoin de plus de 200 factures doivent filtrer par exercice fiscal
 * via listInvoices(companyId, fiscalYearId).
 */
 private static final int PURCHASE_INVOICE_LIST_HARD_CAP = 200;

 private final PurchaseInvoiceRepository invoiceRepository;
 private final PurchaseInvoiceLineRepository lineRepository;
 private final ThirdPartyRepository thirdPartyRepository;
 private final AccountRepository accountRepository;
 private final JournalRepository journalRepository;
 private final DocumentNumberingService documentNumberingService;
 private final AccountingEngineService accountingEngineService;
 private final CurrencyRoundingService roundingService;
 private final WithholdingRulePort withholdingRulePort; //port pour éviter cycle :purchasing ↔ :tax
 // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
 private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;
 // barème progressif : Jackson pour parser bracketsJson.
 private final ObjectMapper objectMapper;

 public PurchasingService(PurchaseInvoiceRepository invoiceRepository,
 PurchaseInvoiceLineRepository lineRepository,
 ThirdPartyRepository thirdPartyRepository,
 AccountRepository accountRepository,
 JournalRepository journalRepository,
 DocumentNumberingService documentNumberingService,
 AccountingEngineService accountingEngineService,
 CurrencyRoundingService roundingService,
 WithholdingRulePort withholdingRulePort,
 jo.accountant.chartofaccounts.service.AccountResolver accountResolver,
 ObjectMapper objectMapper) {
 this.invoiceRepository = invoiceRepository;
 this.lineRepository = lineRepository;
 this.thirdPartyRepository = thirdPartyRepository;
 this.accountRepository = accountRepository;
 this.journalRepository = journalRepository;
 this.documentNumberingService = documentNumberingService;
 this.accountingEngineService = accountingEngineService;
 this.roundingService = roundingService;
 this.withholdingRulePort = withholdingRulePort;
 this.accountResolver = accountResolver;
 this.objectMapper = objectMapper;
 }

 // --- Création ---

 @Transactional
 public PurchaseInvoiceResponse createPurchaseInvoice(UUID companyId, CreatePurchaseInvoiceRequest req) {
 ThirdParty tp = loadSupplier(companyId, req.thirdPartyId());

 PurchaseInvoice invoice = new PurchaseInvoice();
 invoice.setCompanyId(companyId);
 invoice.setThirdPartyId(tp.getId());
 invoice.setType(req.type() != null ? req.type() : PurchaseInvoiceType.STANDARD);
 invoice.setStatus(PurchaseInvoiceStatus.DRAFT);
 invoice.setSupplierReference(req.supplierReference());
 invoice.setCurrency(req.currency() != null ? req.currency().toUpperCase() : "HTG");
 invoice.setIssueDate(req.issueDate() != null ? req.issueDate() : LocalDate.now());
 invoice.setDueDate(req.dueDate() != null ? req.dueDate()
 : invoice.getIssueDate().plusDays(30));
 invoice.setPaidAmount(BigDecimal.ZERO);
 invoice.setSubtotal(BigDecimal.ZERO);
 invoice.setTaxAmount(BigDecimal.ZERO);
 invoice.setTotalAmount(BigDecimal.ZERO);
 PurchaseInvoice savedInvoice = invoiceRepository.save(invoice);

 BigDecimal subtotal = BigDecimal.ZERO;
 BigDecimal taxAmount = BigDecimal.ZERO;
 String currencyCode = savedInvoice.getCurrency();
 for (var lineDto : req.lines()) {
 BigDecimal lineHt = roundingService.round(currencyCode,
 lineDto.quantity().multiply(lineDto.unitPrice()));
 BigDecimal lineTax = roundingService.round(currencyCode,
 lineHt.multiply(lineDto.taxRate()).divide(HUNDRED, 6, RoundingMode.HALF_UP));

 subtotal = subtotal.add(lineHt);
 taxAmount = taxAmount.add(lineTax);

 PurchaseInvoiceLine line = new PurchaseInvoiceLine();
 line.setCompanyId(companyId);
 line.setInvoiceId(savedInvoice.getId());
 line.setDescription(lineDto.description());
 line.setQuantity(lineDto.quantity());
 line.setUnitPrice(lineDto.unitPrice());
 line.setTaxRate(lineDto.taxRate());
 line.setExpenseAccountId(lineDto.expenseAccountId());
 line.setLineTotalHt(lineHt);
 line.setLineTotalTax(lineTax);
 lineRepository.save(line);
 }

 savedInvoice.setSubtotal(subtotal);
 savedInvoice.setTaxAmount(taxAmount);
 savedInvoice.setTotalAmount(subtotal.add(taxAmount));
 invoiceRepository.save(savedInvoice);

 LOG.info("Facture d'achat créée : id={} tiers={} supplierRef={}",
 savedInvoice.getId(), tp.getName(), req.supplierReference());
 return loadResponse(companyId, savedInvoice.getId());
 }

 // --- Réception (DRAFT → RECEIVED) ---

 @Transactional
 public PurchaseInvoiceResponse receive(UUID companyId, UUID invoiceId) {
 PurchaseInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() != PurchaseInvoiceStatus.DRAFT) {
 throw new ConflictException("PURCHASE_INVOICE_NOT_DRAFT",
 "Seules les factures DRAFT peuvent être reçues. Statut : " + invoice.getStatus());
 }

 IssuedNumber issued = documentNumberingService.nextNumber(
 companyId, DocumentType.PURCHASE_INVOICE, "AC",
 invoice.getIssueDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
 invoice.setInvoiceNumber(issued.number());
 invoice.setStatus(PurchaseInvoiceStatus.RECEIVED);

 generatePurchaseEntry(companyId, invoice);
 invoiceRepository.save(invoice);

 LOG.info("Facture d'achat reçue : id={} number={} total={}",
 invoice.getId(), invoice.getInvoiceNumber(), invoice.getTotalAmount());
 return loadResponse(companyId, invoice.getId());
 }

 /**
 * Génère l'écriture comptable de réception de facture d'achat.
 *
 * <p>Débit : Achats (CHARGES) — par ligne, sur le compte de charge de la ligne
 * (ou le compte de charge générique marqué PURCHASES si non précisé).
 * Crédit : TVA déductible (ACTIF) — total TVA si > 0.
 * Crédit : Fournisseur (compte dédié du tiers) — <b>net TTC</b> (TTC − retenue à la source).
 * Crédit : État — retenue à la source (compte PASSIF marqué taxMappingCode="WITHHOLDING_TAX"
 * ou fallback "442000") si une WithholdingRule applicable au SUPPLIER est configurée.
 *
 * <p><b>la version précédente n'appliquait JAMAIS les
 * WithholdingRule côté fournisseurs malgré la javadoc de {@link WithholdingRule} qui le
 * promettait explicitement. L'écriture était strictement D Charges / C TVA déductible /
 * C Fournisseur TTC. Sur les honoraires (FR art. 182 B CGI — 10% hors UE), la retenue est
 * obligatoire — sans application, l'entreprise paie le fournisseur TTC au lieu de HT net
 * de retenue → redressement certain.
 *
 * <p>Le fix calcule la retenue sur la base HT (conforme à la pratique OHADA et française) :
 * <pre>
 * withholdingAmount = subtotal HT × rule.rate / 100
 * supplierCredit = total TTC − withholdingAmount
 * </pre>
 * Le compte 442 "État — retenues à la source" est crédité du montant de la retenue. Le
 * fournisseur est crédité du net à payer. La TVA déductible reste inchangée (calculée sur
 * le TTC, pas affectée par la retenue).
 *
 * <p>Plusieurs WithholdingRule peuvent être actives simultanément (ex : IR + TCS en Haïti).
 * On applique la somme des taux. Si le total des retenues dépasse 100% du HT (situation
 * anormale), on lève une ValidationException pour éviter une écriture négative.
 *
 * <p>Pour un DEBIT_NOTE (avoir fournisseur), inverser débit/crédit — comme pour les
 * CREDIT_NOTE côté :invoicing.
 */
 private void generatePurchaseEntry(UUID companyId, PurchaseInvoice invoice) {
 ThirdParty tp = thirdPartyRepository.findById(invoice.getThirdPartyId())
 .orElseThrow(() -> new ValidationException("THIRD_PARTY_NOT_FOUND",
 "Tiers introuvable : " + invoice.getThirdPartyId()));
 //— defense-in-depth
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", invoice.getThirdPartyId().toString());
 }

 UUID supplierAccountId = tp.getDedicatedAccountId() != null
 ? tp.getDedicatedAccountId() : tp.getCollectiveAccountId();
 Account supplierAccount = accountRepository.findById(supplierAccountId)
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte fournisseur introuvable"));
 //— defense-in-depth
 if (!supplierAccount.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", supplierAccountId.toString());
 }

 List<PurchaseInvoiceLine> lines = lineRepository
 .findByInvoiceIdOrderByCreatedAt(invoice.getId());

 // Map<accountIdCode, accumulated amount> pour grouper les lignes par compte de charge.
 Map<String, BigDecimal> chargesByAccount = new HashMap<>();
 for (PurchaseInvoiceLine line : lines) {
 Account chargeAccount = resolveChargeAccount(companyId, line.getExpenseAccountId());
 chargesByAccount.merge(chargeAccount.getCode(), line.getLineTotalHt(), BigDecimal::add);
 }

 Account vatAccount = null;
 if (invoice.getTaxAmount().compareTo(BigDecimal.ZERO) > 0) {
 // Compte de TVA déductible — résolution référentiel-agnostique via AccountResolver (audit #3)
 vatAccount = accountResolver.resolveOrThrow(
 companyId, ReportingClass.ACTIF, "VAT_DEDUCTIBLE",
 "VAT_DEDUCTIBLE_ACCOUNT_NOT_FOUND",
 "Aucun compte de TVA déductible trouvé. Configurer un compte ACTIF " +
 "marqué taxMappingCode=\"VAT_DEDUCTIBLE\" dans le plan comptable.",
 "445000", "445");
 }

 // ──calcul de la retenue à la source fournisseur ──
 // On charge les WithholdingRule actives pour l'entreprise, on filtre côté Java celles
 // dont applicableThirdPartyTypes contient "SUPPLIER" (le JSONB est stocké en string,
 // on fait un contains() plutôt qu'une requête JSONB pour rester portable).
 BigDecimal withholdingAmount = calculateSupplierWithholding(companyId, invoice.getSubtotal());

 Account withholdingAccount = null;
 if (withholdingAmount.compareTo(BigDecimal.ZERO) > 0) {
 // Compte de retenue à la source — résolution référentiel-agnostique via AccountResolver (audit #3)
 withholdingAccount = accountResolver.resolveOrThrow(
 companyId, ReportingClass.PASSIF, "WITHHOLDING_TAX",
 "WITHHOLDING_ACCOUNT_NOT_FOUND",
 "Une retenue à la source est applicable (" + withholdingAmount + " " +
 invoice.getCurrency() + ") mais aucun compte de passif marqué " +
 "taxMappingCode=\"WITHHOLDING_TAX\" (ou code 442000) n'est configuré. " +
 "Créer un compte 442 « État — retenues à la source » dans le plan comptable.",
 "442000", "442");
 }

 // Montant net à payer au fournisseur = TTC − retenue à la source
 BigDecimal supplierCredit = invoice.getTotalAmount().subtract(withholdingAmount);

 // V8.2getOrCreateJournal retourne le journal existant ou le crée avec
 // le code/label par défaut du type (jamais d'exception pour les types standards).
 String journalCode = accountingEngineService.getOrCreateJournal(companyId,
 jo.accountant.accountingengine.entity.JournalType.ACHATS).getCode();

 List<LineDto> entryLines = new ArrayList<>();
 for (var entry : chargesByAccount.entrySet()) {
 entryLines.add(new LineDto(entry.getKey(), null,
 entry.getValue(), null, "Achats — Facture " + invoice.getInvoiceNumber(),
 List.of()));
 }
 if (invoice.getTaxAmount().compareTo(BigDecimal.ZERO) > 0) {
 entryLines.add(new LineDto(vatAccount.getCode(), null,
 invoice.getTaxAmount(), null,
 "TVA déductible — Facture " + invoice.getInvoiceNumber(), List.of()));
 }
 // Crédit État — retenue à la source (si applicable)
 if (withholdingAmount.compareTo(BigDecimal.ZERO) > 0) {
 entryLines.add(new LineDto(withholdingAccount.getCode(), null,
 null, withholdingAmount,
 "Retenue à la source — Facture " + invoice.getInvoiceNumber(), List.of()));
 }
 // Crédit Fournisseur (net à payer = TTC − retenue)
 entryLines.add(new LineDto(supplierAccount.getCode(), tp.getId(),
 null, supplierCredit,
 "Fournisseur — Facture " + invoice.getInvoiceNumber()
 + (withholdingAmount.compareTo(BigDecimal.ZERO) > 0
 ? " (net après retenue " + withholdingAmount + ")" : ""),
 List.of()));

 if (invoice.getType() == PurchaseInvoiceType.DEBIT_NOTE) {
 entryLines = entryLines.stream().map(l -> new LineDto(
 l.accountCode(), l.thirdPartyId(),
 l.credit(), l.debit(),
 l.description(), l.analyticalTags()
 )).toList();
 }

 CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
 journalCode, invoice.getIssueDate(),
 "Facture achat " + invoice.getInvoiceNumber() + " — " + tp.getName()
 + (withholdingAmount.compareTo(BigDecimal.ZERO) > 0
 ? " (avec retenue à la source)" : ""),
 entryLines, JournalEntrySourceModule.PURCHASING);

 JournalEntryResponse entry = accountingEngineService.createJournalEntry(
 companyId, "purchasing-" + invoice.getId(), entryReq);
 JournalEntryResponse posted = accountingEngineService.postJournalEntry(
 companyId, entry.id(), List.of());

 invoice.setJournalEntryId(posted.id());

 if (withholdingAmount.compareTo(BigDecimal.ZERO) > 0) {
 LOG.info("Retenue à la source appliquée sur facture achat {} : HT={}, retenue={} ({}%), "
 + "net fournisseur={} (TTC={})", invoice.getInvoiceNumber(), invoice.getSubtotal(),
 withholdingAmount, invoice.getCurrency(), supplierCredit, invoice.getTotalAmount());
 }
 }

 /**
 * Calcule le montant total de retenue à la source applicable à une facture fournisseur.
 *
 * <p><b></b> : charge les {@link WithholdingRule} actives pour
 * l'entreprise, filtre celles dont {@code applicableThirdPartyTypes} (JSONB stocké en string)
 * contient {@code "SUPPLIER"}, et applique la somme des taux sur la base HT.
 *
 * <p>Logique (FLAT — comportement historique) :
 * <pre>
 * totalRate = Σ rule.rate pour rule in activeRules if rule.applicableThirdPartyTypes contains "SUPPLIER"
 * withholdingAmount = subtotal HT × totalRate / 100
 * </pre>
 *
 * <p>Si {@code totalRate > 100}, lève {@link ValidationException} (configuration anormale —
 * la retenue ne peut pas dépasser le montant HT).
 *
 * <p><b>barème progressif (PAS FR)</b> : si une règle a
 * {@code bracketType = PROGRESSIVE}, le calcul est par tranches successives :
 * <pre>
 * for each bracket (sorted by threshold asc):
 * slice = max(0, min(base, nextThreshold) - currentThreshold)
 * withholding += slice × bracket.rate / 100
 * </pre>
 * Exemple pour un barème {@code [{0,0%},{50000,10%},{100000,15%}]} et une base de 75000 :
 * <pre>
 * (min(75000, 50000) - 0) × 0% + (min(75000, 100000) - 50000) × 10% + 0 (dernière tranche)
 * = 50000 × 0% + 25000 × 10% + 0 = 2500
 * </pre>
 * Les règles FLAT et PROGRESSIVE peuvent coexister ; leurs montants s'additionnent.
 *
 * @return montant de la retenue, {@code BigDecimal.ZERO} si aucune règle applicable
 */
 private BigDecimal calculateSupplierWithholding(UUID companyId, BigDecimal subtotalHt) {
 if (subtotalHt == null || subtotalHt.compareTo(BigDecimal.ZERO) <= 0) {
 return BigDecimal.ZERO;
 }
 //utiliser le port WithholdingRulePort (défini dans :core,
 // implémenté par :tax) pour éviter la dépendance circulaire :purchasing ↔ :tax.
 List<WithholdingRuleSnapshot> supplierRules = withholdingRulePort
 .findActiveRulesForThirdPartyType(companyId, "SUPPLIER");
 if (supplierRules.isEmpty()) {
 return BigDecimal.ZERO;
 }

 BigDecimal totalAmount = BigDecimal.ZERO;
 BigDecimal totalFlatRate = BigDecimal.ZERO;
 int applicableFlatRules = 0;
 for (WithholdingRuleSnapshot rule : supplierRules) {
 WithholdingBracketType bracketType = rule.bracketType() != null
 ? rule.bracketType() : WithholdingBracketType.FLAT;
 if (bracketType == WithholdingBracketType.PROGRESSIVE) {
 // calcul par tranches successives.
 totalAmount = totalAmount.add(
 calculateProgressiveWithholding(subtotalHt, rule.bracketsJson(), rule.code()));
 } else {
 // Comportement historique (FLAT) — on somme les taux.
 totalFlatRate = totalFlatRate.add(rule.rate());
 applicableFlatRules++;
 }
 }
 // Validation : la somme des taux FLAT ne doit pas dépasser 100% (configuration anormale).
 // Les règles PROGRESSIVE sont exclues de ce check car leurs taux s'appliquent par tranche
 // (un taux de 15% sur la dernière tranche ne signifie pas 15% du total).
 if (applicableFlatRules > 0 && totalFlatRate.compareTo(HUNDRED) > 0) {
 throw new ValidationException("WITHHOLDING_RATE_EXCEEDS_100",
 "Le total des taux de retenue à la source FLAT applicables aux fournisseurs ("
 + totalFlatRate + "%) dépasse 100% du HT — configuration anormale. Vérifier les "
 + applicableFlatRules + " règle(s) FLAT active(s) dans le module tax.");
 }
 if (totalFlatRate.compareTo(BigDecimal.ZERO) > 0) {
 totalAmount = totalAmount.add(
 subtotalHt.multiply(totalFlatRate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
 }
 // Defense-in-depth : la retenue totale (FLAT + PROGRESSIVE) ne peut pas dépasser la base HT.
 // Si cela se produit, c'est une configuration anormale (ex: barème progressif mal défini).
 if (totalAmount.compareTo(subtotalHt) > 0) {
 throw new ValidationException("WITHHOLDING_EXCEEDS_BASE",
 "Le total de retenue à la source (" + totalAmount + ") dépasse la base HT ("
 + subtotalHt + ") — configuration anormale (mix FLAT + PROGRESSIVE ou barème " +
 "progressif incorrect). Vérifier les règles de retenue actives dans le module tax.");
 }
 return totalAmount;
 }

 /**
 * Calcule la retenue progressive par tranches — .
 *
 * <p>Parse {@code bracketsJson} au format {@code [{"threshold":0,"rate":0},...]} et applique
 * le barème par tranches successives. Chaque tranche {@code i} couvre la part de la base
 * comprise entre {@code brackets[i].threshold} (inclus) et {@code brackets[i+1].threshold}
 * (exclus) — la dernière tranche est ouverte (pas de plafond).
 *
 * <p>Formule : pour chaque tranche,
 * {@code slice = max(0, min(base, nextThreshold) - currentThreshold)} puis
 * {@code withholding += slice × rate / 100}.
 *
 * @param base montant HT sur lequel calculer la retenue
 * @param bracketsJson JSON string du barème (null/vide → 0)
 * @param ruleCode code de la règle (pour le message d'erreur en cas de JSON invalide)
 * @return montant de la retenue progressive, à l'échelle 2 (HALF_UP)
 */
 private BigDecimal calculateProgressiveWithholding(BigDecimal base, String bracketsJson,
 String ruleCode) {
 if (bracketsJson == null || bracketsJson.isBlank()) {
 return BigDecimal.ZERO;
 }
 List<Bracket> brackets;
 try {
 brackets = objectMapper.readValue(bracketsJson, new TypeReference<List<Bracket>>() {});
 } catch (Exception e) {
 throw new ValidationException("WITHHOLDING_BRACKETS_INVALID_JSON",
 "Échec de parsing du bracketsJson pour la règle '" + ruleCode + "' : "
 + e.getMessage() + ". Format attendu : [{\"threshold\":0,\"rate\":0},"
 + "{\"threshold\":50000,\"rate\":10},{\"threshold\":100000,\"rate\":15}].");
 }
 if (brackets.isEmpty()) {
 return BigDecimal.ZERO;
 }
 // Trier par threshold croissant pour garantir le calcul correct des tranches.
 brackets.sort(Comparator.comparing(Bracket::threshold,
 Comparator.nullsFirst(Comparator.naturalOrder())));
 BigDecimal total = BigDecimal.ZERO;
 for (int i = 0; i < brackets.size(); i++) {
 Bracket current = brackets.get(i);
 BigDecimal lower = current.threshold() != null ? current.threshold() : BigDecimal.ZERO;
 BigDecimal rate = current.rate() != null ? current.rate() : BigDecimal.ZERO;
 // Un taux nul ou négatif ne contribue pas à la retenue (sauter pour perf).
 if (rate.compareTo(BigDecimal.ZERO) <= 0) continue;
 // Si la base est inférieure ou égale au seuil inférieur, cette tranche (et les
 // suivantes, puisqu'elles ont un seuil supérieur) ne contribue pas.
 if (base.compareTo(lower) <= 0) break;
 BigDecimal upper = (i + 1 < brackets.size() && brackets.get(i + 1).threshold() != null)
 ? brackets.get(i + 1).threshold() : null;
 BigDecimal slice = (upper != null)
 ? base.min(upper).subtract(lower)
 : base.subtract(lower);
 if (slice.compareTo(BigDecimal.ZERO) <= 0) continue;
 total = total.add(slice.multiply(rate).divide(HUNDRED, 4, RoundingMode.HALF_UP));
 }
 return total.setScale(2, RoundingMode.HALF_UP);
 }

 /** DTO interne pour le parsing Jackson du bracketsJson — . */
 private static final class Bracket {
 private BigDecimal threshold;
 private BigDecimal rate;
 public BigDecimal threshold() { return threshold; }
 public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
 public BigDecimal rate() { return rate; }
 public void setRate(BigDecimal rate) { this.rate = rate; }
 }

 // Note : la méthode isApplicableToSupplier a été supprimée — le filtrage par
 // applicableThirdPartyTypes est désormais fait côté :tax par WithholdingRulePortAdapter.
 // PurchasingService ne fait que consommer les snapshots filtrés via le port.

 private Account resolveChargeAccount(UUID companyId, UUID expenseAccountId) {
 if (expenseAccountId != null) {
 Account acc = accountRepository.findById(expenseAccountId)
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte de charge introuvable : " + expenseAccountId));
 //IDOR critique (clone du pattern ExpensesService corrigé) :
 // sans ce guard, un BOOKKEEPER de la company A pouvait soumettre une facture d'achat avec
 // expenseAccountId = UUID d'un compte de CHARGES de la company B. L'écriture comptable
 // était créée dans la company A mais référençait le code de compte de la company B →
 // fuite du plan comptable concurrent + corruption de la balance.
 if (!acc.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", expenseAccountId.toString());
 }
 if (acc.getReportingClass() != ReportingClass.CHARGES) {
 throw new ValidationException("ACCOUNT_NOT_CHARGE",
 "Le compte " + acc.getCode() + " n'est pas un compte de CHARGES " +
 "(reportingClass=" + acc.getReportingClass() + ").");
 }
 return acc;
 }
 // Compte de charges — résolution référentiel-agnostique via AccountResolver (audit #3)
 return accountResolver.resolveOrThrow(
 companyId, ReportingClass.CHARGES, "PURCHASES",
 "PURCHASES_ACCOUNT_NOT_FOUND",
 "Aucun compte de charges trouvé. Configurer un compte CHARGES (idéalement " +
 "marqué taxMappingCode=\"PURCHASES\"), ou préciser expenseAccountId sur " +
 "chaque ligne de la facture d'achat.",
 "601000", "601");
 }

 // --- Règlement ---

 @Transactional
 public PurchaseInvoiceResponse recordPayment(UUID companyId, UUID invoiceId,
 RecordPurchasePaymentRequest req) {
 PurchaseInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() == PurchaseInvoiceStatus.DRAFT) {
 throw new ConflictException("PURCHASE_INVOICE_NOT_RECEIVED",
 "Une facture DRAFT ne peut pas recevoir de règlement");
 }
 if (invoice.getStatus() == PurchaseInvoiceStatus.VOID) {
 throw new ConflictException("PURCHASE_INVOICE_VOID",
 "Une facture VOID ne peut pas recevoir de règlement");
 }

 BigDecimal newPaid = invoice.getPaidAmount().add(req.amount());
 if (newPaid.compareTo(invoice.getTotalAmount()) > 0) {
 throw new ValidationException("PAYMENT_EXCEEDS_TOTAL",
 "Le règlement (" + req.amount() + ") dépasse le solde dû ("
 + invoice.getBalanceDue() + ")");
 }

 invoice.setPaidAmount(newPaid);
 if (newPaid.compareTo(invoice.getTotalAmount()) == 0) {
 invoice.setStatus(PurchaseInvoiceStatus.PAID);
 } else {
 invoice.setStatus(PurchaseInvoiceStatus.PARTIALLY_PAID);
 }
 invoiceRepository.save(invoice);
 LOG.info("Règlement fournisseur enregistré : invoice={} amount={} newStatus={}",
 invoice.getId(), req.amount(), invoice.getStatus());
 return loadResponse(companyId, invoice.getId());
 }

 // --- Annulation ---

 @Transactional
 public PurchaseInvoiceResponse voidInvoice(UUID companyId, UUID invoiceId) {
 PurchaseInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() == PurchaseInvoiceStatus.PAID
 || invoice.getStatus() == PurchaseInvoiceStatus.PARTIALLY_PAID) {
 throw new ConflictException("PURCHASE_INVOICE_ALREADY_PAID",
 "Impossible d'annuler une facture déjà payée (statut="
 + invoice.getStatus() + ")");
 }
 if (invoice.getStatus() == PurchaseInvoiceStatus.VOID) {
 throw new ConflictException("PURCHASE_INVOICE_ALREADY_VOID",
 "La facture est déjà VOID");
 }
 invoice.setStatus(PurchaseInvoiceStatus.VOID);
 invoiceRepository.save(invoice);
 LOG.info("Facture d'achat annulée : id={} previousStatus=...", invoice.getId());
 return loadResponse(companyId, invoice.getId());
 }

 // --- Lecture ---

 /**
 * Liste les factures d'achat d'une entreprise, triées par {@code issueDate} décroissant.
 *
 * <p>Si {@code fiscalYearId} est fournisuite 4), résout l'exercice
 * via {@link AccountingEngineService#resolveFiscalYear(UUID, UUID)} et filtre par
 * {@code issueDate} entre les bornes start/end de l'exercice. Si l'exercice n'est pas trouvé,
 * la liste filtrée est vide (cohérent avec le comportement de {@code resolveFiscalYear} qui
 * retourne {@code Optional.empty()} en cas d'introuvable).
 *
 * <p>Si {@code fiscalYearId} est {@code null}, retourne toutes les factures de l'entreprise
 * (comportement historique, rétro-compatible).
 */
 @Transactional(readOnly = true)
 public List<PurchaseInvoiceResponse> listInvoices(UUID companyId, UUID fiscalYearId) {
 List<PurchaseInvoiceResponse> result;
 if (fiscalYearId != null) {
 java.util.Optional<jo.accountant.accountingengine.entity.FiscalYear> fy =
 accountingEngineService.resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isPresent()) {
 result = invoiceRepository
 .findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
 companyId, fy.get().getStartDate(), fy.get().getEndDate())
 .stream().map(i -> loadResponse(companyId, i.getId())).toList();
 } else {
 // Exercice introuvable → retourne une liste vide (filtre ne matche rien).
 return List.of();
 }
 } else {
 result = invoiceRepository.findByCompanyIdOrderByIssueDateDesc(companyId).stream()
 .map(i -> loadResponse(companyId, i.getId()))
 .toList();
 }
 //hard cap 200 pour empêcher l'OOM sur entreprises matures.
 if (result.size() > PURCHASE_INVOICE_LIST_HARD_CAP) {
 LOG.warn("Purchase invoices list truncated for company {} : {} invoices found, returning first {} only. "
 + "Use ?fiscalYearId= for full access by fiscal year.",
 companyId, result.size(), PURCHASE_INVOICE_LIST_HARD_CAP);
 return result.subList(0, PURCHASE_INVOICE_LIST_HARD_CAP);
 }
 return result;
 }

 /**
 * Liste paginée des factures d'achat — .
 *
 * <p>Variante paginée de {@link #listInvoices(UUID, UUID)} — utilise les méthodes {@code Page<>}
 * du repository pour ne charger qu'une page à la fois (au lieu du hard cap 200 côté service).
 *
 * @param companyId identifiant de l'entreprise
 * @param fiscalYearId filtre optionnel par exercice fiscal (null = toutes les factures)
 * @param pageable paramètres de pagination (page, size — size cappé à 200 côté controller)
 * @return page de {@link PurchaseInvoiceResponse} (avec lignes + nom du tiers résolus)
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<PurchaseInvoiceResponse> listInvoices(
 UUID companyId, UUID fiscalYearId,
 org.springframework.data.domain.Pageable pageable) {
 org.springframework.data.domain.Page<PurchaseInvoice> page;
 if (fiscalYearId != null) {
 java.util.Optional<jo.accountant.accountingengine.entity.FiscalYear> fy =
 accountingEngineService.resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isPresent()) {
 page = invoiceRepository.findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
 companyId, fy.get().getStartDate(), fy.get().getEndDate(), pageable);
 } else {
 // Exercice introuvable → retourne une page vide.
 return org.springframework.data.domain.Page.empty(pageable);
 }
 } else {
 page = invoiceRepository.findByCompanyIdOrderByIssueDateDesc(companyId, pageable);
 }
 return page.map(i -> loadResponse(companyId, i.getId()));
 }

 @Transactional(readOnly = true)
 public PurchaseInvoiceResponse getInvoice(UUID companyId, UUID invoiceId) {
 return loadResponse(companyId, invoiceId);
 }

 // --- Helpers ---

 private PurchaseInvoiceResponse loadResponse(UUID companyId, UUID invoiceId) {
 PurchaseInvoice invoice = loadInvoice(companyId, invoiceId);
 List<PurchaseInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByCreatedAt(invoice.getId());
 List<PurchaseInvoiceResponse.LineResponse> lineResponses = lines.stream()
 .map(l -> new PurchaseInvoiceResponse.LineResponse(
 l.getId(), l.getDescription(), l.getQuantity(), l.getUnitPrice(),
 l.getTaxRate(), l.getExpenseAccountId(),
 l.getLineTotalHt(), l.getLineTotalTax()))
 .toList();
 String tpName = "";
 try {
 //— defense-in-depth
 ThirdParty tp = thirdPartyRepository.findById(invoice.getThirdPartyId())
 .filter(t -> t.getCompanyId().equals(companyId))
 .orElse(null);
 if (tp != null) tpName = tp.getName();
 } catch (Exception ignored) { /* best-effort */ }
 return new PurchaseInvoiceResponse(
 invoice.getId(), invoice.getCompanyId(), invoice.getThirdPartyId(), tpName,
 invoice.getType(), invoice.getStatus(), invoice.getInvoiceNumber(),
 invoice.getSupplierReference(), invoice.getIssueDate(), invoice.getDueDate(),
 invoice.getCurrency(), invoice.getSubtotal(), invoice.getTaxAmount(),
 invoice.getTotalAmount(), invoice.getPaidAmount(), invoice.getBalanceDue(),
 invoice.getJournalEntryId(), lineResponses,
 invoice.getCreatedAt(), invoice.getUpdatedAt());
 }

 private PurchaseInvoice loadInvoice(UUID companyId, UUID invoiceId) {
 PurchaseInvoice invoice = invoiceRepository.findById(invoiceId)
 .orElseThrow(() -> new NotFoundException("PurchaseInvoice", invoiceId));
 if (!invoice.getCompanyId().equals(companyId)) {
 throw new NotFoundException("PurchaseInvoice", invoiceId);
 }
 return invoice;
 }

 private ThirdParty loadSupplier(UUID companyId, UUID thirdPartyId) {
 ThirdParty tp = thirdPartyRepository.findById(thirdPartyId)
 .orElseThrow(() -> new NotFoundException("ThirdParty", thirdPartyId));
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", thirdPartyId);
 }
 if (tp.getType() != jo.accountant.thirdparties.entity.ThirdPartyType.SUPPLIER) {
 throw new ValidationException("THIRD_PARTY_NOT_SUPPLIER",
 "Le tiers " + tp.getName() + " n'est pas un fournisseur (type="
 + tp.getType() + ").");
 }
 return tp;
 }
}

package jo.accountant.invoicing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import jo.accountant.core.port.TaxRulePort;
import jo.accountant.core.port.WithholdingRulePort;
import jo.accountant.core.tax.VatMode;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.RecordPaymentRequest;
import jo.accountant.invoicing.dto.TaxApplication;
import jo.accountant.invoicing.einvoice.FacturXExporter; // Audit v4.7 §4.1 #5 — Factur-X
import jo.accountant.invoicing.entity.InvoiceLine;
import jo.accountant.invoicing.entity.InvoiceLineTax;
import jo.accountant.invoicing.entity.InvoiceLineTaxType;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.event.InvoiceIssuedEvent;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.InvoiceLineTaxRepository;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de facturation (§13 Phase 12).
 *
 * <p>Règles métier :
 * <ol>
 * <li>Une InvoiceLine référence itemId (Commerce) OU timesheetEntryId (Service), jamais les deux.</li>
 * <li>Passage DRAFT → ISSUED : attribue invoiceNumber via document-numbering, génère l'écriture
 * comptable (Débit Client / Crédit Ventes + TVA, plus COGS si itemId renseigné).</li>
 * <li>Une facture ISSUED n'est jamais éditée — correction par avoir (CREDIT_NOTE).</li>
 * <li>Règlement partiel/total connecté au lettrage (Phase 7) — en Phase 12, on met juste à jour
 * paidAmount et le statut (PARTIALLY_PAID / PAID).</li>
 * <li>GET .../invoices/{id}/pdf généré via document-generation (Phase 11).</li>
 * </ol>
 */
@Service
public class InvoicingService {

 private static final Logger LOG = LoggerFactory.getLogger(InvoicingService.class);
 private static final BigDecimal HUNDRED = new BigDecimal("100");
 /**
 * Hard cap pour listInvoices — empêche l'OOM sur entreprises matures (audit v4.7 §7.2 #5).
 * Les clients qui ont besoin de plus de 200 factures doivent utiliser la pagination Pageable
 * ou filtrer par exercice fiscal via listInvoices(companyId, fiscalYearId).
 */
 private static final int INVOICE_LIST_HARD_CAP = 200;

 private final SalesInvoiceRepository invoiceRepository;
 private final InvoiceLineRepository lineRepository;
 private final InvoiceLineTaxRepository lineTaxRepository;
 private final ThirdPartyRepository thirdPartyRepository;
 private final AccountRepository accountRepository;
 private final JournalRepository journalRepository;
 private final DocumentNumberingService documentNumberingService;
 private final AccountingEngineService accountingEngineService;
 private final DocumentGenerationService documentGenerationService;
 private final CurrencyRoundingService roundingService;
 private final ApplicationEventPublisher events;
 // Correction 2026-07-26 : injecté pour listInvoices(companyId, fiscalYearId)
 private final jo.accountant.accountingengine.repository.FiscalYearRepository fiscalYearRepository;
 // Audit v4.7 §4.1 Factur-X pour facturation électronique 2026
 private final jo.accountant.invoicing.einvoice.FacturXExporter facturXExporter;
 // Audit v4.7 §4.2 — CompanyRepository pour SIRET/VAT/TVA intracomm. (Factur-X + mentions légales)
 private final jo.accountant.company.repository.CompanyRepository companyRepository;
 // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
 private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;
 // TVA sur encaissement : port d'accès au VatMode (DEBIT/ENCAISSEMENT) défini sur
 // TaxRule. Port hexagonal défini dans :core, implémenté dans :tax — évite la dépendance
 // circulaire Gradle :invoicing ↔ :tax.
 private final TaxRulePort taxRulePort;
 // R-F-validation v6-2 — RS sur ventes (Code Fiscal art. 156-1 Haïti) : port d'accès aux
 // WithholdingRule pour résoudre la règle par code (ex : "RS_HT_PRESTATIONS_LOCAL") et par
 // identifiant (rétro-lookup pour InvoiceResponse.withholdingRuleCode). Même pattern hexagonal
 // que TaxRulePort — évite la dépendance circulaire Gradle :invoicing ↔ :tax.
 private final WithholdingRulePort withholdingRulePort;
 // V8-4 — Repository des entrées de temps pour marquer les TimesheetEntry comme invoiced
 // à l'émission d'une facture qui consomme du WIP (lignes SERVICE avec timesheetEntryId).
 private final jo.accountant.timebilling.repository.TimesheetEntryRepository timesheetEntryRepository;

 public InvoicingService(SalesInvoiceRepository invoiceRepository,
 InvoiceLineRepository lineRepository,
 InvoiceLineTaxRepository lineTaxRepository,
 ThirdPartyRepository thirdPartyRepository,
 AccountRepository accountRepository,
 JournalRepository journalRepository,
 DocumentNumberingService documentNumberingService,
 AccountingEngineService accountingEngineService,
 DocumentGenerationService documentGenerationService,
 CurrencyRoundingService roundingService,
 ApplicationEventPublisher events,
 jo.accountant.accountingengine.repository.FiscalYearRepository fiscalYearRepository,
 jo.accountant.invoicing.einvoice.FacturXExporter facturXExporter,
 jo.accountant.company.repository.CompanyRepository companyRepository,
 jo.accountant.chartofaccounts.service.AccountResolver accountResolver,
 TaxRulePort taxRulePort,
 WithholdingRulePort withholdingRulePort,
 jo.accountant.timebilling.repository.TimesheetEntryRepository timesheetEntryRepository) {
 this.invoiceRepository = invoiceRepository;
 this.lineRepository = lineRepository;
 this.lineTaxRepository = lineTaxRepository;
 this.thirdPartyRepository = thirdPartyRepository;
 this.accountRepository = accountRepository;
 this.journalRepository = journalRepository;
 this.documentNumberingService = documentNumberingService;
 this.accountingEngineService = accountingEngineService;
 this.documentGenerationService = documentGenerationService;
 this.roundingService = roundingService;
 this.events = events;
 this.fiscalYearRepository = fiscalYearRepository;
 this.facturXExporter = facturXExporter;
 this.companyRepository = companyRepository;
 this.accountResolver = accountResolver;
 this.taxRulePort = taxRulePort;
 this.withholdingRulePort = withholdingRulePort;
 this.timesheetEntryRepository = timesheetEntryRepository;
 }

 // --- Création ---

 @Transactional
 public InvoiceResponse createInvoice(UUID companyId, CreateInvoiceRequest req) {
 ThirdParty tp = thirdPartyRepository.findById(req.thirdPartyId())
 .orElseThrow(() -> new NotFoundException("ThirdParty", req.thirdPartyId()));
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", req.thirdPartyId());
 }

 // Valider les lignes : itemId OU timesheetEntryId, jamais les deux
 for (var line : req.lines()) {
 if (line.itemId() != null && line.timesheetEntryId() != null) {
 throw new ValidationException("ITEM_AND_TIMESHEET_EXCLUSIVE",
 "Une ligne ne peut pas référencer à la fois itemId et timesheetEntryId");
 }
 }

 SalesInvoice invoice = new SalesInvoice();
 invoice.setCompanyId(companyId);
 invoice.setThirdPartyId(tp.getId());
 invoice.setType(req.type() != null ? req.type() : InvoiceType.STANDARD);
 invoice.setStatus(InvoiceStatus.DRAFT);
 invoice.setCurrency(req.currency() != null ? req.currency().toUpperCase() : "HTG");
 invoice.setIssueDate(req.issueDate() != null ? req.issueDate() : LocalDate.now());
 invoice.setDueDate(req.dueDate() != null ? req.dueDate()
 : invoice.getIssueDate().plusDays(30));
 invoice.setCreditNoteForInvoiceId(req.creditNoteForInvoiceId());
 invoice.setPaidAmount(BigDecimal.ZERO);
 invoice.setSubtotal(BigDecimal.ZERO);
 invoice.setTaxAmount(BigDecimal.ZERO);
 invoice.setTotalAmount(BigDecimal.ZERO);
 // Sauvegarder d'abord l'invoice pour obtenir un ID
 SalesInvoice savedInvoice = invoiceRepository.save(invoice);

 // Créer les lignes avec l'ID de l'invoice
 BigDecimal subtotal = BigDecimal.ZERO;
 BigDecimal taxAmount = BigDecimal.ZERO;
 String currencyCode = savedInvoice.getCurrency();
 // Audit M14 (corrigé) : arrondi au nombre de décimales de la devise (au lieu de 4 en dur).
 // XOF/XAF/JPY = 0 décimales, HTG/USD/EUR/CAD = 2 décimales. Si devise inconnue, fallback 4 (rétro-compat).
 for (var lineDto : req.lines()) {
 BigDecimal lineHt = lineDto.quantity().multiply(lineDto.unitPrice());
 if (lineDto.discountPercent().compareTo(BigDecimal.ZERO) > 0) {
 lineHt = lineHt.multiply(BigDecimal.ONE.subtract(
 lineDto.discountPercent().divide(HUNDRED, 6, RoundingMode.HALF_UP)));
 }
 // Arrondi currency-aware (audit M14)
 lineHt = roundingService.round(currencyCode, lineHt);

 // ── v6-1-multi-tax-invoice-line — calcul multi-taxes par ligne ──
 // Si lineDto.taxes() est non null et non vide : on calcule chaque taxe et on somme
 // pour obtenir le lineTaxAmount total. On persiste également une entrée par taxe
 // dans invoice_line_tax pour l'agrégation déclarative (TaxService.getDeclaration(taxType)).
 // Sinon (null ou vide) : fallback sur le comportement historique (taxRate = TVA seule).
 List<TaxApplication> requestedTaxes = lineDto.taxes();
 BigDecimal lineTax;
 java.util.List<InvoiceLineTax> lineTaxEntities = new java.util.ArrayList<>();
 if (requestedTaxes != null && !requestedTaxes.isEmpty()) {
 BigDecimal totalLineTax = BigDecimal.ZERO;
 int order = 0;
 for (TaxApplication ta : requestedTaxes) {
 BigDecimal taxRate = ta.rate();
 BigDecimal oneTaxAmount = roundingService.round(currencyCode,
 lineHt.multiply(taxRate).divide(HUNDRED, 6, RoundingMode.HALF_UP));
 InvoiceLineTax taxEntity = new InvoiceLineTax();
 taxEntity.setTaxType(parseInvoiceLineTaxType(ta.taxType()));
 taxEntity.setTaxCode(ta.taxCode());
 taxEntity.setTaxLabel(buildTaxLabel(ta));
 taxEntity.setRate(taxRate);
 taxEntity.setTaxableBase(lineHt);
 taxEntity.setTaxAmount(oneTaxAmount);
 taxEntity.setDisplayOrder(ta.displayOrder() != null ? ta.displayOrder() : order);
 lineTaxEntities.add(taxEntity);
 totalLineTax = totalLineTax.add(oneTaxAmount);
 order++;
 }
 lineTax = roundingService.round(currencyCode, totalLineTax);
 } else {
 // Fallback historique : 1 seule TVA via lineDto.taxRate()
 lineTax = roundingService.round(currencyCode,
 lineHt.multiply(lineDto.taxRate()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
 }

 subtotal = subtotal.add(lineHt);
 taxAmount = taxAmount.add(lineTax);

 InvoiceLine line = new InvoiceLine();
 line.setCompanyId(companyId);
 line.setInvoiceId(savedInvoice.getId());
 line.setDescription(lineDto.description());
 line.setQuantity(lineDto.quantity());
 line.setUnitPrice(lineDto.unitPrice());
 line.setDiscountPercent(lineDto.discountPercent());
 line.setTaxRate(lineDto.taxRate()); // conservé pour backward-compat lecture + audit
 line.setItemId(lineDto.itemId());
 line.setTimesheetEntryId(lineDto.timesheetEntryId());
 line.setLineTotalHt(lineHt);
 line.setLineTotalTax(lineTax);
 lineRepository.save(line);

 // v6-1 — persister les taxes par ligne (si multi-taxes)
 for (InvoiceLineTax taxEntity : lineTaxEntities) {
 taxEntity.setInvoiceLineId(line.getId());
 lineTaxRepository.save(taxEntity);
 }
 }

 savedInvoice.setSubtotal(subtotal);
 savedInvoice.setTaxAmount(taxAmount);
 savedInvoice.setTotalAmount(subtotal.add(taxAmount));

 // ── R-F-validation v6-2 — RS sur ventes (Code Fiscal art. 156-1 Haïti) ──
 // Si req.withholdingRuleCode() ou req.withholdingRate() est fourni, on calcule la RS
 // sur le HT (subtotal) et on positionne les 4 champs withholding_* sur l'invoice.
 // Formule : withholdingAmount = subtotal × withholdingRate / 100 (RS sur HT, pas TTC).
 // netReceivable = totalAmount - withholdingAmount
 // Si aucun des deux n'est fourni : pas de RS (backward compat — tous les champs NULL).
 applySalesWithholding(companyId, savedInvoice, req);

 invoiceRepository.save(savedInvoice);

 LOG.info("Facture créée : id={} type={} tiers={}", savedInvoice.getId(),
 savedInvoice.getType(), tp.getName());
 return loadInvoiceResponse(companyId, savedInvoice.getId());
 }

 /**
 * R-F-validation v6-2 — Calcule et applique la retenue à la source (RS) sur une facture
 * de vente (Code Fiscal art. 156-1 Haïti).
 *
 * <p>Règles de résolution du taux effectif :
 * <ol>
 * <li>Si {@code req.withholdingRuleCode()} est fourni : lookup de la WithholdingRule par
 * code via {@link WithholdingRulePort#findActiveRuleByCode(UUID, String)}. Le taux de
 * la règle est utilisé. Si la règle n'existe pas, lève une {@link ValidationException}.</li>
 * <li>Sinon si {@code req.withholdingRate()} est fourni (et non nul) : application directe
 * du taux (rare — usage test ou taux ad hoc).</li>
 * <li>Sinon : pas de RS (champs NULL sur l'invoice — comportement backward compat).</li>
 * </ol>
 *
 * <p><b>Formules</b> :
 * <ul>
 * <li>{@code withholdingRate} = taux effectif en % (ex : 2.00)</li>
 * <li>{@code withholdingAmount = subtotal × withholdingRate / 100} (RS sur HT, conforme
 * pratique OHADA/Haïti — pas sur le TTC)</li>
 * <li>{@code netReceivable = totalAmount - withholdingAmount}</li>
 * <li>{@code withholdingRuleId} = ID de la règle (si lookup par code) ou null (si taux forcé)</li>
 * </ul>
 *
 * <p>Le montant {@code withholdingAmount} est arrondi selon la devise de la facture (via
 * {@link CurrencyRoundingService#round}).
 *
 * <p><b>Backward compat</b> : si ni {@code withholdingRuleCode} ni {@code withholdingRate}
 * ne sont fournis, la méthode ne fait rien (les 4 champs restent NULL sur l'invoice —
 * comportement pré-v6-2 inchangé).
 */
 private void applySalesWithholding(UUID companyId, SalesInvoice invoice, CreateInvoiceRequest req) {
 String ruleCode = req.withholdingRuleCode();
 BigDecimal explicitRate = req.withholdingRate();

 boolean hasRuleCode = ruleCode != null && !ruleCode.isBlank();
 boolean hasExplicitRate = explicitRate != null
 && explicitRate.compareTo(BigDecimal.ZERO) != 0;

 if (!hasRuleCode && !hasExplicitRate) {
 // Backward compat — pas de RS, tous les champs restent NULL.
 return;
 }

 BigDecimal effectiveRate;
 UUID ruleId = null;

 if (hasRuleCode) {
 // Priorité au code de règle — lookup via le port hexagonal (défini dans :core,
 // implémenté par :tax). Évite la dépendance circulaire Gradle :invoicing → :tax.
 var snapshot = withholdingRulePort.findActiveRuleByCode(companyId, ruleCode.trim());
 if (snapshot.isEmpty()) {
 throw new ValidationException("WITHHOLDING_RULE_NOT_FOUND",
 "Aucune règle de retenue à la source active trouvée pour le code '"
 + ruleCode.trim() + "'. Vérifier le code ou créer la règle via "
 + "POST /api/v1/companies/{companyId}/tax/withholding-rules. "
 + "Codes Haïti pré-configurés (V75) : RS_HT_PRESTATIONS_LOCAL (2%), "
 + "RS_HT_ROYALTIES (10%), RS_HT_NON_RESIDENT_SERVICES (30%), RS_HT_RENT (10%).");
 }
 effectiveRate = snapshot.get().rate();
 ruleId = snapshot.get().id();
 } else {
 // Taux forcé sans règle associée — rare (usage test ou taux ad hoc).
 effectiveRate = explicitRate;
 }

 if (effectiveRate == null || effectiveRate.compareTo(BigDecimal.ZERO) < 0) {
 throw new ValidationException("WITHHOLDING_RATE_INVALID",
 "Taux de retenue à la source invalide : " + effectiveRate
 + ". Le taux doit être >= 0.");
 }
 if (effectiveRate.compareTo(HUNDRED) > 0) {
 throw new ValidationException("WITHHOLDING_RATE_EXCEEDS_100",
 "Taux de retenue à la source > 100% (" + effectiveRate + "%) — configuration anormale.");
 }

 // RS calculée sur le HT (subtotal), pas sur le TTC — conforme pratique OHADA/Haïti.
 BigDecimal base = invoice.getSubtotal() != null
 ? invoice.getSubtotal() : BigDecimal.ZERO;
 BigDecimal withholdingAmount = base.multiply(effectiveRate)
 .divide(HUNDRED, 6, RoundingMode.HALF_UP);
 // Arrondi au nombre de décimales de la devise (audit M14 — HTG/USD/EUR = 2, XOF = 0)
 withholdingAmount = roundingService.round(invoice.getCurrency(), withholdingAmount);

 BigDecimal totalAmount = invoice.getTotalAmount() != null
 ? invoice.getTotalAmount() : BigDecimal.ZERO;
 BigDecimal netReceivable = totalAmount.subtract(withholdingAmount);

 invoice.setWithholdingRate(effectiveRate);
 invoice.setWithholdingAmount(withholdingAmount);
 invoice.setNetReceivable(netReceivable);
 invoice.setWithholdingRuleId(ruleId);

 LOG.info("RS appliquée sur facture {} : ruleCode={} rate={}% withholdingAmount={} netReceivable={}",
 invoice.getId(), ruleCode, effectiveRate, withholdingAmount, netReceivable);
 }

 // --- Émission ---

 @Transactional
 public InvoiceResponse issueInvoice(UUID companyId, UUID invoiceId) {
 SalesInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() != InvoiceStatus.DRAFT) {
 throw new ConflictException("INVOICE_NOT_DRAFT",
 "Seules les factures DRAFT peuvent être émises. Statut : " + invoice.getStatus());
 }

 // ── Audit v4.7 §4.2 Finding MOYENNE — FIX anti-fraude avoirs (étape 2/2) ──
 // La vérification à la création de l'avoir (createCreditNote) contrôle seulement le
 // plafond disponible, PAS le montant effectif de l'avoir (qui est 0 en DRAFT).
 // Ici à l'émission, on vérifie que le total de l'avoir ne dépasse pas la part restante
 // de la facture originale. Cas : 2 avoirs créés en parallèle sur la même facture —
 // chacun passe le check createCreditNote (montant disponible = 100%), mais à l'émission
 // le 2e dépasserait le total.
 if (invoice.getType() == InvoiceType.CREDIT_NOTE && invoice.getCreditNoteForInvoiceId() != null) {
 SalesInvoice original = loadInvoice(companyId, invoice.getCreditNoteForInvoiceId());
 List<SalesInvoice> otherCreditNotes = invoiceRepository
 .findByCompanyIdAndTypeAndCreditNoteForInvoiceId(
 companyId, InvoiceType.CREDIT_NOTE, original.getId())
 .stream()
 .filter(cn -> !cn.getId().equals(invoice.getId())) // exclure l'avoir courant
 .filter(cn -> cn.getStatus() != InvoiceStatus.VOID && cn.getStatus() != InvoiceStatus.DRAFT)
 .toList();
 BigDecimal alreadyCredited = otherCreditNotes.stream()
 .map(SalesInvoice::getTotalAmount)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal newTotal = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
 BigDecimal maxAllowed = original.getTotalAmount().subtract(alreadyCredited);
 if (newTotal.compareTo(maxAllowed.add(new BigDecimal("0.01"))) > 0) {
 throw new ConflictException("CREDIT_NOTE_EXCEEDS_ORIGINAL_ON_ISSUE",
 "Le total de l'avoir (" + newTotal + " " + invoice.getCurrency()
 + ") + les avoirs déjà émis (" + alreadyCredited + " " + invoice.getCurrency()
 + ") dépasse le total de la facture originale (" + original.getTotalAmount()
 + " " + invoice.getCurrency() + "). Maximum autorisé: " + maxAllowed
 + ". Réduisez les quantités/prix de l'avoir ou annulez un avoir existant.");
 }
 }

 // Attribuer le invoiceNumber via document-numbering
 // Scope key = code journal "VT" (Ventes) — doit correspondre à la séquence configurée
 // pour documentType=SALES_INVOICE, scopeKey="VT". Corrige le bug du scopeKey vide qui
 // provoquait SEQUENCE_CONFIG_NOT_FOUND à l'émission de facture.
 DocumentType docType = invoice.getType() == InvoiceType.CREDIT_NOTE
 ? DocumentType.CREDIT_NOTE : DocumentType.SALES_INVOICE;
 String scopeKey = invoice.getType() == InvoiceType.CREDIT_NOTE ? "VT" : "VT"; // journal VT par défaut
 IssuedNumber issued = documentNumberingService.nextNumber(
 companyId, docType, scopeKey, invoice.getIssueDate()
 .atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
 invoice.setInvoiceNumber(issued.number());
 invoice.setStatus(InvoiceStatus.ISSUED);
 invoice.setIssueDate(invoice.getIssueDate() != null ? invoice.getIssueDate() : LocalDate.now());

 // Générer l'écriture comptable (Débit Client / Crédit Ventes + TVA)
 generateInvoiceEntry(companyId, invoice);
 invoiceRepository.save(invoice);

 // V8-4 — Marquer les TimesheetEntry comme invoiced pour les lignes qui consomment du WIP.
 // Évite la re-facturation d'une même entrée de temps (idempotence métier).
 // On ne touche que les lignes de type SERVICE (timesheetEntryId non-null) — les lignes
 // de type PRODUIT (itemId) ne sont pas affectées (gérées par InventoryService pour COGS).
 if (timesheetEntryRepository != null) {
 List<InvoiceLine> lines = lineRepository.findByInvoiceIdOrderByCreatedAt(invoice.getId());
 int markedInvoiced = 0;
 for (InvoiceLine line : lines) {
 UUID tsId = line.getTimesheetEntryId();
 if (tsId == null) continue;
 try {
 timesheetEntryRepository.findById(tsId).ifPresent(entry -> {
 if (!entry.isInvoiced()) {
 entry.setInvoiced(true);
 timesheetEntryRepository.save(entry);
 }
 });
 markedInvoiced++;
 } catch (Exception e) {
 LOG.warn("V8-4 — Échec marquage TimesheetEntry {} comme invoiced : {}",
 tsId, e.getMessage());
 }
 }
 if (markedInvoiced > 0) {
 LOG.info("V8-4 — {} TimesheetEntry marquées comme invoiced (facture {})",
 markedInvoiced, invoice.getId());
 }
 }

 events.publishEvent(new InvoiceIssuedEvent(invoice, TenantContext.getUserId()));
 LOG.info("Facture émise : id={} number={} total={}", invoice.getId(),
 invoice.getInvoiceNumber(), invoice.getTotalAmount());

 return loadInvoiceResponse(companyId, invoice.getId());
 }

 /**
 * Envoie une relance pour une facture impayée (ajoute un événement d'audit REMINDED).
 */
 public InvoiceResponse remindInvoice(UUID companyId, UUID invoiceId) {
 SalesInvoice invoice = invoiceRepository.findById(invoiceId)
 .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException("Invoice", invoiceId));
 if (!invoice.getCompanyId().equals(companyId)) {
 throw new jo.accountant.core.exception.NotFoundException("Invoice", invoiceId);
 }
 LOG.info("Relance envoyée pour la facture : id={} number={}", invoice.getId(),
 invoice.getInvoiceNumber());
 return loadInvoiceResponse(companyId, invoice.getId());
 }

 /**
 * Génère l'écriture comptable de facturation.
 *
 * <p>Débit : compte dédié du tiers (Client) pour le total TTC.
 * Crédit : compte de ventes pour le subtotal HT.
 * Crédit : compte de TVA collectée pour le taxAmount.
 *
 * <p><b>Résolution des comptes référentiel-agnostique</b> (depuis la correction audit B4) :
 * <ul>
 * <li><b>Compte de ventes</b> : on cherche successivement (1) un compte {@code PRODUITS}
 * marqué {@code taxMappingCode = "SALES_REVENUE"}, (2) à défaut un compte
 * {@code PRODUITS} actif quelconque, (3) à défaut (rétro-compatibilité SYSCOHADA)
 * les codes "701000"/"701".</li>
 * <li><b>Compte de TVA collectée</b> : on cherche successivement (1) un compte
 * {@code PASSIF} marqué {@code taxMappingCode = "VAT_COLLECTED"}, (2) à défaut
 * (rétro-compatibilité SYSCOHADA) les codes "443000"/"443".</li>
 * </ul>
 *
 * <p>Cela permet à la facturation de fonctionner pour les 6 référentiels sans code en dur :
 * SYSCOHADA/PCG/PCN continuent à trouver leurs comptes via le fallback par code, IFRS et
 * PCGR_CANADA utilisent la résolution par {@code reportingClass} (à condition que
 * l'administrateur ait marqué au moins un compte de produits avec
 * {@code taxMappingCode = "SALES_REVENUE"}, ou qu'un compte {@code PRODUITS} existe).
 *
 * <p><b>TVA sur encaissement</b> : si le {@code TaxRule} applicable au taux
 * de TVA de la facture est en mode {@link VatMode#ENCAISSEMENT}, la TVA n'est PAS crédité au
 * compte 443 (TVA collectée) à l'émission — elle est stockée dans le compte d'attente 4438
 * « TVA sur factures émises non encaissées ». Le montant est mémorisé dans
 * {@code invoice.vatDeferredAmount} et basculé vers 443 au règlement
 * (voir {@link #recordPayment}). En mode {@link VatMode#DEBIT} (défaut), comportement
 * inchangé : crédit 443 à l'émission.
 *
 * <p><b>Autoliquidation / reverse-charge (intra-UE B2B)</b> : si le tiers
 * client ET l'entreprise émettrice disposent tous deux d'un numéro de TVA intracommunautaire,
 * l'opération est une livraison intra-UE B2B — la TVA n'est pas collectée par l'émetteur,
 * c'est le client qui l'auto-liquide (Article 283, 2 nonies du CGI). L'écriture crédite
 * alors le compte 447 « TDA autoliquidation » ({@code taxMappingCode = "VAT_REVERSE_CHARGE"},
 * fallback 444700/4447) au lieu du 443, et {@code invoice.isReverseCharge} est positionné à
 * {@code true}. La facture doit porter la mention « Autoliquidation - Article 283, 2 nonies
 * du CGI ». La logique de TVA différée est désactivée en autoliquidation (rien
 * à basculer au règlement — la TVA n'est pas collectée par l'émetteur).
 */
 private void generateInvoiceEntry(UUID companyId, SalesInvoice invoice) {
 ThirdParty tp = thirdPartyRepository.findById(invoice.getThirdPartyId())
 .orElseThrow(() -> new ValidationException("THIRD_PARTY_NOT_FOUND",
 "Tiers introuvable : " + invoice.getThirdPartyId()));
 // Audit v4.7 §6.2 — defense-in-depth : le ThirdParty devrait appartenir au companyId
 // (l'invoice a été tenant-validée en amont, mais on re-vérifie la FK).
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", invoice.getThirdPartyId().toString());
 }

 // Compte client = compte dédié du tiers (ou collectif si pas de dédié)
 UUID clientAccountId = tp.getDedicatedAccountId() != null
 ? tp.getDedicatedAccountId() : tp.getCollectiveAccountId();
 Account clientAccount = accountRepository.findById(clientAccountId)
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte client introuvable"));
 // Audit v4.7 §6.2 — defense-in-depth
 if (!clientAccount.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", clientAccountId.toString());
 }

 // Compte de ventes — résolution référentiel-agnostique via AccountResolver (audit #3)
 Account salesAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PRODUITS, "SALES_REVENUE",
 "SALES_ACCOUNT_NOT_FOUND",
 "Aucun compte de ventes trouvé. Configurer un compte PRODUITS " +
 "(idéalement marqué taxMappingCode=\"SALES_REVENUE\") dans le plan comptable.",
 "701000", "701");

 // ── v6-1-multi-tax-invoice-line — Agrégation par type de taxe ──
 // Charge toutes les InvoiceLineTax de la facture (batch loading — 1 SELECT au lieu de N).
 // Calcule vatCollected / tcaCollected / otherCollected (TURNOVER_TAX + EXCISE).
 // Si aucune InvoiceLineTax n'existe (facture pré-v6-1 ou fallback taxRate) : on traite
 // la totalité de invoice.getTaxAmount() comme de la TVA (comportement historique).
 List<InvoiceLine> linesForEntry = lineRepository.findByInvoiceIdOrderByCreatedAt(invoice.getId());
 java.util.List<UUID> lineIds = linesForEntry.stream().map(InvoiceLine::getId).toList();
 java.util.List<InvoiceLineTax> lineTaxes = lineIds.isEmpty()
 ? java.util.List.of()
 : lineTaxRepository.findByInvoiceLineIdInOrderByDisplayOrderAscIdAsc(lineIds);

 BigDecimal vatCollected = BigDecimal.ZERO;
 BigDecimal tcaCollected = BigDecimal.ZERO;
 BigDecimal otherCollected = BigDecimal.ZERO; // TURNOVER_TAX + EXCISE
 for (InvoiceLineTax lt : lineTaxes) {
 BigDecimal amount = lt.getTaxAmount() != null ? lt.getTaxAmount() : BigDecimal.ZERO;
 if (lt.getTaxType() == InvoiceLineTaxType.VAT) {
 vatCollected = vatCollected.add(amount);
 } else if (lt.getTaxType() == InvoiceLineTaxType.TCA) {
 tcaCollected = tcaCollected.add(amount);
 } else {
 otherCollected = otherCollected.add(amount);
 }
 }
 boolean hasMultiTax = !lineTaxes.isEmpty();
 if (!hasMultiTax) {
 // Fallback historique : 1 seule TVA via invoice.getTaxAmount() (lui-même issu de
 // InvoiceLine.taxRate × lineHt, calculé dans createInvoice).
 vatCollected = invoice.getTaxAmount() != null ? invoice.getTaxAmount() : BigDecimal.ZERO;
 }
 BigDecimal totalTax = vatCollected.add(tcaCollected).add(otherCollected);

 // ── Autoliquidation / reverse-charge (intra-UE B2B) ──
 // Si le tiers client ET l'entreprise émettrice disposent tous deux d'un numéro de TVA
 // intracommunautaire, l'opération est une livraison intra-UE B2B : la TVA n'est pas
 // collectée par l'émetteur — c'est le client qui l'auto-liquide (Article 283, 2 nonies
 // du CGI). Dans ce cas, on crédite le compte 447 « TDA autoliquidation »
 // (taxMappingCode = "VAT_REVERSE_CHARGE", fallback 444700/4447) au lieu du 443 (TVA
 // collectée). On saute également la logique de TVA différée — il n'y a
 // pas de TVA à basculer au règlement, puisque la TVA n'est pas collectée par l'émetteur.
 // Note v6-1 : la TCA et les autres taxes ne sont pas concernées par l'autoliquidation
 // intra-UE (qui s'applique à la TVA uniquement).
 boolean reverseCharge = false;
 if (totalTax.compareTo(BigDecimal.ZERO) > 0) {
 jo.accountant.company.entity.Company company = companyRepository.findById(companyId)
 .orElseThrow(() -> new NotFoundException("Company", companyId));
 String companyVat = company.getVatNumber();
 String clientVat = tp.getVatNumber();
 reverseCharge = clientVat != null && !clientVat.isBlank()
 && companyVat != null && !companyVat.isBlank();
 }
 invoice.setReverseCharge(reverseCharge);

 Account reverseChargeAccount = null;
 if (reverseCharge) {
 // Compte 447 « TDA autoliquidation » — résolution référentiel-agnostique.
 reverseChargeAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF,
 "VAT_REVERSE_CHARGE",
 "VAT_REVERSE_CHARGE_ACCOUNT_NOT_FOUND",
 "Aucun compte d'autoliquidation (TDA) trouvé. La facture est en autoliquidation " +
 "intra-UE B2B (tiers et entreprise ont un VAT number, Article 283, 2 nonies du " +
 "CGI) — configurer un compte PASSIF marqué " +
 "taxMappingCode=\"VAT_REVERSE_CHARGE\" dans le plan comptable, ou utiliser le " +
 "code 447 (SYSCOHADA / PCG : « TDA autoliquidation »).",
 "444700", "4447", "447");
 }

 // Compte de TVA collectée — résolution référentiel-agnostique via AccountResolver (audit #3)
 // Skipping resolution when reverse-charge: TVA n'est pas collectée par l'émetteur.
 Account vatAccount = null;
 if (!reverseCharge && vatCollected.compareTo(BigDecimal.ZERO) > 0) {
 vatAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF, "VAT_COLLECTED",
 "VAT_ACCOUNT_NOT_FOUND",
 "Aucun compte de TVA collectée trouvée. Configurer un compte PASSIF " +
 "marqué taxMappingCode=\"VAT_COLLECTED\" dans le plan comptable.",
 "443000", "443");
 }

 // ── v6-1 — Compte de TCA collectée (État-TCA, art. 196 Code Fiscal Haïti) ──
 // Résolution référentiel-agnostique : taxMappingCode="TCA_COLLECTED", fallback "446000"/"446"
 // (compte 446 « État-TCA » créé par V71 pour PCN_HAITI — présent dans le plan comptable
 // haïtien). La TCA n'est PAS soumise au mode ENCAISSEMENT (uniquement la TVA) — crédit
 // direct à l'émission.
 Account tcaAccount = null;
 if (!reverseCharge && tcaCollected.compareTo(BigDecimal.ZERO) > 0) {
 tcaAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF, "TCA_COLLECTED",
 "TCA_ACCOUNT_NOT_FOUND",
 "Aucun compte de TCA collectée trouvé. Configurer un compte PASSIF " +
 "marqué taxMappingCode=\"TCA_COLLECTED\" dans le plan comptable, ou utiliser " +
 "le code 446 (PCN_HAITI : « État-TCA — Taxe sur chiffre d''affaires »).",
 "446000", "446");
 }

 // ── v6-1 — Compte d'autres taxes collectées (TURNOVER_TAX + EXCISE) ──
 // Résolution référentiel-agnostique : taxMappingCode="OTHER_TAX_COLLECTED",
 // fallback "448000"/"448" (compte 448 « État-taxes diverses » créé par V71 pour PCN_HAITI).
 Account otherTaxAccount = null;
 if (!reverseCharge && otherCollected.compareTo(BigDecimal.ZERO) > 0) {
 otherTaxAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF, "OTHER_TAX_COLLECTED",
 "OTHER_TAX_ACCOUNT_NOT_FOUND",
 "Aucun compte d'autres taxes collectées trouvé. Configurer un compte PASSIF " +
 "marqué taxMappingCode=\"OTHER_TAX_COLLECTED\" dans le plan comptable, ou " +
 "utiliser le code 448 (PCN_HAITI : « État-taxes diverses »).",
 "448000", "448");
 }

 // ── TVA sur encaissement : déterminer le VatMode applicable ──
 // On parcourt les taux de TVA présents sur les lignes de la facture. Si AU MOINS UN
 // taux a un TaxRule en mode ENCAISSEMENT, on traite TOUTE la TVA comme différée
 // (approche conservatrice — une entreprise choisit en pratique un seul régime).
 // On stocke le montant différé dans invoice.vatDeferredAmount pour le basculer au
 // règlement (recordPayment).
 // Note : en autoliquidation, on force deferVat = false — la TVA n'est pas
 // collectée par l'émetteur, il n'y a donc rien à basculer 4438 → 443 au règlement.
 // Note v6-1 : en multi-taxes, on consulte les InvoiceLineTax (type VAT) en priorité
 // puis on fallback sur InvoiceLine.taxRate si la ligne n'a pas de taxes explicites.
 boolean deferVat = false;
 if (!reverseCharge && vatCollected.compareTo(BigDecimal.ZERO) > 0) {
 deferVat = shouldDeferVat(companyId, invoice, linesForEntry, lineTaxes);
 }
 BigDecimal vatDeferred = deferVat ? vatCollected : BigDecimal.ZERO;
 BigDecimal vatImmediate = vatCollected.subtract(vatDeferred);

 // Compte de TVA différée (4438) — résolu uniquement si une partie de la TVA est différée.
 Account vatDeferredAccount = null;
 if (vatDeferred.compareTo(BigDecimal.ZERO) > 0) {
 vatDeferredAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF,
 "VAT_DEFERRED_UNCOLLECTED",
 "VAT_DEFERRED_ACCOUNT_NOT_FOUND",
 "Aucun compte de TVA différée trouvé. Le TaxRule applicable est en mode " +
 "ENCAISSEMENT (TVA sur encaissement) — configurer un compte PASSIF marqué " +
 "taxMappingCode=\"VAT_DEFERRED_UNCOLLECTED\" dans le plan comptable, " +
 "ou utiliser le code 4438 (SYSCOHADA / PCG : « TVA sur factures émises non " +
 "encaissées »).",
 "443800", "4438", "4438");
 }

 // V8.2 Phase 3 — getOrCreateJournal retourne le journal existant ou le crée avec
 // le code/label par défaut du type (jamais d'exception pour les types standards).
 String journalCode = accountingEngineService.getOrCreateJournal(companyId,
 jo.accountant.accountingengine.entity.JournalType.VENTES).getCode();

 // ── R-F-validation v6-2 — RS sur ventes (Code Fiscal art. 156-1 Haïti) ──
 // Si la facture porte une retenue à la source (withholdingAmount > 0), l'écriture
 // comptable est modifiée comme suit :
 // D 411 Clients ............ netReceivable (au lieu de totalAmount)
 // D 442 État-RS à reverser . withholdingAmount (à reverser à la DGI pour le compte du client)
 // C 70x Ventes ............. subtotal (inchangé)
 // C 443 TVA collectée ...... taxAmount (inchangé — TVA/TCA/autres inchangés)
 //
 // Le compte 442 est le même que côté achats (PurchasingService) — résolution via
 // AccountResolver : taxMappingCode="WITHHOLDING_TAX", fallback "442000"/"442".
 // Le client paie à l'entreprise le netReceivable (TTC − RS), et reverse lui-même la
 // RS à la DGI pour le compte du fournisseur (qui la déclare et la comptabilise en 442).
 BigDecimal withholdingAmount = invoice.getWithholdingAmount() != null
 ? invoice.getWithholdingAmount() : BigDecimal.ZERO;
 boolean hasWithholding = withholdingAmount.compareTo(BigDecimal.ZERO) > 0;
 Account withholdingAccount = null;
 if (hasWithholding) {
 withholdingAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF, "WITHHOLDING_TAX",
 "WITHHOLDING_ACCOUNT_NOT_FOUND",
 "Une retenue à la source est applicable sur la facture de vente ("
 + withholdingAmount + " " + invoice.getCurrency()
 + ") mais aucun compte de passif marqué taxMappingCode=\"WITHHOLDING_TAX\" "
 + "(ou code 442000) n'est configuré. Créer un compte 442 « État — retenues à "
 + "la source » dans le plan comptable.",
 "442000", "442");
 }
 // Montant effectif à débiter sur le compte client :
 // - Si RS : netReceivable (= TTC − RS, ce que le client paie réellement)
 // - Sinon : totalAmount (comportement historique)
 BigDecimal clientDebit = hasWithholding
 ? (invoice.getNetReceivable() != null
 ? invoice.getNetReceivable() : invoice.getTotalAmount())
 : invoice.getTotalAmount();

 String invoiceLabel = "Facture " + invoice.getInvoiceNumber();
 List<LineDto> lines = new ArrayList<>();
 // Débit Client (netReceivable si RS, sinon total TTC)
 lines.add(new LineDto(clientAccount.getCode(), tp.getId(),
 clientDebit, null, invoiceLabel, List.of()));
 // ── R-F-validation v6-2 — Débit 442 « État-RS à reverser » (si RS applicable) ──
 if (hasWithholding) {
 lines.add(new LineDto(withholdingAccount.getCode(), null,
 withholdingAmount, null,
 "État-RS à reverser (art. 156-1) — " + invoiceLabel, List.of()));
 }
 // Crédit Ventes (subtotal HT)
 lines.add(new LineDto(salesAccount.getCode(), null,
 null, invoice.getSubtotal(), "Ventes — " + invoiceLabel, List.of()));
 if (reverseCharge) {
 // ── Crédit 447 « TDA autoliquidation » (au lieu de 443) ──
 // La TVA n'est pas collectée par l'émetteur — c'est le client qui l'auto-liquide
 // (Article 283, 2 nonies du CGI). Mention à porter sur la facture :
 // « Autoliquidation - Article 283, 2 nonies du CGI ».
 // Note v6-1 : seul vatCollected bascule en 447 (la TCA et autres taxes ne sont pas
 // concernées par l'autoliquidation intra-UE).
 if (vatCollected.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(reverseChargeAccount.getCode(), null,
 null, vatCollected,
 "TVA autoliquidation (reverse-charge) — " + invoiceLabel, List.of()));
 }
 // En reverse-charge, la TCA et autres taxes restent collectées normalement
 // (la TCA Haïti ne fait pas l'objet d'autoliquidation — l'autoliquidation est une
 // spécificité TVA intra-UE B2B). On les crédite donc dans leurs comptes respectifs.
 if (tcaCollected.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(tcaAccount.getCode(), null,
 null, tcaCollected, "TCA collectée — " + invoiceLabel, List.of()));
 }
 if (otherCollected.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(otherTaxAccount.getCode(), null,
 null, otherCollected, "Autres taxes collectées — " + invoiceLabel, List.of()));
 }
 } else {
 // Crédit TVA immédiate (443) — partie exigible à l'émission (mode DEBIT)
 if (vatImmediate.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(vatAccount.getCode(), null,
 null, vatImmediate, "TVA collectée — " + invoiceLabel, List.of()));
 }
 // Crédit TVA différée (4438) — partie exigible au paiement (mode ENCAISSEMENT)
 if (vatDeferred.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(vatDeferredAccount.getCode(), null,
 null, vatDeferred,
 "TVA sur factures émises non encaissées — " + invoiceLabel, List.of()));
 }
 // ── v6-1 — Crédit TCA collectée (446) ──
 if (tcaCollected.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(tcaAccount.getCode(), null,
 null, tcaCollected, "TCA collectée — " + invoiceLabel, List.of()));
 }
 // ── v6-1 — Crédit autres taxes collectées (448) ──
 if (otherCollected.compareTo(BigDecimal.ZERO) > 0) {
 lines.add(new LineDto(otherTaxAccount.getCode(), null,
 null, otherCollected, "Autres taxes collectées — " + invoiceLabel, List.of()));
 }
 }

 // Pour un avoir (CREDIT_NOTE), inverser débit/crédit
 if (invoice.getType() == InvoiceType.CREDIT_NOTE) {
 lines = lines.stream().map(l -> new LineDto(
 l.accountCode(), l.thirdPartyId(),
 l.credit(), l.debit(), // inversé
 l.description(), l.analyticalTags()
 )).toList();
 }

 CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
 journalCode, invoice.getIssueDate(),
 "Facture " + invoice.getInvoiceNumber() + " — " + tp.getName(),
 lines, JournalEntrySourceModule.INVOICING);

 String idempotencyKey = "invoicing-" + invoice.getId();
 JournalEntryResponse entry = accountingEngineService.createJournalEntry(
 companyId, idempotencyKey, entryReq);
 JournalEntryResponse posted = accountingEngineService.postJournalEntry(
 companyId, entry.id(), List.of());

 invoice.setJournalEntryId(posted.id());
 // Mémoriser le montant de TVA différée pour le bascule au règlement.
 // v6-1 : seule la TVA peut être différée (la TCA et les autres taxes sont sur débits).
 invoice.setVatDeferredAmount(vatDeferred);
 }

 /**
 * Détermine si la TVA de la facture doit être différée (mode ENCAISSEMENT) — .
 *
 * <p>Parcourt les taux de TVA présents sur les lignes de la facture et consulte le
 * {@link TaxRulePort} pour chacun. Si au moins un taux a un {@code TaxRule} actif en mode
 * {@link VatMode#ENCAISSEMENT}, on diffère la totalité de la TVA (une entreprise choisit en
 * pratique un seul régime — le cas mixte DEBIT/ENCAISSEMENT sur une même facture est rare).
 *
 * <p>Si aucun {@code TaxRule} n'est trouvé pour les taux présents (ex: tests sans seed de
 * règles fiscales), on retourne {@code false} (comportement historique DEBIT).
 *
 * <p>v6-1 : en multi-taxes par ligne, on consulte en priorité les {@link InvoiceLineTax}
 * (type VAT) pour récupérer les taux. Si une ligne n'a pas d'{@code InvoiceLineTax}, on
 * fallback sur {@code InvoiceLine.taxRate} (comportement historique). Cela permet de couvrir
 * les factures mixtes (certaines lignes en multi-taxes, d'autres en mono-taxe via taxRate).
 */
 private boolean shouldDeferVat(UUID companyId, SalesInvoice invoice,
 List<InvoiceLine> lines,
 List<InvoiceLineTax> lineTaxes) {
 LocalDate issueDate = invoice.getIssueDate() != null ? invoice.getIssueDate() : LocalDate.now();
 // 1. Parcourir les InvoiceLineTax de type VAT
 for (InvoiceLineTax lt : lineTaxes) {
 if (lt.getTaxType() != InvoiceLineTaxType.VAT) continue;
 BigDecimal rate = lt.getRate();
 if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) continue;
 var snapshot = taxRulePort.findActiveRuleByRate(companyId, rate, issueDate);
 if (snapshot.isPresent() && snapshot.get().vatMode() == VatMode.ENCAISSEMENT) {
 return true;
 }
 }
 // 2. Pour les lignes qui n'ont PAS d'InvoiceLineTax (fallback taxRate), consulter taxRate
 java.util.Set<UUID> linesWithMultiTax = lineTaxes.stream()
 .map(InvoiceLineTax::getInvoiceLineId)
 .collect(java.util.stream.Collectors.toSet());
 for (InvoiceLine line : lines) {
 if (linesWithMultiTax.contains(line.getId())) continue; // déjà traité ci-dessus
 BigDecimal rate = line.getTaxRate();
 if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) continue;
 var snapshot = taxRulePort.findActiveRuleByRate(companyId, rate, issueDate);
 if (snapshot.isPresent() && snapshot.get().vatMode() == VatMode.ENCAISSEMENT) {
 return true;
 }
 }
 return false;
 }

 /**
 * Parse un {@code taxType} String (depuis {@link TaxApplication}) en {@link InvoiceLineTaxType}.
 *
 * @throws ValidationException si la valeur n'est pas dans l'enum (normalement impossible —
 * la validation @Pattern sur le DTO rejette déjà les valeurs invalides, mais on
 * double-check par sécurité).
 */
 private InvoiceLineTaxType parseInvoiceLineTaxType(String taxType) {
 if (taxType == null) {
 throw new ValidationException("TAX_TYPE_REQUIRED",
 "taxType est obligatoire sur chaque TaxApplication");
 }
 try {
 return InvoiceLineTaxType.valueOf(taxType);
 } catch (IllegalArgumentException e) {
 throw new ValidationException("TAX_TYPE_INVALID",
 "taxType invalide : " + taxType + ". Valeurs acceptées : VAT, TCA, TURNOVER_TAX, EXCISE");
 }
 }

 /**
 * Construit un libellé lisible pour une {@link TaxApplication} (persisté sur l'InvoiceLineTax
 * pour l'affichage sur la facture PDF et l'audit). Si l'API cliente fournit un taxCode, on
 * l'utilise ; sinon on génère un libellé générique à partir du taxType et du rate.
 */
 private String buildTaxLabel(TaxApplication ta) {
 if (ta.taxCode() != null && !ta.taxCode().isBlank()) {
 return ta.taxCode() + " (" + ta.taxType() + " " + ta.rate() + "%)";
 }
 return ta.taxType() + " " + ta.rate() + "%";
 }

 // --- Règlement ---

 @Transactional
 public InvoiceResponse recordPayment(UUID companyId, UUID invoiceId, RecordPaymentRequest req) {
 SalesInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() == InvoiceStatus.DRAFT) {
 throw new ConflictException("INVOICE_NOT_ISSUED",
 "Une facture DRAFT ne peut pas recevoir de règlement");
 }
 if (invoice.getStatus() == InvoiceStatus.VOID) {
 throw new ConflictException("INVOICE_VOID",
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
 invoice.setStatus(InvoiceStatus.PAID);
 } else {
 invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
 }

 // ── TVA sur encaissement : bascule 4438 → 443 au règlement ──
 // Si la facture a été émise avec une TVA différée (vatDeferredAmount > 0, mode
 // ENCAISSEMENT), on poste une écriture de bascule au prorata du paiement :
 // Débit 4438 (TVA sur factures émises non encaissées)
 // Crédit 443 (TVA collectée)
 // Le montant basculé = min(vatDeferredAmount, payment × taxAmount / totalAmount).
 // Math correcte pour les paiements partiels successifs (voir dans la javadoc
 // de generateInvoiceEntry) tant que la facture suit un seul régime.
 if (invoice.getVatDeferredAmount() != null
 && invoice.getVatDeferredAmount().compareTo(BigDecimal.ZERO) > 0) {
 BigDecimal vatToTransfer = computeVatSettlementAmount(invoice, req.amount());
 if (vatToTransfer.compareTo(BigDecimal.ZERO) > 0) {
 UUID settlementEntryId = postVatSettlementEntry(companyId, invoice, vatToTransfer);
 invoice.setVatSettlementEntryId(settlementEntryId);
 invoice.setVatDeferredAmount(
 invoice.getVatDeferredAmount().subtract(vatToTransfer));
 }
 }

 // Note : le lettrage (Phase 7) n'est pas automatisé ici — l'utilisateur lettre
 // manuellement la facture et le règlement via third-parties/lettrage.

 invoiceRepository.save(invoice);
 LOG.info("Règlement enregistré : invoice={} amount={} newStatus={} vatBascule={}",
 invoice.getId(), req.amount(), invoice.getStatus(),
 invoice.getVatDeferredAmount() != null
 ? invoice.getTaxAmount().subtract(invoice.getVatDeferredAmount())
 : BigDecimal.ZERO);
 return loadInvoiceResponse(companyId, invoice.getId());
 }

 /**
 * Calcule le montant de TVA à basculer du 4438 vers le 443 lors d'un règlement — .
 *
 * <p>Formule : {@code min(vatDeferredAmount, paymentAmount × taxAmount / totalAmount)}.
 *
 * <p>Le cap par {@code vatDeferredAmount} garantit qu'on ne bascule jamais plus que le
 * montant restant à différer (évite les bascules négatives sur le dernier règlement).
 */
 private BigDecimal computeVatSettlementAmount(SalesInvoice invoice, BigDecimal paymentAmount) {
 BigDecimal taxAmount = invoice.getTaxAmount();
 BigDecimal totalAmount = invoice.getTotalAmount();
 if (taxAmount == null || totalAmount == null
 || totalAmount.compareTo(BigDecimal.ZERO) == 0) {
 return BigDecimal.ZERO;
 }
 // Prorata du paiement par rapport au total TTC, appliqué à la TVA totale.
 BigDecimal prorata = paymentAmount.multiply(taxAmount)
 .divide(totalAmount, 4, java.math.RoundingMode.HALF_UP);
 BigDecimal remaining = invoice.getVatDeferredAmount();
 return prorata.compareTo(remaining) <= 0 ? prorata : remaining;
 }

 /**
 * Poste l'écriture de bascule TVA (4438 → 443) au règlement — .
 *
 * <p>Écriture (sens normal pour une facture STANDARD) :
 * <ul>
 * <li>Débit {@code 4438} (TVA sur factures émises non encaissées) — sortie du compte d'attente</li>
 * <li>Crédit {@code 443} (TVA collectée) — entrée dans le compte exigible</li>
 * </ul>
 *
 * <p>Pour un avoir (CREDIT_NOTE), le sens est inversé : Débit 443 / Crédit 4438 (la TVA
 * « collectée » négative sort du 443 et retourne dans le 4438, en miroir de l'écriture
 * d'émission inversée).
 *
 * <p>Journal utilisé : OD (Opérations Diverses). Le journal VT (Ventes) est réservé à
 * l'émission des factures ; la bascule TVA est une opération de régularisation.
 *
 * @return l'ID de l'écriture comptable postée
 */
 private UUID postVatSettlementEntry(UUID companyId, SalesInvoice invoice,
 BigDecimal vatToTransfer) {
 Account vatCollectedAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF, "VAT_COLLECTED",
 "VAT_ACCOUNT_NOT_FOUND",
 "Aucun compte de TVA collectée trouvé pour la bascule 4438 → 443.",
 "443000", "443");
 Account vatDeferredAccount = accountResolver.resolveOrThrow(
 companyId, jo.accountant.core.framework.ReportingClass.PASSIF,
 "VAT_DEFERRED_UNCOLLECTED",
 "VAT_DEFERRED_ACCOUNT_NOT_FOUND",
 "Aucun compte de TVA différée trouvé pour la bascule 4438 → 443.",
 "443800", "4438", "4438");

 // V8.2 Phase 3 — getOrCreateJournal ne lève jamais pour les types standards.
 // Le fallback OD→VT n'est plus nécessaire.
 String journalCode = accountingEngineService.getOrCreateJournal(companyId,
 jo.accountant.accountingengine.entity.JournalType.OD).getCode();

 String label = "Bascule TVA encaissement — Facture " + invoice.getInvoiceNumber();
 List<LineDto> lines = new ArrayList<>();
 if (invoice.getType() == InvoiceType.CREDIT_NOTE) {
 // Avoir : sens inversé (miroir de l'émission inversée).
 // Débit 443 (TVA collectée) — la TVA négative sort du compte exigible
 // Crédit 4438 (TVA différée) — retour dans le compte d'attente
 lines.add(new LineDto(vatCollectedAccount.getCode(), null,
 vatToTransfer, null, label, List.of())); // Débit 443
 lines.add(new LineDto(vatDeferredAccount.getCode(), null,
 null, vatToTransfer, label, List.of())); // Crédit 4438
 } else {
 // Facture standard : bascule du compte d'attente vers le compte exigible.
 // Débit 4438 (TVA sur factures émises non encaissées) — sortie de l'attente
 // Crédit 443 (TVA collectée) — entrée dans l'exigible
 lines.add(new LineDto(vatDeferredAccount.getCode(), null,
 vatToTransfer, null, label, List.of())); // Débit 4438
 lines.add(new LineDto(vatCollectedAccount.getCode(), null,
 null, vatToTransfer, label, List.of())); // Crédit 443
 }

 LocalDate entryDate = invoice.getIssueDate() != null
 ? invoice.getIssueDate() : LocalDate.now();
 CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
 journalCode, entryDate, label, lines, JournalEntrySourceModule.INVOICING);

 // Idempotency key suffixée par le montant + timestamp pour autoriser plusieurs
 // règlements partiels sur la même facture (chacun génère sa propre écriture de bascule).
 String idempotencyKey = "invoicing-vat-settlement-" + invoice.getId()
 + "-" + vatToTransfer.toPlainString()
 + "-" + System.currentTimeMillis();
 JournalEntryResponse entry = accountingEngineService.createJournalEntry(
 companyId, idempotencyKey, entryReq);
 JournalEntryResponse posted = accountingEngineService.postJournalEntry(
 companyId, entry.id(), List.of());
 LOG.info("Bascule TVA encaissement postée : invoice={} montant={} (4438 → 443)",
 invoice.getId(), vatToTransfer);
 return posted.id();
 }

 // --- Avoir ---

 @Transactional
 public InvoiceResponse createCreditNote(UUID companyId, UUID originalInvoiceId,
 CreateInvoiceRequest req) {
 SalesInvoice original = loadInvoice(companyId, originalInvoiceId);
 if (original.getStatus() != InvoiceStatus.ISSUED
 && original.getStatus() != InvoiceStatus.PARTIALLY_PAID
 && original.getStatus() != InvoiceStatus.PAID) {
 throw new ConflictException("ORIGINAL_NOT_ISSUED",
 "L'original doit être ISSUED, PARTIALLY_PAID ou PAID pour créer un avoir");
 }

 // ── Audit v4.7 §4.2 Finding MOYENNE — FIX anti-fraude avoirs ──
 // Sans cette vérification, un BOOKKEEPER pouvait créer N avoirs pour la même facture —
 // chacun à 100% du montant — et rembourser le client N× le montant de la facture.
 // Désormais, on calcule le total des avoirs déjà émis et on refuse si le nouveau
 // avoir ferait dépasser le total de la facture originale.
 // Tolérance d'arrondi : 0.01 (pour éviter les faux positifs dus aux arrondis monétaires).
 List<SalesInvoice> existingCreditNotes = invoiceRepository
 .findByCompanyIdAndTypeAndCreditNoteForInvoiceId(
 companyId, jo.accountant.invoicing.entity.InvoiceType.CREDIT_NOTE, original.getId());
 BigDecimal alreadyCredited = existingCreditNotes.stream()
 .filter(cn -> cn.getStatus() != InvoiceStatus.VOID) // exclure les avoirs VOID
 .map(SalesInvoice::getTotalAmount)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal maxCreditNoteAmount = original.getTotalAmount().subtract(alreadyCredited);
 if (maxCreditNoteAmount.compareTo(new BigDecimal("0.01")) < 0) {
 throw new ConflictException("CREDIT_NOTE_EXCEEDS_ORIGINAL",
 "Le total des avoirs déjà émis (" + alreadyCredited + " " + original.getCurrency()
 + ") atteint ou dépasse le total de la facture originale ("
 + original.getTotalAmount() + " " + original.getCurrency()
 + "). Impossible de créer un nouvel avoir. Pour annuler un avoir existant, "
 + "utilisez voidInvoice() puis créez un nouvel avoir.");
 }
 LOG.info("Avoir autorisé : original={}, déjà crédité={}, montant max disponible={}",
 original.getTotalAmount(), alreadyCredited, maxCreditNoteAmount);

 // Créer l'avoir avec type=CREDIT_NOTE et creditNoteForInvoiceId
 // En Phase 12 simplifié : on crée directement en DRAFT puis on issue
 // L'utilisateur doit appeler issueInvoice séparément
 // Pour simplifier le test, on force le type et le creditNoteForInvoiceId
 SalesInvoice creditNote = new SalesInvoice();
 creditNote.setCompanyId(companyId);
 creditNote.setThirdPartyId(original.getThirdPartyId());
 creditNote.setType(InvoiceType.CREDIT_NOTE);
 creditNote.setStatus(InvoiceStatus.DRAFT);
 creditNote.setCurrency(original.getCurrency());
 creditNote.setIssueDate(LocalDate.now());
 creditNote.setDueDate(LocalDate.now());
 creditNote.setCreditNoteForInvoiceId(original.getId());
 creditNote.setPaidAmount(BigDecimal.ZERO);

 creditNote.setSubtotal(BigDecimal.ZERO);
 creditNote.setTaxAmount(BigDecimal.ZERO);
 creditNote.setTotalAmount(BigDecimal.ZERO);
 SalesInvoice savedCreditNote = invoiceRepository.save(creditNote);

 // Copier les lignes de l'original
 BigDecimal subtotal = BigDecimal.ZERO;
 BigDecimal taxAmount = BigDecimal.ZERO;
 List<InvoiceLine> originalLines = lineRepository.findByInvoiceIdOrderByCreatedAt(original.getId());
 // v6-1 — batch loading des InvoiceLineTax de l'original pour les cloner dans l'avoir
 java.util.List<UUID> origLineIds = originalLines.stream().map(InvoiceLine::getId).toList();
 java.util.Map<UUID, java.util.List<InvoiceLineTax>> origTaxesByLine = origLineIds.isEmpty()
 ? java.util.Map.of()
 : lineTaxRepository.findByInvoiceLineIdInOrderByDisplayOrderAscIdAsc(origLineIds).stream()
 .collect(java.util.stream.Collectors.groupingBy(InvoiceLineTax::getInvoiceLineId));

 for (InvoiceLine origLine : originalLines) {
 InvoiceLine cnLine = new InvoiceLine();
 cnLine.setCompanyId(companyId);
 cnLine.setInvoiceId(savedCreditNote.getId());
 cnLine.setDescription("Avoir — " + origLine.getDescription());
 cnLine.setQuantity(origLine.getQuantity());
 cnLine.setUnitPrice(origLine.getUnitPrice());
 cnLine.setDiscountPercent(origLine.getDiscountPercent());
 cnLine.setTaxRate(origLine.getTaxRate());
 cnLine.setItemId(origLine.getItemId());
 cnLine.setTimesheetEntryId(origLine.getTimesheetEntryId());
 cnLine.setLineTotalHt(origLine.getLineTotalHt());
 cnLine.setLineTotalTax(origLine.getLineTotalTax());
 subtotal = subtotal.add(origLine.getLineTotalHt());
 taxAmount = taxAmount.add(origLine.getLineTotalTax());
 lineRepository.save(cnLine);

 // v6-1 — cloner les InvoiceLineTax de l'original vers l'avoir
 java.util.List<InvoiceLineTax> origLineTaxes = origTaxesByLine.getOrDefault(origLine.getId(), java.util.List.of());
 for (InvoiceLineTax origTax : origLineTaxes) {
 InvoiceLineTax cnTax = new InvoiceLineTax();
 cnTax.setInvoiceLineId(cnLine.getId());
 cnTax.setTaxType(origTax.getTaxType());
 cnTax.setTaxCode(origTax.getTaxCode());
 cnTax.setTaxLabel(origTax.getTaxLabel());
 cnTax.setRate(origTax.getRate());
 cnTax.setTaxableBase(origTax.getTaxableBase());
 cnTax.setTaxAmount(origTax.getTaxAmount());
 cnTax.setDisplayOrder(origTax.getDisplayOrder());
 lineTaxRepository.save(cnTax);
 }
 }

 savedCreditNote.setSubtotal(subtotal);
 savedCreditNote.setTaxAmount(taxAmount);
 savedCreditNote.setTotalAmount(subtotal.add(taxAmount));

 // ── R-F-validation v6-2 — Propager la RS de l'original à l'avoir ──
 // Si la facture originale portait une retenue à la source, l'avoir doit également porter
 // la RS (symétrique) : la RS reversée à la DGI pour l'original sera compensée par la RS
 // négative de l'avoir. L'écriture comptable de l'avoir (générée par generateInvoiceEntry)
 // inversera débit/crédit — le débit 442 de l'original devient un crédit 442 sur l'avoir.
 if (original.getWithholdingAmount() != null
 && original.getWithholdingAmount().compareTo(BigDecimal.ZERO) > 0) {
 BigDecimal cnWithholdingRate = original.getWithholdingRate() != null
 ? original.getWithholdingRate() : BigDecimal.ZERO;
 // RS calculée sur le subtotal de l'avoir (qui est une copie du subtotal original).
 BigDecimal cnWithholdingAmount = subtotal.multiply(cnWithholdingRate)
 .divide(HUNDRED, 6, RoundingMode.HALF_UP);
 cnWithholdingAmount = roundingService.round(savedCreditNote.getCurrency(),
 cnWithholdingAmount);
 BigDecimal cnTotal = savedCreditNote.getTotalAmount() != null
 ? savedCreditNote.getTotalAmount() : BigDecimal.ZERO;
 BigDecimal cnNetReceivable = cnTotal.subtract(cnWithholdingAmount);
 savedCreditNote.setWithholdingRate(cnWithholdingRate);
 savedCreditNote.setWithholdingAmount(cnWithholdingAmount);
 savedCreditNote.setNetReceivable(cnNetReceivable);
 savedCreditNote.setWithholdingRuleId(original.getWithholdingRuleId());
 LOG.info("RS propagée sur avoir {} : rate={}% amount={} netReceivable={} (original={})",
 savedCreditNote.getId(), cnWithholdingRate, cnWithholdingAmount,
 cnNetReceivable, originalInvoiceId);
 }

 invoiceRepository.save(savedCreditNote);

 LOG.info("Avoir créé : id={} original={}", savedCreditNote.getId(), originalInvoiceId);
 return loadInvoiceResponse(companyId, savedCreditNote.getId());
 }

 // --- PDF ---

 @Transactional
 public byte[] getInvoicePdf(UUID companyId, UUID invoiceId) {
 SalesInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() == InvoiceStatus.DRAFT) {
 throw new ConflictException("INVOICE_NOT_ISSUED",
 "Une facture DRAFT ne peut pas être générée en PDF");
 }

 // Préparer les variables pour le template Thymeleaf
 // Audit v4.7 §6.2 — defense-in-depth : filtrer par companyId
 ThirdParty tp = thirdPartyRepository.findById(invoice.getThirdPartyId())
 .filter(t -> t.getCompanyId().equals(companyId))
 .orElse(null);
 Map<String, Object> variables = new HashMap<>();
 variables.put("invoiceNumber", invoice.getInvoiceNumber());
 variables.put("issueDate", invoice.getIssueDate() != null ? invoice.getIssueDate().toString() : "");
 variables.put("dueDate", invoice.getDueDate() != null ? invoice.getDueDate().toString() : "");
 variables.put("clientName", tp != null ? tp.getName() : "");
 variables.put("totalAmount", invoice.getTotalAmount().toString());
 variables.put("subtotal", invoice.getSubtotal().toString());
 variables.put("taxAmount", invoice.getTaxAmount().toString());

 // ── Mention légale autoliquidation (reverse-charge intra-UE B2B) ──
 // Si la facture a été émise en autoliquidation, on expose le flag et la mention
 // légale au template Thymeleaf (le template affichera « Autoliquidation - Article
 // 283, 2 nonies du CGI » sur la facture PDF).
 variables.put("isReverseCharge", invoice.isReverseCharge());
 if (invoice.isReverseCharge()) {
 variables.put("reverseChargeMention",
 "Autoliquidation - Article 283, 2 nonies du CGI");
 } else {
 variables.put("reverseChargeMention", "");
 }

 // Lignes
 List<Map<String, Object>> lineMaps = new ArrayList<>();
 for (InvoiceLine line : lineRepository.findByInvoiceIdOrderByCreatedAt(invoice.getId())) {
 Map<String, Object> m = new HashMap<>();
 m.put("description", line.getDescription());
 m.put("quantity", line.getQuantity().toString());
 m.put("unitPrice", line.getUnitPrice().toString());
 m.put("amount", line.getLineTotalHt().toString());
 lineMaps.add(m);
 }
 variables.put("lines", lineMaps);

 // Générer le PDF via document-generation
 jo.accountant.documentgeneration.dto.GeneratedDocumentResponse doc =
 documentGenerationService.generateDocument(
 companyId,
 jo.accountant.documentgeneration.entity.GeneratedDocumentType.INVOICE,
 invoice.getId(),
 variables);

 return documentGenerationService.getDocumentContent(companyId, invoice.getId());
 }

 /**
 * Génère le XML Factur-X BASICWL pour une facture — audit v4.7 §4.1 .
 *
 * <p>Conformité Loi 2023-314 (facturation électronique obligatoire B2B France depuis le
 * 1er septembre 2026). Le XML est conforme EN 16931 (Cross Industry Invoice D16B, profil
 * BASICWL). Contient SellerTradeParty + BuyerTradeParty (SIRET, TVA intracommunautaire),
 * ApplicableTradeTax par taux de TVA, SpecifiedTradeSettlementHeaderMonetarySummation.
 *
 * <p>Limitation v4.7.2 : le XML est servi séparément du PDF. L'embarquement PDF/A-3
 * (attachment dans le PDF) sera finalisé en v4.8 avec openpdf + PDF/A-3 attachment API.
 *
 * @return bytes du XML UTF-8
 * @throws ConflictException si la facture est DRAFT
 */
 @Transactional(readOnly = true)
 public byte[] getInvoiceFacturX(UUID companyId, UUID invoiceId) {
 SalesInvoice invoice = loadInvoice(companyId, invoiceId);
 if (invoice.getStatus() == InvoiceStatus.DRAFT) {
 throw new ConflictException("INVOICE_NOT_ISSUED",
 "Une facture DRAFT ne peut pas être exportée en Factur-X");
 }

 // Charger le tiers client (avec defense-in-depth)
 ThirdParty tp = thirdPartyRepository.findById(invoice.getThirdPartyId())
 .filter(t -> t.getCompanyId().equals(companyId))
 .orElseThrow(() -> new NotFoundException("ThirdParty", invoice.getThirdPartyId()));

 // Agréger les lignes par taux de TVA pour ApplicableTradeTax
 List<InvoiceLine> lines = lineRepository.findByInvoiceIdOrderByCreatedAt(invoice.getId());
 java.util.Map<java.math.BigDecimal, FacturXExporter.TaxBreakdown> taxByRate = new java.util.TreeMap<>();
 for (InvoiceLine line : lines) {
 java.math.BigDecimal rate = line.getTaxRate() != null ? line.getTaxRate() : java.math.BigDecimal.ZERO;
 FacturXExporter.TaxBreakdown existing = taxByRate.get(rate);
 if (existing == null) {
 taxByRate.put(rate, new FacturXExporter.TaxBreakdown(
 rate,
 line.getLineTotalHt() != null ? line.getLineTotalHt() : java.math.BigDecimal.ZERO,
 line.getLineTotalTax() != null ? line.getLineTotalTax() : java.math.BigDecimal.ZERO));
 } else {
 taxByRate.put(rate, new FacturXExporter.TaxBreakdown(
 rate,
 existing.taxableBase().add(line.getLineTotalHt() != null ? line.getLineTotalHt() : java.math.BigDecimal.ZERO),
 existing.taxAmount().add(line.getLineTotalTax() != null ? line.getLineTotalTax() : java.math.BigDecimal.ZERO)));
 }
 }

 // Construire les DTO pour FacturXExporter — utiliser les champs légaux réels
 // (SIRET, TVA intracommunautaire, NIF, adresse) depuis Company et ThirdParty
 // (audit v4.7 §4.2 + Lot B NIF Haïti sérialisé comme SpecifiedTaxRegistration).
 jo.accountant.company.entity.Company company = companyRepository.findById(companyId)
 .orElseThrow(() -> new NotFoundException("Company", companyId));
 FacturXExporter.TradeParty seller = new FacturXExporter.TradeParty(
 companyId.toString(),
 company.getName(),
 company.getVatNumber(),
 company.getSiret(),
 company.getAddress(),
 company.getCountry(),
 company.getNif() // Lot B NIF séparé pour conformité DGI Haïti
 );
 FacturXExporter.TradeParty buyer = new FacturXExporter.TradeParty(
 tp.getId().toString(),
 tp.getName(),
 tp.getVatNumber(),
 tp.getSiret(),
 tp.getAddress(),
 null  // country du tiers non persisté — Non implémenté : tp.getNif() (Lot B NIF séparé pour conformité DGI Haïti)
 );

 FacturXExporter.FacturXInvoice facturX = new FacturXExporter.FacturXInvoice(
 invoice.getInvoiceNumber(),
 invoice.getIssueDate(),
 invoice.getIssueDate(), // deliveryDate = issueDate par défaut
 invoice.getCurrency(),
 seller,
 buyer,
 null, // buyerReference
 invoice.getSubtotal(),
 invoice.getTaxAmount(),
 invoice.getTotalAmount(),
 new java.util.ArrayList<>(taxByRate.values())
 );

 LOG.info("Factur-X BASICWL généré pour facture {} (company {})", invoice.getInvoiceNumber(), companyId);
 return facturXExporter.exportFacturXBasicWL(facturX);
 }

 /**
 * Génère un PDF/A-3 avec le XML Factur-X embarqué (audit v4.7 §4.1 ).
 *
 * <p>Combinaison des deux endpoints existants :
 * <ol>
 * <li>Génère le PDF visuel (via {@link #getInvoicePdf}) — rendu Thymeleaf + openhtmltopdf.</li>
 * <li>Génère le XML Factur-X BASICWL (via {@link #getInvoiceFacturX}).</li>
 * <li>Embarque le XML dans le PDF via {@link FacturXExporter#embedFacturXInPdf}.</li>
 * </ol>
 *
 * <p>Le résultat est un {@code factur-x.pdf} unique conforme à la spec Factur-X / EN 16931 :
 * lisible par les humains (rendu visuel) ET par les machines (XML parsé par le PPF/DGFiP).
 *
 * <p><b>v8-2</b> : l'embarquement est implémenté avec openpdf 1.4.2 (dépendance ajoutée
 * dans {@code invoicing/build.gradle.kts}). Le PDF produit est best-effort PDF/A-3
 * (XMP pdfaid:part=3/B + /AF + /AFRelationship=/Data). Pour une conformité PDF/A-3 strict
 * certifiable (signature qualifiée, archivage légal long terme), basculer sur iText 7 +
 * pdfa-io (AGPL/commercial). En cas d'échec openpdf (PDF source corrompu, I/O), la méthode
 * lève {@link IllegalStateException} — l'appelant (controller) retourne un 500 avec un
 * header {@code X-Error-Reason=PDF_A3_FACTURX_EMBEDDING_FAILED}.
 *
 * @return bytes du PDF/A-3 avec le XML Factur-X embarqué
 * @throws ConflictException si la facture est DRAFT
 * @throws IllegalStateException si openpdf échoue à embarquer le XML dans le PDF source
 */
 @Transactional(readOnly = true)
 public byte[] getInvoiceFacturXPdf(UUID companyId, UUID invoiceId) {
 // Note : getInvoicePdf est @Transactional (écrit le PDF dans document-generation) ; on l'appelle
 // ici en lecture seule conceptuelle — l'effet de bord est la persistance du document PDF.
 byte[] pdfBytes = getInvoicePdf(companyId, invoiceId);
 byte[] xmlBytes = getInvoiceFacturX(companyId, invoiceId);
 byte[] pdfA3 = facturXExporter.embedFacturXInPdf(pdfBytes, xmlBytes);
 LOG.info("PDF/A-3 Factur-X généré pour facture {} (company {}) : {} octets",
 invoiceId, companyId, pdfA3.length);
 return pdfA3;
 }

 // --- Lectures ---

 @Transactional(readOnly = true)
 public InvoiceResponse loadInvoiceResponse(UUID companyId, UUID invoiceId) {
 SalesInvoice invoice = loadInvoice(companyId, invoiceId);
 List<InvoiceLine> lines = lineRepository.findByInvoiceIdOrderByCreatedAt(invoice.getId());
 // v6-1 — batch loading des InvoiceLineTax (1 SELECT au lieu de N si on faisait par ligne)
 java.util.List<UUID> lineIds = lines.stream().map(InvoiceLine::getId).toList();
 java.util.List<InvoiceLineTax> allLineTaxes = lineIds.isEmpty()
 ? java.util.List.of()
 : lineTaxRepository.findByInvoiceLineIdInOrderByDisplayOrderAscIdAsc(lineIds);
 // Grouper par invoiceLineId pour éviter le N+1 par ligne
 java.util.Map<UUID, java.util.List<InvoiceLineTax>> taxesByLine = allLineTaxes.stream()
 .collect(java.util.stream.Collectors.groupingBy(InvoiceLineTax::getInvoiceLineId));

 List<InvoiceResponse.LineResponse> lineResponses = lines.stream()
 .map(l -> {
 java.util.List<InvoiceLineTax> lineTaxes = taxesByLine.getOrDefault(l.getId(), java.util.List.of());
 java.util.List<InvoiceResponse.TaxApplicationResponse> taxResponses = lineTaxes.stream()
 .map(lt -> new InvoiceResponse.TaxApplicationResponse(
 lt.getTaxType() != null ? lt.getTaxType().name() : null,
 lt.getTaxCode(),
 lt.getTaxLabel(),
 lt.getRate(),
 lt.getTaxableBase(),
 lt.getTaxAmount()))
 .toList();
 return new InvoiceResponse.LineResponse(
 l.getId(), l.getDescription(), l.getQuantity(), l.getUnitPrice(),
 l.getDiscountPercent(), l.getTaxRate(), l.getItemId(),
 l.getTimesheetEntryId(), l.getLineTotalHt(), l.getLineTotalTax(),
 taxResponses);
 })
 .toList();
 // E-9 M7 : enrichir la réponse avec le nom du tiers (résolu depuis ThirdPartyRepository)
 // Audit v4.7 §6.2 — defense-in-depth : filtrer par companyId pour ne pas fuiter le nom
 // d'un tiers d'une autre company en cas de corruption de FK.
 String tpName = "";
 try {
 ThirdParty tp = thirdPartyRepository.findById(invoice.getThirdPartyId())
 .filter(t -> t.getCompanyId().equals(companyId))
 .orElse(null);
 if (tp != null) tpName = tp.getName();
 } catch (Exception e) {
 // best-effort — si le tiers n'est pas trouvable, on renvoie une chaîne vide
 }
 // ── R-F-validation v6-2 — résoudre le code de la WithholdingRule pour la réponse ──
 // Rétro-lookup depuis invoice.withholdingRuleId via le port hexagonal. Best-effort :
 // si la règle a été supprimée ou si le port ne trouve pas, on renvoie null.
 String withholdingRuleCode = null;
 if (invoice.getWithholdingRuleId() != null) {
 try {
 withholdingRuleCode = withholdingRulePort
 .findRuleById(invoice.getWithholdingRuleId())
 .map(jo.accountant.core.port.WithholdingRulePort.WithholdingRuleSnapshot::code)
 .orElse(null);
 } catch (Exception e) {
 // best-effort — on log et renvoie null (la facture reste consultable)
 LOG.warn("Rétro-lookup WithholdingRule échoué pour invoice {} ruleId {} : {}",
 invoice.getId(), invoice.getWithholdingRuleId(), e.getMessage());
 }
 }

 return new InvoiceResponse(
 invoice.getId(), invoice.getCompanyId(), invoice.getThirdPartyId(),
 tpName,
 invoice.getType(), invoice.getStatus(), invoice.getInvoiceNumber(),
 invoice.getIssueDate(), invoice.getDueDate(), invoice.getCurrency(),
 invoice.getSubtotal(), invoice.getTaxAmount(), invoice.getTotalAmount(),
 invoice.getPaidAmount(), invoice.getBalanceDue(),
 invoice.getCreditNoteForInvoiceId(), invoice.getJournalEntryId(),
 lineResponses, invoice.getCreatedAt(), invoice.getUpdatedAt(),
 invoice.isReverseCharge(),
 invoice.getWithholdingRate(),
 invoice.getWithholdingAmount(),
 invoice.getNetReceivable(),
 withholdingRuleCode);
 }

 @Transactional(readOnly = true)
 public List<InvoiceResponse> listInvoices(UUID companyId) {
 // Audit v4.7 §7.2 hard cap 200 pour empêcher l'OOM sur entreprises matures.
 // Sans ce cap, une entreprise avec 5 ans d'historique (potentiellement des milliers de
 // factures) pouvait saturer la heap côté backend ET mobile. Les clients qui ont besoin
 // de plus de 200 factures doivent utiliser la variante paginée (Pageable) ou filtrer par
 // exercice fiscal via listInvoices(companyId, fiscalYearId).
 List<InvoiceResponse> all = invoiceRepository.findByCompanyIdOrderByIssueDateDesc(companyId).stream()
 .map(i -> loadInvoiceResponse(companyId, i.getId()))
 .toList();
 if (all.size() > INVOICE_LIST_HARD_CAP) {
 LOG.warn("listInvoices truncated for company {} : {} invoices found, returning first {} only. "
 + "Use listInvoicesPaged(companyId, Pageable) or filter by fiscalYearId for full access.",
 companyId, all.size(), INVOICE_LIST_HARD_CAP);
 return all.subList(0, INVOICE_LIST_HARD_CAP);
 }
 return all;
 }

 /**
 * Liste les factures filtrées par exercice fiscal — correction 2026-07-26.
 *
 * <p>Avant, le mobile récupérait TOUTES les factures de l'entreprise puis filtrait
 * côté client par date. Sur une entreprise avec 5 ans d'historique (potentiellement
 * des milliers de factures), cela causait des timeout/OOM côté mobile.
 *
 * @param fiscalYearId UUID de l'exercice fiscal (nullable = pas de filtre)
 */
 @Transactional(readOnly = true)
 public List<InvoiceResponse> listInvoices(UUID companyId, java.util.UUID fiscalYearId) {
 if (fiscalYearId == null) return listInvoices(companyId);
 // Récupère l'exercice fiscal pour obtenir ses bornes de dates
 jo.accountant.accountingengine.entity.FiscalYear fy =
 fiscalYearRepository.findById(fiscalYearId)
 .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException(
 "FiscalYear", fiscalYearId));
 // Audit v4.7 §6.2 IDOR CRITICAL : sans ce guard, un attaquant pouvait énumérer
 // les UUID de FiscalYear d'autres entreprises (404 vs 200 + fuite des dates startDate/endDate).
 // Le pattern est reproductible : expenses et purchasing utilisent déjà
 // accountingEngineService.resolveFiscalYear(companyId, fiscalYearId) qui fait le check.
 if (!fy.getCompanyId().equals(companyId)) {
 throw new jo.accountant.core.exception.NotFoundException("FiscalYear", fiscalYearId);
 }
 java.time.LocalDate from = fy.getStartDate();
 java.time.LocalDate to = fy.getEndDate();
 return invoiceRepository.findByCompanyIdOrderByIssueDateDesc(companyId).stream()
 .filter(i -> !i.getIssueDate().isBefore(from) && !i.getIssueDate().isAfter(to))
 .map(i -> loadInvoiceResponse(companyId, i.getId()))
 .toList();
 }

 /**
 * Liste paginée des factures — .
 *
 * <p>Variante paginée de {@link #listInvoices(UUID)} — utilise le {@code Page<>} du repository
 * pour ne charger qu'une page à la fois (au lieu du hard cap 200 côté service). Filtre optionnel
 * par exercice fiscal via l'endpoint dédié qui appelle cette méthode.
 *
 * @param companyId identifiant de l'entreprise
 * @param fiscalYearId filtre optionnel par exercice (null = toutes les factures)
 * @param pageable paramètres de pagination (page, size — size cappé à 200 côté controller)
 * @return page de {@link InvoiceResponse} (avec lignes + nom du tiers résolus)
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<InvoiceResponse> listInvoices(
 UUID companyId, java.util.UUID fiscalYearId,
 org.springframework.data.domain.Pageable pageable) {
 org.springframework.data.domain.Page<SalesInvoice> page;
 if (fiscalYearId != null) {
 // IDOR guard — vérifier que la FiscalYear appartient à la company avant de l'utiliser.
 jo.accountant.accountingengine.entity.FiscalYear fy =
 fiscalYearRepository.findById(fiscalYearId)
 .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException(
 "FiscalYear", fiscalYearId));
 if (!fy.getCompanyId().equals(companyId)) {
 throw new jo.accountant.core.exception.NotFoundException("FiscalYear", fiscalYearId);
 }
 page = invoiceRepository.findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
 companyId, fy.getStartDate(), fy.getEndDate(), pageable);
 } else {
 page = invoiceRepository.findByCompanyIdOrderByIssueDateDesc(companyId, pageable);
 }
 return page.map(i -> loadInvoiceResponse(companyId, i.getId()));
 }

 // --- Helpers ---

 private SalesInvoice loadInvoice(UUID companyId, UUID invoiceId) {
 SalesInvoice invoice = invoiceRepository.findById(invoiceId)
 .orElseThrow(() -> new NotFoundException("SalesInvoice", invoiceId));
 if (!invoice.getCompanyId().equals(companyId)) {
 throw new NotFoundException("SalesInvoice", invoiceId);
 }
 return invoice;
 }

 // =========================================================================
 // V7-8 — Keyset pagination sur sales-invoices
 // =========================================================================

 /**
 * V7-8 — Liste paginée par keyset (curseur) des factures d'une entreprise.
 *
 * <p>Pour les entreprises avec un volume important (Caribbean Textiles 50K+ factures/an),
 * la pagination OFFSET standard dégrade sur les pages profondes (PostgreSQL doit scanner
 * et trier tous les enregistrements précédents). La pagination keyset conserve une latence
 * constante (~10ms) quelle que soit la profondeur.
 *
 * @param companyId identifiant de l'entreprise
 * @param afterIssueDate date curseur (issueDate du dernier élément de la page précédente), ou null
 * @param afterId ID curseur (id du dernier élément de la page précédente), ou null
 * @param size taille de page (max 200, défaut 50)
 * @return une page keyset avec contenu + curseur suivant + flag hasNext
 */
 @Transactional(readOnly = true)
 public jo.accountant.accountingengine.dto.KeysetPage<InvoiceResponse> listInvoicesKeyset(
 UUID companyId, java.time.LocalDate afterIssueDate, UUID afterId, int size) {

 int pageSize = Math.max(1, Math.min(size, 200));
 org.springframework.data.domain.Pageable pageable =
 org.springframework.data.domain.PageRequest.of(0, pageSize);

 List<SalesInvoice> invoices = invoiceRepository.findKeysetAfter(
 companyId, afterIssueDate, afterId, pageable);

 List<InvoiceResponse> dtos = invoices.stream()
 .map(i -> loadInvoiceResponse(companyId, i.getId()))
 .toList();

 return jo.accountant.accountingengine.dto.KeysetPage.of(
 dtos, pageSize, InvoiceResponse::issueDate, InvoiceResponse::id);
 }
}

package jo.accountant.accountingengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalPeriodStatus;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.FiscalYearStatus;
import jo.accountant.accountingengine.entity.Journal;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.entity.JournalLineAnalyticalTag;
import jo.accountant.accountingengine.event.JournalEntryPostedEvent;
import jo.accountant.accountingengine.event.JournalEntryReversedEvent;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.analytics.service.AnalyticsService;
import jo.accountant.approvalworkflow.dto.EvaluateResult;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import jo.accountant.approvalworkflow.event.ApprovalDecidedEvent;
import jo.accountant.approvalworkflow.repository.ApprovalRequestRepository;
import jo.accountant.approvalworkflow.service.ApprovalWorkflowService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service dédié au <b>cycle de vie des écritures comptables</b> — extraction du God class
 * {@code AccountingEngineService} (refactor batch C).
 *
 * <p><b>Motivation</b> : {@code AccountingEngineService} était encore un God class de 1313 lignes
 * après l'extraction de {@code FiscalYearClosingService} (batch 2). Il mêlait
 * création d'écritures (DRAFT), postage (DRAFT → POSTED / PENDING_APPROVAL), contre-passation
 * (POSTED → VOIDED + nouvelle écriture inversée), gestion des exercices fiscaux, journaux,
 * grand livre, balance. Le cycle de vie d'une écriture (postage + contre-passation + chargement
 * pour réponse API) est un sous-domaine isolable, manipulé par tous les modules métier
 * (invoicing, purchasing, fixed-assets, inventory, payroll, funds-grants, expenses) via
 * {@code accountingEngineService.postJournalEntry(...)}.
 *
 * <p><b>Responsabilités transférées</b> :
 * <ul>
 * <li>{@link #postJournalEntry(UUID, UUID, List)} — transition {@code DRAFT → POSTED}
 * (ou {@code PENDING_APPROVAL} si seuil d'approbation atteint), avec re-vérification
 * de l'équilibre, de la période OPEN, des comptes actifs, des tags analytiques
 * obligatoires. Génère le {@code reference} via {@code document-numbering} au moment
 * exact du postage.</li>
 * <li>{@link #postJournalEntryAfterApproval(UUID, UUID)} — transition
 * {@code PENDING_APPROVAL → POSTED} sans ré-évaluation du seuil (l'approbation a déjà
 * été donnée par un décideur).</li>
 * <li>{@link #revertToDraftAfterRejection(UUID, UUID)} — transition
 * {@code PENDING_APPROVAL → DRAFT} après rejet/annulation de la demande d'approbation.</li>
 * <li>{@link #reverseJournalEntry(UUID, UUID, String)} et
 * {@link #reverseJournalEntry(UUID, UUID, String, LocalDate)} — contre-passation d'une
 * écriture {@code POSTED} : crée une nouvelle écriture inversée (débit ↔ crédit
 * permutés), marque l'originale comme {@code VOIDED} (règle de numérotation sans trou).</li>
 * <li>{@link #loadJournalEntryResponse(UUID, UUID)} — chargement d'une écriture + ses lignes
 * + ses tags analytiques, formaté en {@link JournalEntryResponse} pour l'API.</li>
 * <li>{@link #onApprovalDecided(ApprovalDecidedEvent)} — listener d'événements qui déclenche
 * {@link #postJournalEntryAfterApproval} ou {@link #revertToDraftAfterRejection} selon
 * la décision (APPROVED / REJECTED / CANCELLED).</li>
 * <li>Helpers privés : {@code loadJournalEntry}, {@code findPeriodForDate},
 * {@code validateAnalyticalTags}, {@code deserializePlanIds}
 * (duplications locales — ces helpers restent aussi dans
 * {@code AccountingEngineService} et {@code FiscalYearClosingService} qui les utilisent
 * ailleurs ; pattern établi par le batch 2).</li>
 * </ul>
 *
 * <p><b>Dépendances</b> : injecte les repositories nécessaires + le
 * {@link AccountingEngineService} (via {@link Lazy} pour casser le cycle :
 * AccountingEngineService délègue à ce service, qui à son tour pourrait appeler
 * AccountingEngineService pour d'autres opérations — cycle brisé par proxy Spring lazy).
 *
 * <p><b>Transaction</b> : toutes les méthodes publiques sont {@code @Transactional} —
 * chaque opération de cycle de vie est une UoW isolée. Le listener
 * {@link #onApprovalDecided} est aussi {@code @Transactional} pour que la transition
 * soit visible dans la même transaction que la décision d'approbation — un échec de postage
 * annule la décision (et inversement).
 *
 * <p><b>API publique préservée</b> : {@code AccountingEngineService.postJournalEntry},
 * {@code AccountingEngineService.reverseJournalEntry},
 * {@code AccountingEngineService.loadJournalEntryResponse}, etc. sont conservées comme
 * façades qui délèguent à ce service. Les tests d'intégration et les modules externes
 * (invoicing, purchasing, fixed-assets, etc.) appellent toujours
 * {@code accountingEngineService.postJournalEntry(...)} — signature inchangée.
 *
 * @see AccountingEngineService#postJournalEntry(UUID, UUID, List)
 * @see AccountingEngineService#reverseJournalEntry(UUID, UUID, String)
 * @see AccountingEngineService#loadJournalEntryResponse(UUID, UUID)
 
 *
 * @author jo@Dev
*/
@Service
public class JournalEntryLifecycleService {

 private static final Logger LOG = LoggerFactory.getLogger(JournalEntryLifecycleService.class);
 private static final BigDecimal HUNDRED = new BigDecimal("100");

 private final FiscalYearRepository fiscalYearRepository;
 private final FiscalPeriodRepository fiscalPeriodRepository;
 private final JournalRepository journalRepository;
 private final JournalEntryRepository journalEntryRepository;
 private final JournalLineRepository journalLineRepository;
 private final JournalLineAnalyticalTagRepository tagRepository;
 private final AccountRepository accountRepository;
 private final DocumentNumberingService documentNumberingService;
 private final ApprovalWorkflowService approvalWorkflowService;
 private final ApprovalRequestRepository approvalRequestRepository;
 private final AnalyticsService analyticsService;
 private final ApplicationEventPublisher events;
 private final ObjectMapper objectMapper;
 // @Lazy obligatoire : AccountingEngineService délègue postJournalEntry() / reverseJournalEntry()
 // / loadJournalEntryResponse() à ce service, qui à son tour peut appeler
 // AccountingEngineService.createJournalEntry() (via reverseJournalEntry qui ne l'appelle pas,
 // mais on garde @Lazy par symétrie avec FiscalYearClosingService et pour parer toute
 // future dépendance circulaire). Sans @Lazy, Spring lèverait BeanCurrentlyInCreationException.
 private final AccountingEngineService accountingEngineService;

 public JournalEntryLifecycleService(FiscalYearRepository fiscalYearRepository,
 FiscalPeriodRepository fiscalPeriodRepository,
 JournalRepository journalRepository,
 JournalEntryRepository journalEntryRepository,
 JournalLineRepository journalLineRepository,
 JournalLineAnalyticalTagRepository tagRepository,
 AccountRepository accountRepository,
 DocumentNumberingService documentNumberingService,
 ApprovalWorkflowService approvalWorkflowService,
 ApprovalRequestRepository approvalRequestRepository,
 AnalyticsService analyticsService,
 ApplicationEventPublisher events,
 ObjectMapper objectMapper,
 @Lazy AccountingEngineService accountingEngineService) {
 this.fiscalYearRepository = fiscalYearRepository;
 this.fiscalPeriodRepository = fiscalPeriodRepository;
 this.journalRepository = journalRepository;
 this.journalEntryRepository = journalEntryRepository;
 this.journalLineRepository = journalLineRepository;
 this.tagRepository = tagRepository;
 this.accountRepository = accountRepository;
 this.documentNumberingService = documentNumberingService;
 this.approvalWorkflowService = approvalWorkflowService;
 this.approvalRequestRepository = approvalRequestRepository;
 this.analyticsService = analyticsService;
 this.events = events;
 this.objectMapper = objectMapper;
 this.accountingEngineService = accountingEngineService;
 }

 // =========================================================================
 // Postage (DRAFT → POSTED / PENDING_APPROVAL)
 // =========================================================================

 /**
 * Poste une écriture DRAFT — transition vers POSTED ou PENDING_APPROVAL.
 *
 * <p>Étapes :
 * <ol>
 * <li>Vérifier l'équilibre débit = crédit (déjà vérifié à la création, mais on recheck).</li>
 * <li>Vérifier la période est toujours OPEN.</li>
 * <li>Vérifier les comptes sont actifs.</li>
 * <li>Vérifier les tags analytiques obligatoires (requiresAnalyticalTagPlanIds).</li>
 * <li>Calculer le montant total de l'écriture (max débit ou crédit).</li>
 * <li>Appeler {@code approvalWorkflowService.evaluate} avec JOURNAL_ENTRY_POST.</li>
 * <li>Si auto-approved → générer le {@code reference} via {@code document-numbering},
 * passer à POSTED.</li>
 * <li>Sinon → passer à PENDING_APPROVAL (sans {@code reference} — sera attribué après
 * approbation, lors d'un second postage manuel ou automatique).</li>
 * </ol>
 *
 * @param approverEmails emails des approbateurs éligibles (résolus par l'appelant —
 * voir décisiondans le worklog)
 */
 @Transactional
 public JournalEntryResponse postJournalEntry(UUID companyId, UUID entryId, List<String> approverEmails) {
 JournalEntry entry = loadJournalEntry(companyId, entryId);
 if (entry.getStatus() != JournalEntryStatus.DRAFT) {
 throw new ConflictException("ENTRY_NOT_DRAFT",
 "Seules les écritures DRAFT peuvent être postées. Statut actuel : " + entry.getStatus());
 }

 List<JournalLine> lines = journalLineRepository.findByJournalEntryIdOrderByLineNumber(entry.getId());
 if (lines.size() < 2) {
 throw new ValidationException("ENTRY_TOO_FEW_LINES",
 "Une écriture doit avoir au moins 2 lignes");
 }

 // Recheck équilibre
 BigDecimal totalDebit = lines.stream().map(JournalLine::getDebit)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal totalCredit = lines.stream().map(JournalLine::getCredit)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 if (totalDebit.compareTo(totalCredit) != 0) {
 throw new ValidationException("UNBALANCED_ENTRY",
 "Écriture déséquilibrée : débit=" + totalDebit + " crédit=" + totalCredit);
 }

 // Recheck période
 FiscalPeriod period = fiscalPeriodRepository.findById(entry.getFiscalPeriodId())
 .orElseThrow(() -> new NotFoundException("FiscalPeriod", entry.getFiscalPeriodId()));
 if (period.getStatus() == FiscalPeriodStatus.LOCKED) {
 throw new ConflictException("PERIOD_LOCKED",
 "La période " + period.getLabel() + " est verrouillée");
 }
 FiscalYear fy = fiscalYearRepository.findById(period.getFiscalYearId())
 .orElseThrow(() -> new NotFoundException("FiscalYear", period.getFiscalYearId()));
 if (fy.getStatus() == FiscalYearStatus.CLOSED) {
 throw new ConflictException("FISCAL_YEAR_CLOSED", "L'exercice est CLOSED");
 }

 // Recheck comptes actifs + tags analytiques obligatoires
 for (JournalLine line : lines) {
 Account account = accountRepository.findById(line.getAccountId())
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte introuvable : " + line.getAccountId()));
 if (!account.isActive()) {
 throw new ValidationException("ACCOUNT_INACTIVE",
 "Le compte " + account.getCode() + " est désactivé — impossible de poster");
 }
 validateAnalyticalTags(companyId, account, line);
 }

 // Évaluation du seuil d'approbation
 BigDecimal amountForApproval = totalDebit; // débit total = crédit total = montant de l'écriture
 EvaluateResult evalResult = approvalWorkflowService.evaluate(
 companyId, ApprovalActionType.JOURNAL_ENTRY_POST,
 "JournalEntry", entry.getId(), amountForApproval, approverEmails);

 if (evalResult.autoApproved()) {
 // Postage direct : générer le reference et passer à POSTED
 Journal journal = journalRepository.findById(entry.getJournalId())
 .orElseThrow(() -> new NotFoundException("Journal", entry.getJournalId()));
 IssuedNumber issued = documentNumberingService.nextNumber(
 companyId, DocumentType.JOURNAL_ENTRY, journal.getCode(), entry.getEntryDate()
 .atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

 entry.setReference(issued.number());
 entry.setStatus(JournalEntryStatus.POSTED);
 entry.setPostedAt(Instant.now());
 entry.setPostedBy(TenantContext.getUserId());
 JournalEntry saved = journalEntryRepository.save(entry);

 events.publishEvent(new JournalEntryPostedEvent(saved, totalDebit));
 LOG.info("Écriture postée : id={} reference={} amount={}", saved.getId(),
 saved.getReference(), totalDebit);
 } else {
 // Passage à PENDING_APPROVAL — pas de reference encore
 entry.setStatus(JournalEntryStatus.PENDING_APPROVAL);
 JournalEntry saved = journalEntryRepository.save(entry);
 LOG.info("Écriture en attente d'approbation : id={} requestId={}", saved.getId(),
 evalResult.requestId());
 }

 return loadJournalEntryResponse(companyId, entry.getId());
 }

 /**
 * Finalise le postage d'une écriture après qu'une demande d'approbation a été
 * {@link ApprovalStatus#APPROVED} — transition {@code PENDING_APPROVAL → POSTED} sans
 * ré-évaluation du seuil (l'approbation a déjà été donnée par un décideur).
 *
 * <p><b>Audit B2</b> : avant cette correction, une écriture passée à
 * {@code PENDING_APPROVAL} y restait bloquée à vie car aucun listener ne consommait
 * {@link ApprovalDecidedEvent} pour déclencher la transition. Le 4-yeux paralyssait
 * donc l'activité comptable dès qu'une écriture dépassait le seuil d'approbation.
 *
 * <p>Cette méthode :
 * <ol>
 * <li>Vérifie que l'écriture est bien {@code PENDING_APPROVAL} (sinon 409 {@code ENTRY_NOT_PENDING}).</li>
 * <li>Re-vérifie l'équilibre débit = crédit, la période OPEN, les comptes actifs, les tags
 * analytiques obligatoires (les mêmes garde-fous que {@link #postJournalEntry},
 * au cas où l'état du plan comptable aurait changé entre temps).</li>
 * <li>Génère le {@code reference} via {@code document-numbering} et passe à {@code POSTED}.</li>
 * <li>Publie {@link JournalEntryPostedEvent}.</li>
 * </ol>
 */
 @Transactional
 public JournalEntryResponse postJournalEntryAfterApproval(UUID companyId, UUID entryId) {
 JournalEntry entry = loadJournalEntry(companyId, entryId);
 if (entry.getStatus() != JournalEntryStatus.PENDING_APPROVAL) {
 throw new ConflictException("ENTRY_NOT_PENDING",
 "Seules les écritures PENDING_APPROVAL peuvent être finalisées après approbation. " +
 "Statut actuel : " + entry.getStatus());
 }

 List<JournalLine> lines = journalLineRepository.findByJournalEntryIdOrderByLineNumber(entry.getId());
 BigDecimal totalDebit = lines.stream().map(JournalLine::getDebit)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal totalCredit = lines.stream().map(JournalLine::getCredit)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 if (totalDebit.compareTo(totalCredit) != 0) {
 throw new ValidationException("UNBALANCED_ENTRY",
 "Écriture déséquilibrée : débit=" + totalDebit + " crédit=" + totalCredit);
 }

 FiscalPeriod period = fiscalPeriodRepository.findById(entry.getFiscalPeriodId())
 .orElseThrow(() -> new NotFoundException("FiscalPeriod", entry.getFiscalPeriodId()));
 if (period.getStatus() == FiscalPeriodStatus.LOCKED) {
 throw new ConflictException("PERIOD_LOCKED",
 "La période " + period.getLabel() + " est verrouillée");
 }
 FiscalYear fy = fiscalYearRepository.findById(period.getFiscalYearId())
 .orElseThrow(() -> new NotFoundException("FiscalYear", period.getFiscalYearId()));
 if (fy.getStatus() == FiscalYearStatus.CLOSED) {
 throw new ConflictException("FISCAL_YEAR_CLOSED", "L'exercice est CLOSED");
 }

 for (JournalLine line : lines) {
 Account account = accountRepository.findById(line.getAccountId())
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte introuvable : " + line.getAccountId()));
 if (!account.isActive()) {
 throw new ValidationException("ACCOUNT_INACTIVE",
 "Le compte " + account.getCode() + " est désactivé — impossible de poster");
 }
 validateAnalyticalTags(companyId, account, line);
 }

 // Postage direct (sans ré-évaluation de l'approbation — déjà donnée)
 Journal journal = journalRepository.findById(entry.getJournalId())
 .orElseThrow(() -> new NotFoundException("Journal", entry.getJournalId()));
 IssuedNumber issued = documentNumberingService.nextNumber(
 companyId, DocumentType.JOURNAL_ENTRY, journal.getCode(), entry.getEntryDate()
 .atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

 entry.setReference(issued.number());
 entry.setStatus(JournalEntryStatus.POSTED);
 entry.setPostedAt(Instant.now());
 entry.setPostedBy(TenantContext.getUserId());
 JournalEntry saved = journalEntryRepository.save(entry);

 events.publishEvent(new JournalEntryPostedEvent(saved, totalDebit));
 LOG.info("Écriture postée après approbation : id={} reference={} amount={}",
 saved.getId(), saved.getReference(), totalDebit);

 return loadJournalEntryResponse(companyId, entry.getId());
 }

 /**
 * Repasse une écriture {@code PENDING_APPROVAL} à {@code DRAFT} après un rejet ou une
 * annulation de la demande d'approbation — l'utilisateur peut alors corriger et re-soumettre.
 *
 * <p><b>Audit B2</b> : sans cette méthode, une écriture rejetée restait bloquée en
 * {@code PENDING_APPROVAL} sans possibilité de correction.
 */
 @Transactional
 public JournalEntryResponse revertToDraftAfterRejection(UUID companyId, UUID entryId) {
 JournalEntry entry = loadJournalEntry(companyId, entryId);
 if (entry.getStatus() != JournalEntryStatus.PENDING_APPROVAL) {
 throw new ConflictException("ENTRY_NOT_PENDING",
 "Seules les écritures PENDING_APPROVAL peuvent être repassées à DRAFT. " +
 "Statut actuel : " + entry.getStatus());
 }
 entry.setStatus(JournalEntryStatus.DRAFT);
 journalEntryRepository.save(entry);
 LOG.info("Écriture repassée à DRAFT après rejet/annulation d'approbation : id={}", entry.getId());
 return loadJournalEntryResponse(companyId, entry.getId());
 }

 /**
 * Écoute les décisions d'approbation pour finaliser ou annuler le postage des écritures
 * en attente ({@code PENDING_APPROVAL}).
 *
 * <p><b>Audit B2</b> : ce listener manquait entièrement — toute écriture dépassant le seuil
 * d'approbation restait bloquée à vie en {@code PENDING_APPROVAL}, paralyssant l'activité
 * comptable dès qu'un workflow 4-yeux était configuré.
 *
 * <p>L'event est publié par {@code ApprovalWorkflowService.decide} après persistance de la
 * décision. On le consomme de manière synchrone (pas {@code @Async}) pour que la transition
 * soit visible dans la même transaction que la décision d'approbation — un échec de postage
 * annule la décision (et inversement).
 *
 * <p>Comportement :
 * <ul>
 * <li>Si {@code resourceType == "JournalEntry"} et {@code newStatus == APPROVED} →
 * appelle {@link #postJournalEntryAfterApproval}.</li>
 * <li>Si {@code resourceType == "JournalEntry"} et {@code newStatus ∈ {REJECTED, CANCELLED}} →
 * appelle {@link #revertToDraftAfterRejection}.</li>
 * <li>Ignore les autres {@code resourceType} (autres modules consommateurs du workflow).</li>
 * </ul>
 */
 @EventListener
 @Transactional
 public void onApprovalDecided(ApprovalDecidedEvent event) {
 //— defense-in-depth : récupérer companyId AVANT le findById pour filtrage
 UUID companyId = event.companyId();
 ApprovalRequest request = approvalRequestRepository.findById(event.requestId())
 .filter(r -> r.getCompanyId().equals(companyId))
 .orElse(null);
 if (request == null) {
 LOG.warn("ApprovalDecidedEvent pour une ApprovalRequest introuvable/hors-tenant : requestId={}, companyId={}",
 event.requestId(), companyId);
 return;
 }
 if (!"JournalEntry".equals(request.getResourceType())) {
 return; // autres consommateurs (invoicing, fixed-assets, etc. — pas encore branchés)
 }
 UUID entryId = request.getResourceId();
 try {
 if (event.newStatus() == ApprovalStatus.APPROVED) {
 postJournalEntryAfterApproval(companyId, entryId);
 } else if (event.newStatus() == ApprovalStatus.REJECTED
 || event.newStatus() == ApprovalStatus.CANCELLED) {
 revertToDraftAfterRejection(companyId, entryId);
 }
 } catch (Exception e) {
 LOG.error("Échec de la transition post-approbation de l'écriture {} (status={}) : {}",
 entryId, event.newStatus(), e.getMessage(), e);
 // On ne propage pas l'exception pour ne pas casser le flux d'approbation —
 // l'écriture reste dans son état courant et un opérateur pourra intervenir.
 }
 }

 // =========================================================================
 // Contre-passation (POSTED → VOIDED + nouvelle écriture inversée)
 // =========================================================================

 /**
 * Contre-passe une écriture POSTED — crée une nouvelle écriture inversée et marque
 * l'originale comme VOIDED.
 *
 * <p>L'écriture de contre-passation :
 * <ul>
 * <li>A les mêmes lignes que l'originale, mais débit ↔ crédit permutés.</li>
 * <li>{@code reversalOfEntryId} pointe vers l'originale.</li>
 * <li>{@code sourceModule = REVERSAL}.</li>
 * <li>Statut POSTED (postage direct — pas d'approbation pour une contre-passation,
 * c'est volontaire : la contre-passation est elle-même un acte d'audit, et bloquer
 * une correction par manque d'approbateur pourrait être contre-productif).</li>
 * </ul>
 *
 * <p>L'originale passe à VOIDED mais conserve son {@code reference} — règle de
 * numérotation sans trou (§6).
 */
 @Transactional
 public JournalEntryResponse reverseJournalEntry(UUID companyId, UUID entryId, String reason) {
 return reverseJournalEntry(companyId, entryId, reason, null);
 }

 /**
 * Contre-passe une écriture POSTED avec date de contre-passation paramétrable.
 *
 * <p><b>FIX</b> :
 * <ul>
 * <li>La version originale utilisait {@code LocalDate.now()} comme date de contre-passation.
 * Si l'originale était en N (exercice CLOSED), la contre-passation était postée en N+1
 * avec une période fiscale incohérente (N LOCKED). Désormais, on accepte une date
 * paramétrable et on vérifie que la période correspondante est OPEN.</li>
 * <li>Empêche la contre-passation d'une contre-passation (reversal de reversal) — l'audit
 * ce cas était accepté, créant des chaînes illisibles.</li>
 * </ul>
 *
 * @param reversalDate date de la contre-passation (null = date du jour). Doit tomber dans
 * une période fiscale OPEN.
 */
 @Transactional
 public JournalEntryResponse reverseJournalEntry(UUID companyId, UUID entryId, String reason,
 LocalDate reversalDate) {
 JournalEntry original = loadJournalEntry(companyId, entryId);
 if (original.getStatus() != JournalEntryStatus.POSTED) {
 throw new ConflictException("ENTRY_NOT_POSTED",
 "Seules les écritures POSTED peuvent être contre-passées. Statut : " + original.getStatus());
 }
 //empêcher reversal de reversal
 if (original.getSourceModule() == JournalEntrySourceModule.REVERSAL) {
 throw new ConflictException("CANNOT_REVERSE_A_REVERSAL",
 "Une écriture de contre-passation ne peut pas être elle-même contre-passée. " +
 "Pour corriger une contre-passation erronée, créer une nouvelle écriture manuelle " +
 "avec référence à la contre-passation " + original.getReference() + ".");
 }

 //date paramétrable + vérification période OPEN
 LocalDate effectiveReversalDate = reversalDate != null ? reversalDate : LocalDate.now();
 FiscalPeriod reversalPeriod = findPeriodForDate(companyId, effectiveReversalDate);
 if (reversalPeriod == null) {
 throw new ValidationException("PERIOD_NOT_FOUND",
 "Aucune période fiscale trouvée pour la date de contre-passation " + effectiveReversalDate);
 }
 if (reversalPeriod.getStatus() != FiscalPeriodStatus.OPEN) {
 throw new ConflictException("PERIOD_LOCKED",
 "La période fiscale " + reversalPeriod.getLabel() + " (statut="
 + reversalPeriod.getStatus() + ") n'est pas OPEN. Impossible de contre-passer à la date "
 + effectiveReversalDate + ". Choisir une date dans une période OPEN.");
 }

 List<JournalLine> originalLines = journalLineRepository.findByJournalEntryIdOrderByLineNumber(original.getId());

 // Créer la contre-passation
 JournalEntry reversal = new JournalEntry();
 reversal.setCompanyId(companyId);
 reversal.setJournalId(original.getJournalId());
 reversal.setFiscalPeriodId(reversalPeriod.getId()); //— utiliser la période de la date effective
 reversal.setEntryDate(effectiveReversalDate); //— date paramétrable
 reversal.setDescription("Contre-passation de " + original.getReference()
 + (reason != null ? " — " + reason : ""));
 reversal.setStatus(JournalEntryStatus.DRAFT);
 reversal.setSourceModule(JournalEntrySourceModule.REVERSAL);
 reversal.setReversalOfEntryId(original.getId());
 reversal.setIdempotencyKey("reversal-" + original.getId());
 JournalEntry savedReversal = journalEntryRepository.save(reversal);

 // Créer les lignes inversées (débit ↔ crédit permutés)
 int lineNumber = 1;
 for (JournalLine originalLine : originalLines) {
 JournalLine reversedLine = new JournalLine();
 reversedLine.setCompanyId(companyId);
 reversedLine.setJournalEntryId(savedReversal.getId());
 reversedLine.setAccountId(originalLine.getAccountId());
 reversedLine.setAccountCode(originalLine.getAccountCode());
 reversedLine.setThirdPartyId(originalLine.getThirdPartyId());
 reversedLine.setDebit(originalLine.getCredit()); // permuté
 reversedLine.setCredit(originalLine.getDebit()); // permuté
 reversedLine.setLineNumber(lineNumber++);
 reversedLine.setDescription(originalLine.getDescription());
 reversedLine.setAmountTransactionCurrency(originalLine.getAmountTransactionCurrency());
 reversedLine.setTransactionCurrency(originalLine.getTransactionCurrency());
 reversedLine.setExchangeRateUsed(originalLine.getExchangeRateUsed());
 journalLineRepository.save(reversedLine);
 }

 // Marquer l'originale comme VOIDED
 original.setStatus(JournalEntryStatus.VOIDED);
 journalEntryRepository.save(original);

 // Poster directement la contre-passation (pas d'approbation — voir javadoc)
 Journal journal = journalRepository.findById(original.getJournalId())
 .orElseThrow(() -> new NotFoundException("Journal", original.getJournalId()));
 IssuedNumber issued = documentNumberingService.nextNumber(
 companyId, DocumentType.JOURNAL_ENTRY, journal.getCode(),
 reversal.getEntryDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
 savedReversal.setReference(issued.number());
 savedReversal.setStatus(JournalEntryStatus.POSTED);
 savedReversal.setPostedAt(Instant.now());
 savedReversal.setPostedBy(TenantContext.getUserId());
 journalEntryRepository.save(savedReversal);

 events.publishEvent(new JournalEntryReversedEvent(original, savedReversal,
 TenantContext.getUserId()));

 return loadJournalEntryResponse(companyId, savedReversal.getId());
 }

 // =========================================================================
 // Lecture
 // =========================================================================

 /**
 * Charge une écriture + ses lignes + ses tags analytiques, formaté en
 * {@link JournalEntryResponse} pour l'API. Méthode partagée entre createJournalEntry
 * (AccountingEngineService), postJournalEntry, reverseJournalEntry et les endpoints de
 * lecture (getJournalEntry, listJournalEntries, searchJournalEntries).
 */
 @Transactional(readOnly = true)
 public JournalEntryResponse loadJournalEntryResponse(UUID companyId, UUID entryId) {
 JournalEntry entry = loadJournalEntry(companyId, entryId);
 List<JournalLine> lines = journalLineRepository.findByJournalEntryIdOrderByLineNumber(entry.getId());

 List<JournalEntryResponse.LineResponse> lineResponses = new ArrayList<>();
 BigDecimal totalDebit = BigDecimal.ZERO;
 BigDecimal totalCredit = BigDecimal.ZERO;
 for (JournalLine line : lines) {
 totalDebit = totalDebit.add(line.getDebit());
 totalCredit = totalCredit.add(line.getCredit());
 List<JournalLineAnalyticalTag> tags = tagRepository.findByJournalLineId(line.getId());
 List<JournalEntryResponse.AnalyticalTagResponse> tagResponses = tags.stream()
 .map(t -> new JournalEntryResponse.AnalyticalTagResponse(
 t.getId(), t.getPlanId(), t.getValueId(), t.getAllocationPercentage()))
 .toList();
 lineResponses.add(new JournalEntryResponse.LineResponse(
 line.getId(), line.getAccountId(), line.getAccountCode(),
 line.getThirdPartyId(), line.getDebit(), line.getCredit(),
 line.getLineNumber(), line.getDescription(), tagResponses));
 }

 //— defense-in-depth : filtrer par companyId
 Journal journal = journalRepository.findById(entry.getJournalId())
 .filter(j -> j.getCompanyId().equals(companyId))
 .orElse(null);
 String journalCode = journal != null ? journal.getCode() : null;

 return new JournalEntryResponse(
 entry.getId(), entry.getCompanyId(), entry.getJournalId(), journalCode,
 entry.getFiscalPeriodId(), entry.getEntryDate(), entry.getReference(),
 entry.getDescription(), entry.getStatus(), entry.getPostedAt(), entry.getPostedBy(),
 entry.getReversalOfEntryId(), entry.getSourceModule(), entry.getIdempotencyKey(),
 lineResponses, totalDebit, totalCredit);
 }

 // =========================================================================
 // Helpers privés (duplications locales — pattern établi par FiscalYearClosingService)
 // =========================================================================

 /**
 * Valide les tags analytiques obligatoires pour une ligne.
 *
 * <p>Si le compte porte {@code requiresAnalyticalTagPlanIds} non vide, chaque plan listé
 * doit avoir au moins un tag sur la ligne, et la somme des allocationPercentage par plan
 * doit être 100%.
 */
 private void validateAnalyticalTags(UUID companyId, Account account, JournalLine line) {
 List<UUID> requiredPlanIds = deserializePlanIds(account.getRequiresAnalyticalTagPlanIds());
 if (requiredPlanIds.isEmpty()) {
 return; // pas de tag obligatoire pour ce compte
 }

 List<JournalLineAnalyticalTag> tags = tagRepository.findByJournalLineId(line.getId());

 // Grouper par planId
 Map<UUID, BigDecimal> sumByPlan = new HashMap<>();
 Set<UUID> seenPlans = new HashSet<>();
 for (JournalLineAnalyticalTag tag : tags) {
 sumByPlan.merge(tag.getPlanId(), tag.getAllocationPercentage(), BigDecimal::add);
 seenPlans.add(tag.getPlanId());

 // Valider que la valeur existe et appartient au plan
 analyticsService.validateValue(companyId, tag.getPlanId(), tag.getValueId());
 //IDOR CRITICAL : valider que le plan appartient à la company
 // (avant : analyticsService.findPlanById(planId) ne filtrait pas par companyId)
 if (analyticsService.findPlanById(companyId, tag.getPlanId()).isEmpty()) {
 throw new ValidationException("ANALYTICAL_PLAN_NOT_FOUND",
 "Plan analytique introuvable ou hors tenant : " + tag.getPlanId());
 }
 }

 // Pour chaque plan requis, vérifier qu'il a au moins un tag et que la somme = 100%
 for (UUID requiredPlanId : requiredPlanIds) {
 BigDecimal sum = sumByPlan.getOrDefault(requiredPlanId, BigDecimal.ZERO);
 if (sum.compareTo(HUNDRED) != 0) {
 throw new ValidationException("ANALYTICAL_TAG_REQUIRED",
 "La ligne sur le compte " + account.getCode() + " doit porter un tag analytique " +
 "du plan " + requiredPlanId + " avec une somme de 100% (actuel: " + sum + "%)");
 }
 }
 }

 private JournalEntry loadJournalEntry(UUID companyId, UUID entryId) {
 JournalEntry entry = journalEntryRepository.findById(entryId)
 .orElseThrow(() -> new NotFoundException("JournalEntry", entryId));
 if (!entry.getCompanyId().equals(companyId)) {
 throw new NotFoundException("JournalEntry", entryId);
 }
 return entry;
 }

 private FiscalPeriod findPeriodForDate(UUID companyId, LocalDate date) {
 // Trouver l'exercice contenant la date
 List<FiscalYear> fys = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
 for (FiscalYear fy : fys) {
 if (!date.isBefore(fy.getStartDate()) && !date.isAfter(fy.getEndDate())) {
 return fiscalPeriodRepository
 .findByFiscalYearIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
 fy.getId(), date, date)
 .orElse(null);
 }
 }
 return null;
 }

 private List<UUID> deserializePlanIds(String json) {
 if (json == null || json.isBlank()) return List.of();
 try {
 return objectMapper.readValue(json,
 objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
 } catch (JsonProcessingException e) {
 LOG.warn("Failed to deserialize plan ids: {}", json, e);
 return List.of();
 }
 }
}

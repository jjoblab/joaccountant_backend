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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.AnalyticalTagDto;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.dto.KeysetPage;
import jo.accountant.accountingengine.dto.LedgerLine;
import jo.accountant.accountingengine.dto.TrialBalanceLine;
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
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.analytics.service.AnalyticsService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service du moteur comptable (§13le cœur non négociable du projet.
 *
 * <p>Responsabilités :
 * <ul>
 * <li>Création des exercices fiscaux et périodes (mensuelles auto-générées)</li>
 * <li>Création des journaux</li>
 * <li>Création d'écritures en brouillon (DRAFT) avec idempotence (§3.10)</li>
 * <li>Postage d'écritures (transition DRAFT → POSTED), avec :
 * <ul>
 * <li>Vérification de l'équilibre débit = crédit</li>
 * <li>Vérification de la période OPEN</li>
 * <li>Vérification que les comptes sont {@code active = true}</li>
 * <li>Vérification des tags analytiques obligatoires (si le compte porte
 * {@code requiresAnalyticalTagPlanIds} non vide)</li>
 * <li>Évaluation du seuil d'approbation via {@code :approval-workflow}</li>
 * <li>Génération du numéro via {@code :document-numbering} au moment exact du postage</li>
 * </ul>
 * </li>
 * <li>Contre-passation d'une écriture POSTED (crée une nouvelle écriture liée via
 * {@code reversalOfEntryId}, l'originale passe à VOIDED)</li>
 * <li>Grand livre et balance générale (toujours reconciliables à zéro)</li>
 * </ul>
 *
 * <p>Règles métier §13(chacune testée par un test qui échouerait si la règle
 * était retirée) :
 * <ol>
 * <li>Somme(débit) = somme(crédit) sur chaque écriture, vérifiée en application ET par trigger DB.</li>
 * <li>Création d'écriture uniquement sur période OPEN ; LOCKED/exercice CLOSED → 409.</li>
 * <li>Passage à POSTED soumis à {@code approval-workflow} si règle active et montant &gt; seuil.</li>
 * <li>Écriture POSTED immuable — correction uniquement par contre-passation.</li>
 * <li>Impossible de poster sur un compte {@code active = false}.</li>
 * <li>Ligne sur compte {@code requiresAnalyticalTagPlanIds} non vide → tag obligatoire.</li>
 * <li>Balance générale et grand livre toujours reconciliables à zéro.</li>
 * <li>{@code POST .../journal-entries} exige {@code Idempotency-Key} ; rejeu = même résultat.</li>
 * <li>{@code AccountBalanceGuard} implémenté= vraie impl basée sur JournalLine).</li>
 * <li>{@code reference} générée via {@code document-numbering} au moment du post, jamais avant.</li>
 * <li>Contre-passation : {@code reversalOfEntryId} pointe vers l'originale.</li>
 * </ol>
 
 *
 * @author jo@Dev


*/
@Service
public class AccountingEngineService {

 private static final Logger LOG = LoggerFactory.getLogger(AccountingEngineService.class);
 private static final BigDecimal HUNDRED = new BigDecimal("100");

 private final FiscalYearRepository fiscalYearRepository;
 private final FiscalPeriodRepository fiscalPeriodRepository;
 private final JournalRepository journalRepository;
 private final JournalEntryRepository journalEntryRepository;
 private final JournalLineRepository journalLineRepository;
 private final JournalLineAnalyticalTagRepository tagRepository;
 private final AccountRepository accountRepository;
 private final DocumentNumberingService documentNumberingService;
 private final AnalyticsService analyticsService;
 private final ApplicationEventPublisher events;
 private final ObjectMapper objectMapper;
 private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
 // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
 private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;
 // (refactor batch 2) — la logique de clôture d'exercice est déléguée à un service
 // dédié (extraction du God class). @Lazy non requis ici : FiscalYearClosingService injecte
 // AccountingEngineService en @Lazy → le cycle est déjà cassé côté FiscalYearClosingService.
 private final FiscalYearClosingService fiscalYearClosingService;
 // (refactor batch C) — la logique de cycle de vie des écritures (postage,
 // contre-passation, chargement pour réponse API) est déléguée à un service dédié
 // (extraction du God class). @Lazy non requis ici : JournalEntryLifecycleService injecte
 // AccountingEngineService en @Lazy → le cycle est déjà cassé côté JournalEntryLifecycleService.
 private final JournalEntryLifecycleService journalEntryLifecycleService;

 public AccountingEngineService(FiscalYearRepository fiscalYearRepository,
 FiscalPeriodRepository fiscalPeriodRepository,
 JournalRepository journalRepository,
 JournalEntryRepository journalEntryRepository,
 JournalLineRepository journalLineRepository,
 JournalLineAnalyticalTagRepository tagRepository,
 AccountRepository accountRepository,
 DocumentNumberingService documentNumberingService,
 AnalyticsService analyticsService,
 ApplicationEventPublisher events,
 ObjectMapper objectMapper,
 org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
 jo.accountant.chartofaccounts.service.AccountResolver accountResolver,
 FiscalYearClosingService fiscalYearClosingService,
 JournalEntryLifecycleService journalEntryLifecycleService) {
 this.fiscalYearRepository = fiscalYearRepository;
 this.fiscalPeriodRepository = fiscalPeriodRepository;
 this.journalRepository = journalRepository;
 this.journalEntryRepository = journalEntryRepository;
 this.journalLineRepository = journalLineRepository;
 this.tagRepository = tagRepository;
 this.accountRepository = accountRepository;
 this.documentNumberingService = documentNumberingService;
 this.analyticsService = analyticsService;
 this.events = events;
 this.objectMapper = objectMapper;
 this.jdbcTemplate = jdbcTemplate;
 this.accountResolver = accountResolver;
 this.fiscalYearClosingService = fiscalYearClosingService;
 this.journalEntryLifecycleService = journalEntryLifecycleService;
 }

 // --- Exercices & périodes ---

 @Transactional
 public FiscalYear createFiscalYear(UUID companyId, CreateFiscalYearRequest req) {
 if (req.startDate() == null || req.endDate() == null) {
 throw new ValidationException("DATES_REQUIRED", "startDate et endDate sont requis");
 }
 if (!req.endDate().isAfter(req.startDate())) {
 throw new ValidationException("INVALID_DATE_RANGE", "endDate doit être après startDate");
 }

 // Fix Dim 5 H2 (audit v9.4) — Garde-fou applicatif : 1 entreprise = 1 exercice OPEN maximum.
 // Complète la contrainte DB uc_one_open_per_company (V8_009) avec un message d'erreur explicite.
 // Sans ce guard, l'utilisateur obtiendrait une DataIntegrityViolationException générique
 // (difficile à comprendre côté frontend).
 long openCount = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId).stream()
 .filter(f -> f.getStatus() == FiscalYearStatus.OPEN)
 .count();
 if (openCount > 0) {
 throw new jo.accountant.core.exception.ConflictException("OPEN_FISCAL_YEAR_ALREADY_EXISTS",
 "L'entreprise a déjà un exercice OPEN. Clôturer l'exercice courant avant d'en créer un nouveau. " +
 "Cette contrainte garantit la cohérence comptable (1 exercice actif à la fois).");
 }

 FiscalYear fy = new FiscalYear();
 fy.setCompanyId(companyId);
 fy.setStartDate(req.startDate());
 fy.setEndDate(req.endDate());
 fy.setLabel(req.label() != null ? req.label() :
 "Exercice " + req.startDate().getYear() + "-" + req.endDate().getYear());
 fy.setStatus(FiscalYearStatus.OPEN);
 FiscalYear saved = fiscalYearRepository.save(fy);
 // v9.4 fix — Flush explicite pour garantir que l'INSERT fiscal_year est envoyé au SGBD
 // avant l'UPDATE companies.active_fiscal_year_id (qui passe par jdbcTemplate et bypass
 // la session Hibernate). Sans ce flush, la contrainte FK fk_companies_active_fy (V8_009)
 // échoue car la ligne fiscal_year n'est pas encore visible en DB → HTTP 500 sur
 // POST /wizard/complete (cf. worklog Task 2-a, étape 4).
 fiscalYearRepository.flush();

 // Génère 12 périodes mensuelles par défaut
 generateMonthlyPeriods(companyId, saved);

 // Auto-activate if no active fiscal year is set (best-effort — skip if company row
 // doesn't exist, e.g. in tests where companies are not created via companyService).
 try {
 if (readActiveFiscalYearId(companyId) == null) {
 setActiveFiscalYearId(companyId, saved.getId());
 LOG.info("Auto-activated fiscal year {} for company {}", saved.getId(), companyId);
 }
 } catch (Exception e) {
 LOG.debug("Auto-activation skipped for company {} (best-effort): {}", companyId, e.getMessage());
 }

 return saved;
 }

 // --- Active fiscal year management ---

 private UUID readActiveFiscalYearId(UUID companyId) {
 try {
 var results = jdbcTemplate.queryForList(
 "SELECT active_fiscal_year_id FROM companies WHERE id = ?",
 UUID.class, companyId);
 return results.isEmpty() ? null : results.get(0);
 } catch (Exception e) {
 return null;
 }
 }

 private void setActiveFiscalYearId(UUID companyId, UUID fiscalYearId) {
 try {
 var versions = jdbcTemplate.queryForList(
 "SELECT version FROM companies WHERE id = ?", Long.class, companyId);
 if (versions.isEmpty()) return; // company doesn't exist — skip
 Long version = versions.get(0);
 jdbcTemplate.update(
 "UPDATE companies SET active_fiscal_year_id = ?, updated_at = NOW(), version = version + 1 " +
 "WHERE id = ? AND version = ?",
 fiscalYearId, companyId, version);
 } catch (Exception e) {
 LOG.debug("setActiveFiscalYearId skipped for company {} (best-effort): {}", companyId, e.getMessage());
 }
 }

 // --- Active fiscal year management (§2 Option A — per-request resolution) ---

 /**
 * Résout l'exercice fiscal à utiliser pour une requête de lecture.
 *
 * <p>§2 Option A : l'exercice "actif" n'est plus un état partagé
 * persisté en DB. La résolution se fait à la volée par requête :
 * <ol>
 * <li>Si {@code fiscalYearId} est fourni (paramètre explicite de l'endpoint), l'utiliser.</li>
 * <li>Sinon, chercher l'exercice OPEN dont la plage contient aujourd'hui.</li>
 * <li>Sinon, prendre le dernier exercice OPEN (le plus récent).</li>
 * <li>Sinon (aucun exercice), retourner {@code Optional.empty()}.</li>
 * </ol>
 *
 * <p>Les anciens endpoints {@code POST /fiscal-years/{id}/activate} et
 * {@code GET /fiscal-years/active} sont dépréciés mais conservés pour rétro-compatibilité
 * — ils lisent/écrivent toujours la colonne {@code active_fiscal_year_id} mais celle-ci
 * n'est plus utilisée par les endpoints de données (qui appellent cette méthode à la place).
 *
 * @param companyId identifiant du tenant
 * @param fiscalYearId exercice explicitement demandé par le client (nullable)
 * @return l'exercice fiscal à utiliser, ou empty si aucun n'existe
 */
 @Transactional(readOnly = true)
 public java.util.Optional<FiscalYear> resolveFiscalYear(UUID companyId, java.util.UUID fiscalYearId) {
 if (fiscalYearId != null) {
 try {
 return java.util.Optional.of(loadFiscalYear(companyId, fiscalYearId));
 } catch (NotFoundException e) {
 return java.util.Optional.empty();
 }
 }
 // Fix Dim 5 C3 (audit v9.4) — Consulter FiscalYearContext (header X-Fiscal-Year)
 // avant de fallback sur l'exercice actif. Permet au frontend de "poser" l'exercice
 // sélectionné une fois pour toutes dans le header, sans propager ?fiscalYearId= partout.
 java.util.UUID contextFyId = jo.accountant.core.fiscal.FiscalYearContext.getFiscalYearId();
 if (contextFyId != null) {
 try {
 return java.util.Optional.of(loadFiscalYear(companyId, contextFyId));
 } catch (NotFoundException e) {
 LOG.warn("Header X-Fiscal-Year={} ne correspond à aucun exercice de la company {} — fallback sur exercice actif",
 contextFyId, companyId);
 // On continue vers la résolution par défaut
 }
 }
 // No explicit FY → find the OPEN year containing today
 LocalDate today = LocalDate.now();
 List<FiscalYear> allFy = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
 for (FiscalYear fy : allFy) {
 if (fy.getStatus() == FiscalYearStatus.OPEN
 && !today.isBefore(fy.getStartDate()) && !today.isAfter(fy.getEndDate())) {
 return java.util.Optional.of(fy);
 }
 }
 // No OPEN year containing today → take the latest OPEN
 for (int i = allFy.size() - 1; i >= 0; i--) {
 if (allFy.get(i).getStatus() == FiscalYearStatus.OPEN) {
 return java.util.Optional.of(allFy.get(i));
 }
 }
 return java.util.Optional.empty();
 }

 /**
 * @deprecated Utiliser {@link #resolveFiscalYear(UUID, UUID)} à la place.
 * Conservé pour rétro-compatibilité avec les endpoints dépréciés
 * {@code GET /fiscal-years/active} et {@code POST /fiscal-years/{id}/activate}.
 */
 @Deprecated
 @Transactional(readOnly = true)
 public java.util.Optional<FiscalYear> getActiveFiscalYearForRead(UUID companyId) {
 UUID activeId = readActiveFiscalYearId(companyId);
 if (activeId == null) return java.util.Optional.empty();
 try {
 return java.util.Optional.of(loadFiscalYear(companyId, activeId));
 } catch (NotFoundException e) {
 LOG.warn("Active FY {} not found for company {}", activeId, companyId);
 return java.util.Optional.empty();
 }
 }

 /**
 * @deprecated Utiliser {@link #resolveFiscalYear(UUID, UUID)} à la place.
 */
 @Deprecated
 @Transactional
 public FiscalYear getActiveFiscalYear(UUID companyId) {
 UUID activeId = readActiveFiscalYearId(companyId);
 if (activeId != null) {
 return loadFiscalYear(companyId, activeId);
 }
 List<FiscalYear> allFy = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
 for (int i = allFy.size() - 1; i >= 0; i--) {
 if (allFy.get(i).getStatus() == FiscalYearStatus.OPEN) {
 setActiveFiscalYearId(companyId, allFy.get(i).getId());
 return allFy.get(i);
 }
 }
 throw new NotFoundException("NO_ACTIVE_FISCAL_YEAR",
 "Aucun exercice fiscal actif.");
 }

 /**
 * @deprecated Le concept de "check writable" basé sur l'exercice actif partagé est obsolète (§1).
 * Les écritures sont validées par {@code findPeriodForDate} qui vérifie l'exercice réel de la date.
 */
 @Deprecated
 @Transactional(readOnly = true)
 public void checkActiveFiscalYearWritable(UUID companyId) {
 // No-op: the real validation is in createJournalEntry via findPeriodForDate.
 // Kept for backward compat — does nothing.
 }

 @Transactional
 public FiscalYear activateFiscalYear(UUID companyId, UUID fiscalYearId) {
 FiscalYear fy = loadFiscalYear(companyId, fiscalYearId);
 setActiveFiscalYearId(companyId, fiscalYearId);
 LOG.info("Active fiscal year set to {} for company {}", fiscalYearId, companyId);
 return fy;
 }

 private void generateMonthlyPeriods(UUID companyId, FiscalYear fy) {
 LocalDate cursor = fy.getStartDate().withDayOfMonth(1);
 int month = 1;
 while (!cursor.isAfter(fy.getEndDate())) {
 LocalDate periodStart = cursor;
 LocalDate periodEnd = cursor.plusMonths(1).minusDays(1);
 if (periodEnd.isAfter(fy.getEndDate())) {
 periodEnd = fy.getEndDate();
 }

 FiscalPeriod period = new FiscalPeriod();
 period.setCompanyId(companyId);
 period.setFiscalYearId(fy.getId());
 period.setStartDate(periodStart);
 period.setEndDate(periodEnd);
 period.setLabel(String.format("%04d-%02d", periodStart.getYear(), periodStart.getMonthValue()));
 period.setStatus(FiscalPeriodStatus.OPEN);
 fiscalPeriodRepository.save(period);

 cursor = cursor.plusMonths(1);
 month++;
 }
 }

 @Transactional
 public FiscalYear lockFiscalYear(UUID companyId, UUID fiscalYearId) {
 FiscalYear fy = loadFiscalYear(companyId, fiscalYearId);
 if (fy.getStatus() == FiscalYearStatus.CLOSED) {
 throw new ConflictException("FISCAL_YEAR_ALREADY_CLOSED",
 "L'exercice est déjà CLOSED");
 }
 fy.setStatus(FiscalYearStatus.LOCKED);
 // Verrouiller toutes les périodes
 fiscalPeriodRepository.findByFiscalYearIdOrderByStartDateAsc(fy.getId())
 .forEach(p -> {
 p.setStatus(FiscalPeriodStatus.LOCKED);
 fiscalPeriodRepository.save(p);
 });
 return fiscalYearRepository.save(fy);
 }

 /**
 * Liste tous les exercices fiscaux d'une entreprise (triés par date de début croissante).
 *(suite 3) — endpoint manquant jusqu'ici. Utilisé par le
 * script seed et par l'application mobile pour lister les exercices.
 */
 @Transactional(readOnly = true)
 public List<FiscalYear> listFiscalYears(UUID companyId) {
 return fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
 }

 /**
 * Récupère un exercice fiscal par ID. Lève NotFoundException si l'exercice n'existe pas
 * ou n'appartient pas à l'entreprise.
 */
 @Transactional(readOnly = true)
 public FiscalYear getFiscalYear(UUID companyId, UUID fiscalYearId) {
 return loadFiscalYear(companyId, fiscalYearId);
 }

 /**
 * Liste les périodes d'un exercice fiscal (triées par date de début croissante).
 */
 @Transactional(readOnly = true)
 public List<FiscalPeriod> listFiscalPeriods(UUID companyId, UUID fiscalYearId) {
 FiscalYear fy = loadFiscalYear(companyId, fiscalYearId);
 return fiscalPeriodRepository.findByFiscalYearIdOrderByStartDateAsc(fy.getId());
 }

 @Transactional
 public FiscalPeriod lockFiscalPeriod(UUID companyId, UUID periodId) {
 FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
 .orElseThrow(() -> new NotFoundException("FiscalPeriod", periodId));
 if (!period.getCompanyId().equals(companyId)) {
 throw new NotFoundException("FiscalPeriod", periodId);
 }
 if (period.getStatus() == FiscalPeriodStatus.LOCKED) {
 throw new ConflictException("PERIOD_ALREADY_LOCKED", "Période déjà verrouillée");
 }
 period.setStatus(FiscalPeriodStatus.LOCKED);
 return fiscalPeriodRepository.save(period);
 }

 private FiscalYear loadFiscalYear(UUID companyId, UUID fiscalYearId) {
 FiscalYear fy = fiscalYearRepository.findById(fiscalYearId)
 .orElseThrow(() -> new NotFoundException("FiscalYear", fiscalYearId));
 if (!fy.getCompanyId().equals(companyId)) {
 throw new NotFoundException("FiscalYear", fiscalYearId);
 }
 return fy;
 }

 // --- Journaux ---

 @Transactional
 @org.springframework.cache.annotation.CacheEvict(value = "journals", allEntries = true)
 public Journal createJournal(UUID companyId, String code, String label) {
 if (code == null || code.isBlank()) {
 throw new ValidationException("JOURNAL_CODE_REQUIRED", "Le code du journal est requis");
 }
 if (label == null || label.isBlank()) {
 throw new ValidationException("JOURNAL_LABEL_REQUIRED", "Le libellé du journal est requis");
 }
 if (journalRepository.findByCompanyIdAndCode(companyId, code.trim()).isPresent()) {
 throw new ConflictException("JOURNAL_CODE_ALREADY_EXISTS",
 "Un journal avec le code '" + code + "' existe déjà");
 }
 Journal journal = new Journal();
 journal.setCompanyId(companyId);
 journal.setCode(code.trim().toUpperCase());
 journal.setLabel(label.trim());
 // V8.2déduire le type depuis le code si standard, sinon null (journal perso)
 jo.accountant.accountingengine.entity.JournalType inferredType =
 jo.accountant.accountingengine.entity.JournalType.fromCode(code);
 journal.setType(inferredType);
 journal.setActive(true);
 return journalRepository.save(journal);
 }

 /**
 * V8.2Récupère un journal par type, le crée s'il n'existe pas encore.
 *
 * <p>Remplace le pattern ad-hoc {@code journalRepository.findByCompanyIdAndCode(companyId, "VT")
 * .orElseThrow(JOURNAL_NOT_FOUND)} utilisé dans 8 modules métier. Le journal est créé
 * avec le code et le label par défaut du {@link jo.accountant.accountingengine.entity.JournalType},
 * ce qui rend toute opération métier (facturation, achat, paie, etc.) fonctionnelle même
 * si l'admin n'a pas pré-créé les journaux manuellement.
 *
 * <p><b>Idempotence</b> : si le journal existe déjà (par code), il est retourné tel quel.
 * La race condition entre deux appels concurrents est gérée via catch de
 * {@link ConflictException} ({@code JOURNAL_CODE_ALREADY_EXISTS}) — on recharge alors le
 * journal existant.
 *
 * <p><b>Note sur le type</b> : cette méthode cherche par code (le code par défaut du type).
 * Si l'admin a créé un journal personnalisé avec un code non-standard (ex: BQ1, BQ2 pour
 * plusieurs banques), il ne sera pas trouvé — il faudra alors utiliser
 * {@link #createJournal(UUID, String, String)} explicitement.
 *
 * @param companyId id de la société
 * @param type type de journal (VENTES, ACHATS, BANQUE, CAISSE, OD, PAIE, DEPENSES, FX)
 * @return le journal existant ou nouvellement créé (jamais null)
 */
 @Transactional
 @org.springframework.cache.annotation.CacheEvict(value = "journals", allEntries = true)
 public Journal getOrCreateJournal(UUID companyId,
 jo.accountant.accountingengine.entity.JournalType type) {
 if (type == null) {
 throw new ValidationException("JOURNAL_TYPE_REQUIRED",
 "Le type de journal est requis pour getOrCreateJournal");
 }
 String code = type.getDefaultCode();
 // 1. Lookup par code — retourne l'existant si présent
 var existing = journalRepository.findByCompanyIdAndCode(companyId, code);
 if (existing.isPresent()) {
 return existing.get();
 }
 // 2. Sinon, créer avec code + label par défaut du type
 try {
 Journal journal = new Journal();
 journal.setCompanyId(companyId);
 journal.setCode(code);
 journal.setLabel(type.getDefaultLabel());
 journal.setType(type);
 journal.setActive(true);
 LOG.info("Auto-création journal {} ({}) pour company {} (lazy creation)",
 code, type.name(), companyId);
 Journal saved = journalRepository.save(journal);
 // forcer le flush pour que le journal soit visible dans la DB avant
 // toute query ultérieure dans la même transaction. Sans ce flush, Hibernate
 // peut retarder l'INSERT jusqu'au prochain query, causant des incohérences
 // avec le cache @Cacheable qui retourne Optional.empty() stale.
 journalRepository.flush();
 return saved;
 } catch (ConflictException ex) {
 // JOURNAL_CODE_ALREADY_EXISTS — race condition : une autre requête a créé le journal
 // entre le check et le save. Recharger l'existant.
 LOG.debug("Race condition sur création journal {} — rechargement", code);
 return journalRepository.findByCompanyIdAndCode(companyId, code)
 .orElseThrow(() -> ex);
 }
 }

 // --- Écritures ---

 /**
 * Crée une écriture en DRAFT. Idempotente via la clé {@code idempotencyKey}.
 *
 * <p>L'écriture est créée en DRAFT — pas encore de {@code reference}, pas encore postée.
 * Le postage se fait via {@link #postJournalEntry}.
 */
 @Transactional
 public JournalEntryResponse createJournalEntry(UUID companyId, String idempotencyKey,
 CreateJournalEntryRequest req) {
 if (idempotencyKey == null || idempotencyKey.isBlank()) {
 throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED",
 "L'en-tête Idempotency-Key est obligatoire (§3.10)");
 }

 // §1 fix: removed the pre-check based on the shared
 // "active fiscal year" — it was a regression. The real validation is done later
 // via findPeriodForDate(companyId, req.entryDate()) which checks the ACTUAL
 // fiscal period/year for the entry's date, not a shared display preference.
 // "Exercice actif" is a read/display concept, never a write gate.

 // Idempotence : si une écriture existe déjà pour cette clé, la retourner
 Optional<JournalEntry> existing = journalEntryRepository
 .findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey);
 if (existing.isPresent()) {
 LOG.debug("Rejeu idempotent détecté : companyId={} key={}", companyId, idempotencyKey);
 return loadJournalEntryResponse(companyId, existing.get().getId());
 }

 // R2 (fix) : gérer la race condition sur la contrainte unique (company_id, idempotency_key)
 // Si deux requêtes concurrentes passent le check ci-dessus, la 2e échouera sur la contrainte DB.
 // On catch DataIntegrityViolationException et on recharge l'entry existante.

 // Valider les entrées
 // V8.2lazy creation : si le code correspond à un JournalType standard
 // (VT, AC, BQ, CA, OD, PA, DP, FX), on appelle directement getOrCreateJournal qui fait
 // son propre lookup + création (avec @CacheEvict). Pour les codes non-standards
 // (journaux personnalisés), on garde le comportement historique (404 JOURNAL_NOT_FOUND).
 // Note : on évite le double lookup (findByCompanyIdAndCode + getOrCreateJournal) qui
 // causait un bug de cache stale (@Cacheable retournait Optional.empty() même après création).
 jo.accountant.accountingengine.entity.JournalType knownType =
 jo.accountant.accountingengine.entity.JournalType.fromCode(req.journalCode());
 Journal journal;
 if (knownType != null) {
 journal = getOrCreateJournal(companyId, knownType);
 } else {
 journal = journalRepository.findByCompanyIdAndCode(companyId, req.journalCode())
 .orElseThrow(() -> new NotFoundException("JOURNAL_NOT_FOUND",
 "Journal introuvable : " + req.journalCode()
 + " (et ne correspond à aucun JournalType standard — créer via POST /journals)"));
 }

 // Trouver la période fiscale correspondant à entryDate
 FiscalPeriod period = findPeriodForDate(companyId, req.entryDate());
 if (period == null) {
 throw new NotFoundException("FISCAL_PERIOD_NOT_FOUND",
 "Aucune période fiscale trouvée pour la date " + req.entryDate());
 }
 if (period.getStatus() == FiscalPeriodStatus.LOCKED) {
 throw new ConflictException("PERIOD_LOCKED",
 "La période " + period.getLabel() + " est verrouillée");
 }
 FiscalYear fy = fiscalYearRepository.findById(period.getFiscalYearId())
 .orElseThrow(() -> new NotFoundException("FiscalYear", period.getFiscalYearId()));
 if (fy.getStatus() == FiscalYearStatus.CLOSED) {
 throw new ConflictException("FISCAL_YEAR_CLOSED",
 "L'exercice fiscal est CLOSED");
 }

 // Valider l'équilibre débit = crédit
 BigDecimal totalDebit = BigDecimal.ZERO;
 BigDecimal totalCredit = BigDecimal.ZERO;
 for (LineDto line : req.lines()) {
 totalDebit = totalDebit.add(line.debit() != null ? line.debit() : BigDecimal.ZERO);
 totalCredit = totalCredit.add(line.credit() != null ? line.credit() : BigDecimal.ZERO);
 }
 if (totalDebit.compareTo(totalCredit) != 0) {
 throw new ValidationException("UNBALANCED_ENTRY",
 "Écriture déséquilibrée : débit=" + totalDebit + " crédit=" + totalCredit);
 }

 // Valider les comptes existent et sont actifs
 Map<String, Account> accountByCode = new HashMap<>();
 for (LineDto line : req.lines()) {
 if (!accountByCode.containsKey(line.accountCode())) {
 Account account = accountRepository.findByCompanyIdAndCode(companyId, line.accountCode())
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte introuvable : " + line.accountCode()));
 accountByCode.put(line.accountCode(), account);
 }
 }

 // Créer l'écriture
 JournalEntry entry = new JournalEntry();
 entry.setCompanyId(companyId);
 entry.setJournalId(journal.getId());
 entry.setFiscalPeriodId(period.getId());
 entry.setEntryDate(req.entryDate());
 entry.setDescription(req.description());
 entry.setStatus(JournalEntryStatus.DRAFT);
 entry.setSourceModule(req.sourceModule() != null ? req.sourceModule() : JournalEntrySourceModule.MANUAL);
 entry.setIdempotencyKey(idempotencyKey);
 JournalEntry savedEntry;
 try {
 savedEntry = journalEntryRepository.save(entry);
 } catch (org.springframework.dao.DataIntegrityViolationException ex) {
 // R2 (fix) : race condition — une autre requête a créé l'entry entre le check et le save
 LOG.debug("Race condition idempotence détectée : key={}", idempotencyKey);
 JournalEntry raceExisting = journalEntryRepository
 .findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey)
 .orElseThrow(() -> ex);
 return loadJournalEntryResponse(companyId, raceExisting.getId());
 }

 // Créer les lignes
 int lineNumber = 1;
 for (LineDto line : req.lines()) {
 Account account = accountByCode.get(line.accountCode());
 JournalLine jl = new JournalLine();
 jl.setCompanyId(companyId);
 jl.setJournalEntryId(savedEntry.getId());
 jl.setAccountId(account.getId());
 jl.setAccountCode(account.getCode());
 jl.setThirdPartyId(line.thirdPartyId());
 jl.setDebit(line.debit() != null ? line.debit() : BigDecimal.ZERO);
 jl.setCredit(line.credit() != null ? line.credit() : BigDecimal.ZERO);
 jl.setLineNumber(lineNumber++);
 jl.setDescription(line.description());
 // ──Finding HAUT — multi-devises effective ──
 // Avant : les champs amountTransactionCurrency / transactionCurrency / exchangeRateUsed
 // étaient systématiquement valorisés avec le montant en devise fonctionnelle et un
 // taux de 1 (mono-devise factice). Toute facture en devise étrangère était doublement
 // fausse : le montant était converti en HTG puis stocké comme "devise de transaction"
 // sans distinguer la devise d'origine.
 // Maintenant : si l'appelant fournit transactionCurrency + exchangeRateUsed + amountTransactionCurrency,
 // on les persiste tels quels (multi-devises effective). Sinon, on retombe sur le
 // comportement mono-devise historique (rétro-compatibilité).
 BigDecimal lineAmount = jl.getDebit().compareTo(BigDecimal.ZERO) > 0
 ? jl.getDebit() : jl.getCredit();
 if (line.transactionCurrency() != null && !line.transactionCurrency().isBlank()
 && line.amountTransactionCurrency() != null) {
 // Multi-devises effective : l'appelant a fourni les 3 champs
 jl.setAmountTransactionCurrency(line.amountTransactionCurrency());
 jl.setTransactionCurrency(line.transactionCurrency().toUpperCase());
 jl.setExchangeRateUsed(line.exchangeRateUsed() != null
 ? line.exchangeRateUsed() : BigDecimal.ONE);
 } else {
 // Mono-devise (rétro-compat) : on snapshot les montants en devise fonctionnelle
 jl.setAmountTransactionCurrency(lineAmount);
 // transactionCurrency reste null (devise fonctionnelle implicite)
 // exchangeRateUsed reste BigDecimal.ONE (défault du constructeur JournalLine)
 }
 JournalLine savedLine = journalLineRepository.save(jl);

 // Tags analytiques (validés plus en détail au postage, mais on les persiste dès le brouillon)
 for (AnalyticalTagDto tag : line.analyticalTags()) {
 JournalLineAnalyticalTag jlat = new JournalLineAnalyticalTag();
 jlat.setId(UUID.randomUUID());
 jlat.setCompanyId(companyId);
 jlat.setJournalLineId(savedLine.getId());
 jlat.setPlanId(tag.planId());
 jlat.setValueId(tag.valueId());
 jlat.setAllocationPercentage(tag.allocationPercentage());
 tagRepository.save(jlat);
 }
 }

 return loadJournalEntryResponse(companyId, savedEntry.getId());
 }

 /**
 * Poste une écriture DRAFT — transition vers POSTED ou PENDING_APPROVAL.
 *
 * <p><b>(refactor batch C) — extraction du God class</b> : le corps de cette
 * méthode a été extrait vers
 * {@link JournalEntryLifecycleService#postJournalEntry(UUID, UUID, List)}. Cette façade
 * publique est conservée pour préserver l'API (tests d'intégration + controller + 9 modules
 * métier consommateurs : invoicing, purchasing, fixed-assets, inventory, payroll,
 * funds-grants, expenses, fx-operations, fiscal-year-closing) — la signature et le contrat
 * {@code @Transactional} sont inchangés.
 *
 * @param approverEmails emails des approbateurs éligibles (résolus par l'appelant —
 * voir décisiondans le worklog)
 * @see JournalEntryLifecycleService#postJournalEntry(UUID, UUID, List)
 */
 @Transactional
 public JournalEntryResponse postJournalEntry(UUID companyId, UUID entryId, List<String> approverEmails) {
 return journalEntryLifecycleService.postJournalEntry(companyId, entryId, approverEmails);
 }

 /**
 * Finalise le postage d'une écriture après approbation — délègue à
 * {@link JournalEntryLifecycleService#postJournalEntryAfterApproval(UUID, UUID)}.
 *
 * @see JournalEntryLifecycleService#postJournalEntryAfterApproval(UUID, UUID)
 */
 @Transactional
 public JournalEntryResponse postJournalEntryAfterApproval(UUID companyId, UUID entryId) {
 return journalEntryLifecycleService.postJournalEntryAfterApproval(companyId, entryId);
 }

 /**
 * Repasse une écriture {@code PENDING_APPROVAL} à {@code DRAFT} après rejet — délègue à
 * {@link JournalEntryLifecycleService#revertToDraftAfterRejection(UUID, UUID)}.
 *
 * @see JournalEntryLifecycleService#revertToDraftAfterRejection(UUID, UUID)
 */
 @Transactional
 public JournalEntryResponse revertToDraftAfterRejection(UUID companyId, UUID entryId) {
 return journalEntryLifecycleService.revertToDraftAfterRejection(companyId, entryId);
 }

 // (refactor batch C) — onApprovalDecided listener et validateAnalyticalTags ont
 // été déplacés vers JournalEntryLifecycleService (cohésion : ils ne manipulent que des
 // écritures en PENDING_APPROVAL et leur cycle de vie). Le listener y est enregistré comme
 // @EventListener sur le bean JournalEntryLifecycleService — Spring le détecte automatiquement.

 /**
 * Contre-passe une écriture POSTED — crée une nouvelle écriture inversée et marque
 * l'originale comme VOIDED.
 *
 * <p><b>(refactor batch C)</b> : délègue à
 * {@link JournalEntryLifecycleService#reverseJournalEntry(UUID, UUID, String)}.
 * L'originale passe à VOIDED mais conserve son {@code reference} — règle de
 * numérotation sans trou (§6).
 */
 @Transactional
 public JournalEntryResponse reverseJournalEntry(UUID companyId, UUID entryId, String reason) {
 return journalEntryLifecycleService.reverseJournalEntry(companyId, entryId, reason);
 }

 /**
 * Contre-passe une écriture POSTED avec date de contre-passation paramétrable — délègue à
 * {@link JournalEntryLifecycleService#reverseJournalEntry(UUID, UUID, String, LocalDate)}.
 *
 * <p><b>FIX</b> : la version originale utilisait
 * {@code LocalDate.now()} comme date de contre-passation. Désormais, on accepte une date
 * paramétrable et on vérifie que la période correspondante est OPEN.
 *
 * @param reversalDate date de la contre-passation (null = date du jour). Doit tomber dans
 * une période fiscale OPEN.
 * @see JournalEntryLifecycleService#reverseJournalEntry(UUID, UUID, String, LocalDate)
 */
 @Transactional
 public JournalEntryResponse reverseJournalEntry(UUID companyId, UUID entryId, String reason,
 LocalDate reversalDate) {
 return journalEntryLifecycleService.reverseJournalEntry(companyId, entryId, reason, reversalDate);
 }

 // --- Clôture d'exercice (Vague 2, item 2.4) ---

 /**
 * Génère et poste les écritures de clôture d'exercice (Vague 2, item 2.4).
 *
 * <p><b>(refactor batch 2) — extraction du God class</b> : le corps de cette
 * méthode (≈280 lignes : calcul du résultat net, écriture de clôture, écriture d'ouverture
 * N+1, verrouillage des périodes, auto-switch de l'exercice actif) a été extrait vers
 * {@link FiscalYearClosingService#closeFiscalYear(UUID, UUID)}. Cette façade publique est
 * conservée pour préserver l'API (tests d'intégration + controller) — la signature et le
 * contrat {@code @Transactional} sont inchangés.
 *
 * <p>Étapes (déléguées) :
 * <ol>
 * <li>Calcule le résultat net = Produits − Charges pour l'exercice.</li>
 * <li>Si résultat positif (bénéfice) : Débit comptes de produits (solde → 0),
 * Crédit compte "Résultat de l'exercice" (capitaux propres).</li>
 * <li>Si résultat négatif (perte) : Débit compte "Résultat de l'exercice",
 * Crédit comptes de charges (solde → 0).</li>
 * <li>Poste l'écriture avec sourceModule=MANUAL et description "Clôture exercice {year}".</li>
 * <li>Génère l'écriture d'ouverture N+1 (à-nouveau —.</li>
 * <li>Verrouille l'exercice (CLOSED) + toutes ses périodes (LOCKED).</li>
 * <li>Auto-switch l'exercice actif sur le prochain OPEN (ou vide le pointeur).</li>
 * </ol>
 *
 * @return l'écriture de clôture postée
 * @see FiscalYearClosingService#closeFiscalYear(UUID, UUID)
 */
 @Transactional
 public JournalEntryResponse closeFiscalYear(UUID companyId, UUID fiscalYearId) {
 return fiscalYearClosingService.closeFiscalYear(companyId, fiscalYearId);
 }

 // --- Lectures ---

 /**
 * Charge une écriture + ses lignes + ses tags analytiques, formaté en
 * {@link JournalEntryResponse} pour l'API.
 *
 * <p><b>(refactor batch C)</b> : délègue à
 * {@link JournalEntryLifecycleService#loadJournalEntryResponse(UUID, UUID)}. Méthode
 * partagée entre {@link #createJournalEntry}, {@link #listJournalEntries},
 * {@link #getJournalEntry}, {@link #searchJournalEntries} (qui restent dans ce service) et
 * les méthodes de cycle de vie {@code postJournalEntry} / {@code reverseJournalEntry}
 * (déléguées au lifecycle service).
 *
 * @see JournalEntryLifecycleService#loadJournalEntryResponse(UUID, UUID)
 */
 @Transactional(readOnly = true)
 public JournalEntryResponse loadJournalEntryResponse(UUID companyId, UUID entryId) {
 return journalEntryLifecycleService.loadJournalEntryResponse(companyId, entryId);
 }

 @Transactional(readOnly = true)
 public List<JournalEntryResponse> listJournalEntries(UUID companyId) {
 return journalEntryRepository.findByCompanyIdOrderByEntryDateDesc(companyId).stream()
 .map(e -> loadJournalEntryResponse(companyId, e.getId()))
 .toList();
 }

 /**
 * Fix Dim 5 C1 (audit v9.4) — Liste des écritures filtrée par exercice fiscal.
 *
 * <p>Si {@code fiscalYearId} est fourni, on filtre par les dates [start, end] de cet
 * exercice (permet de consulter un exercice clôturé). Sinon, on résout l'exercice actif
 * (OPEN contenant aujourd'hui) et on filtre par ses dates.
 *
 * <p>Avant ce fix, {@code GET /journal-entries} retournait TOUT l'historique de l'entreprise
 * mélangé — inutilisable pour un comptable sur une entreprise mature (5 ans × 10K écritures).
 *
 * @param companyId identifiant du tenant
 * @param fiscalYearId identifiant de l'exercice à consulter (null = exercice actif)
 * @return la liste des écritures de l'exercice, triées par date décroissante
 */
 @Transactional(readOnly = true)
 public List<JournalEntryResponse> listJournalEntries(UUID companyId, java.util.UUID fiscalYearId) {
 java.util.Optional<FiscalYear> fy = resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isEmpty()) {
 // Pas d'exercice trouvé — retourner liste vide plutôt que tout l'historique
 LOG.warn("listJournalEntries: aucun exercice trouvé pour company {} (fiscalYearId={}) — retour vide",
 companyId, fiscalYearId);
 return List.of();
 }
 LocalDate from = fy.get().getStartDate();
 LocalDate to = fy.get().getEndDate();
 return journalEntryRepository
 .searchEntries(companyId, from, to, null, null, null,
 org.springframework.data.domain.PageRequest.of(0, 200))
 .stream()
 .map(e -> loadJournalEntryResponse(companyId, e.getId()))
 .toList();
 }

 /**
 * Récupère une écriture par son ID — correction 2026-07-26.
 *
 * <p>Avant, le mobile ne pouvait pas récupérer une écriture par ID (uniquement via la
 * liste complète). Le deep-linking depuis une notification échouait si l'écriture n'était
 * pas déjà en cache côté mobile. Cet endpoint permet au mobile de fetch une écriture
 * à la demande.
 *
 * @throws jo.accountant.core.exception.NotFoundException si l'écriture n'existe pas ou
 * n'appartient pas à l'entreprise
 */
 @Transactional(readOnly = true)
 public JournalEntryResponse getJournalEntry(UUID companyId, UUID entryId) {
 return loadJournalEntryResponse(companyId, entryId);
 }

 /**
 * Liste paginée des écritures (audit M8).
 *
 * <p>Sur une entreprise avec plusieurs exercices d'historique, la liste complète peut
 * contenir des milliers d'écritures. Cette méthode paginée permet au client mobile de
 * charger par batches (50 par défaut, max 200).
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<JournalEntryResponse> listJournalEntries(
 UUID companyId, org.springframework.data.domain.Pageable pageable) {
 return journalEntryRepository
 .findByCompanyIdOrderByEntryDateDesc(companyId, pageable)
 .map(e -> loadJournalEntryResponse(companyId, e.getId()));
 }

 /**
 * Recherche filtrée et paginée des écritures (audit M8 — filtres multi-dimensionnels).
 *
 * <p>Combinaison de filtres optionnels : plage de dates (from/to), code journal,
 * module source, statut. Tous les filtres sont combinés par AND.
 *
 * @param companyId identifiant du tenant
 * @param from date d'écriture minimale (inclusive, optionnel)
 * @param to date d'écriture maximale (inclusive, optionnel)
 * @param journalCode code du journal (ex. "VT", "AC"), optionnel
 * @param sourceModule module d'origine (MANUAL, INVOICING, PURCHASING, etc.), optionnel
 * @param status statut de l'écriture (DRAFT, PENDING_APPROVAL, POSTED, VOIDED), optionnel
 * @param pageable pagination (page, size) — size max 200
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<JournalEntryResponse> searchJournalEntries(
 UUID companyId, LocalDate from, LocalDate to, String journalCode,
 JournalEntrySourceModule sourceModule, JournalEntryStatus status,
 org.springframework.data.domain.Pageable pageable) {
 // Default to resolved fiscal year dates when from/to are null
 if (from == null && to == null) {
 java.util.Optional<FiscalYear> fy = resolveFiscalYear(companyId, null);
 if (fy.isPresent()) {
 from = fy.get().getStartDate();
 to = fy.get().getEndDate();
 }
 }
 return journalEntryRepository
 .searchEntries(companyId, from, to, journalCode, sourceModule, status, pageable)
 .map(e -> loadJournalEntryResponse(companyId, e.getId()));
 }

 /**
 * Fix Dim 5 C1 (audit v9.4) — Surcharge avec fiscalYearId explicite.
 *
 * <p>Si {@code fiscalYearId} est fourni, on filtre par les dates [start, end] de cet
 * exercice (permet de consulter un exercice clôturé). Les autres filtres (from/to,
 * journalCode, sourceModule, status) sont combinés par AND avec les dates de l'exercice.
 *
 * <p>Si {@code fiscalYearId} est null, on délègue à la méthode historique
 * {@link #searchJournalEntries(UUID, LocalDate, LocalDate, String, JournalEntrySourceModule, JournalEntryStatus, Pageable)}.
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<JournalEntryResponse> searchJournalEntries(
 UUID companyId, LocalDate from, LocalDate to, String journalCode,
 JournalEntrySourceModule sourceModule, JournalEntryStatus status,
 java.util.UUID fiscalYearId,
 org.springframework.data.domain.Pageable pageable) {
 if (fiscalYearId == null) {
 return searchJournalEntries(companyId, from, to, journalCode, sourceModule, status, pageable);
 }
 // Résoudre l'exercice et fusionner les dates
 java.util.Optional<FiscalYear> fy = resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isEmpty()) {
 LOG.warn("searchJournalEntries: exercice {} introuvable pour company {} — retour vide",
 fiscalYearId, companyId);
 return org.springframework.data.domain.Page.empty(pageable);
 }
 LocalDate fyFrom = fy.get().getStartDate();
 LocalDate fyTo = fy.get().getEndDate();
 // Si from/to fournis en plus, on intersecte (prend le plus restrictif)
 LocalDate effectiveFrom = from != null ? from : fyFrom;
 LocalDate effectiveTo = to != null ? to : fyTo;
 // Garantir qu'on ne dépasse pas les bornes de l'exercice
 if (effectiveFrom.isBefore(fyFrom)) effectiveFrom = fyFrom;
 if (effectiveTo.isAfter(fyTo)) effectiveTo = fyTo;
 return journalEntryRepository
 .searchEntries(companyId, effectiveFrom, effectiveTo, journalCode, sourceModule, status, pageable)
 .map(e -> loadJournalEntryResponse(companyId, e.getId()));
 }

 /**
 * Keyset pagination des écritures.
 *
 * <p>Alternative à {@link #listJournalEntries(UUID, org.springframework.data.domain.Pageable)}
 * qui utilise un curseur (afterEntryDate, afterId) au lieu d'un OFFSET. Sur les pages
 * profondes (page 1000+ sur 10M d'écritures), la latence est constante (~1-5ms) au lieu
 * de croître linéairement avec le numéro de page (OFFSET 50000 = ~500ms).
 *
 * <p><b>Usage</b> :
 * <ol>
 * <li>Première page : {@code getKeysetPage(companyId, null, null, 50)}</li>
 * <li>Pages suivantes : {@code getKeysetPage(companyId,
 * response.nextAfterEntryDate(), response.nextAfterId(), 50)} tant que
 * {@code response.hasNext()} est {@code true}.</li>
 * </ol>
 *
 * <p><b>Limite connue</b> : pas de filtres (from/to/journalCode/sourceModule/status) pour
 * cette V1 — le keyset avec filtres ajouterait de la complexité dans l'index composite.
 * L'endpoint OFFSET {@code /journal-entries/search} reste disponible pour les requêtes
 * filtrées.
 *
 * @param companyId identifiant du tenant
 * @param afterEntryDate date d'écriture du curseur (null pour la première page)
 * @param afterId ID du curseur (null pour la première page)
 * @param size taille de page (max 200, défaut 50 si &lt;= 0)
 * @return un {@link KeysetPage} contenant la page et le curseur suivant
 */
 @Transactional(readOnly = true)
 public KeysetPage<JournalEntryResponse> getKeysetPage(UUID companyId,
 LocalDate afterEntryDate,
 UUID afterId,
 int size) {
 if (size <= 0 || size > 200) {
 size = 50;
 }
 // Technique du "LIMIT N+1" : on demande size+1 éléments pour détecter hasNext de façon
 // certaine (sans COUNT(*), coûteux sur 10M lignes). Si la DB retourne size+1 éléments,
 // il y a une page suivante ; on tronque ensuite à size pour le client.
 org.springframework.data.domain.Pageable pageRequest =
 org.springframework.data.domain.PageRequest.of(0, size + 1);
 List<JournalEntry> entries = journalEntryRepository
 .findKeysetAfter(companyId, afterEntryDate, afterId, pageRequest);

 boolean hasNext = entries.size() > size;
 List<JournalEntry> truncated = hasNext
 ? entries.subList(0, size)
 : entries;

 List<JournalEntryResponse> responses = truncated.stream()
 .map(e -> loadJournalEntryResponse(companyId, e.getId()))
 .toList();

 if (responses.isEmpty()) {
 return new KeysetPage<>(List.of(), null, null, false);
 }
 JournalEntryResponse last = responses.get(responses.size() - 1);
 return new KeysetPage<>(responses, last.entryDate(), last.id(), hasNext);
 }

 @Transactional(readOnly = true)
 public List<LedgerLine> getLedger(UUID companyId, UUID accountId, LocalDate from, LocalDate to) {
 // Valider que le compte existe et appartient à l'entreprise
 Account account = accountRepository.findById(accountId)
 .orElseThrow(() -> new NotFoundException("Account", accountId));
 if (!account.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", accountId);
 }

 List<JournalLine> lines = journalLineRepository.findLedger(companyId, accountId, from, to);
 List<LedgerLine> result = new ArrayList<>();
 BigDecimal runningBalance = BigDecimal.ZERO;
 for (JournalLine line : lines) {
 runningBalance = runningBalance.add(line.getDebit()).subtract(line.getCredit());
 // Charger la date et le reference de l'écriture
 //— defense-in-depth : filtrer par companyId
 JournalEntry entry = journalEntryRepository.findById(line.getJournalEntryId())
 .filter(e -> e.getCompanyId().equals(companyId))
 .orElse(null);
 if (entry != null) {
 result.add(new LedgerLine(
 entry.getEntryDate(), entry.getReference(), entry.getDescription(),
 line.getAccountCode(), line.getDebit(), line.getCredit(),
 runningBalance, entry.getId()));
 }
 }
 return result;
 }

 /**
 * Balance générale — toutes les écritures POSTED de l'entreprise, agrégées par compte.
 *
 * <p>L'invariant : somme des débits = somme des crédits (testé explicitement avec
 * injection volontaire d'un déséquilibre pour prouver que le contrôle bloque).
 */
 @Transactional(readOnly = true)
 public List<TrialBalanceLine> getTrialBalance(UUID companyId) {
 java.util.Optional<FiscalYear> fy = resolveFiscalYear(companyId, null);
 if (fy.isEmpty()) {
 throw new NotFoundException("NO_FISCAL_YEAR",
 "Aucun exercice fiscal disponible. Créez un exercice ou spécifiez ?fiscalYearId=.");
 }
 return getTrialBalance(companyId, fy.get().getStartDate(), fy.get().getEndDate());
 }

 @Transactional(readOnly = true)
 public List<TrialBalanceLine> getTrialBalance(UUID companyId, UUID fiscalYearId) {
 java.util.Optional<FiscalYear> fy = resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isEmpty()) {
 throw new NotFoundException("NO_FISCAL_YEAR",
 "Aucun exercice fiscal disponible.");
 }
 return getTrialBalance(companyId, fy.get().getStartDate(), fy.get().getEndDate());
 }

 @Transactional(readOnly = true)
 public List<TrialBalanceLine> getTrialBalance(UUID companyId, LocalDate from, LocalDate to) {
 //agrégation SQL au lieu de charger toutes les lignes en Java.
 // Avant : findAllPostedBetweenDates ou findAllPosted chargeaient toutes les lignes POSTED
 // en mémoire puis agrégeaient en Java via TrialBalanceAccumulator. Sur 100K+ lignes :
 // heap > 50 MB, latence > 3s. Maintenant : 1 requête SQL GROUP BY account_id, ~100 lignes.
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> aggregates;
 if (from != null && to != null) {
 aggregates = journalLineRepository.aggregateByAccountBetweenDates(companyId, from, to);
 } else {
 // Pas de filtre date — équivalent de findAllPosted
 aggregates = journalLineRepository.aggregateByAccountBetweenDates(companyId, null, null);
 }

 List<TrialBalanceLine> result = new ArrayList<>();
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate acc : aggregates) {
 //— defense-in-depth : filtrer par companyId
 String label = accountRepository.findById(acc.getAccountId())
 .filter(a -> a.getCompanyId().equals(companyId))
 .map(Account::getLabel).orElse("(compte supprimé)");
 BigDecimal debit = acc.getTotalDebit() != null ? acc.getTotalDebit() : BigDecimal.ZERO;
 BigDecimal credit = acc.getTotalCredit() != null ? acc.getTotalCredit() : BigDecimal.ZERO;
 result.add(new TrialBalanceLine(
 acc.getAccountId(),
 acc.getAccountCode() != null ? acc.getAccountCode() : "(code inconnu)",
 label,
 debit, credit, debit.subtract(credit)));
 }
 return result;
 }

 // --- Helpers ---

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

 /** Accumulateur interne pour la balance générale. */
 private static class TrialBalanceAccumulator {
 final UUID accountId;
 final String accountCode;
 BigDecimal debit = BigDecimal.ZERO;
 BigDecimal credit = BigDecimal.ZERO;

 TrialBalanceAccumulator(UUID accountId, String accountCode) {
 this.accountId = accountId;
 this.accountCode = accountCode;
 }
 }
}

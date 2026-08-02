package jo.accountant.expenses.service;

import java.math.BigDecimal;
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
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.expenses.dto.CreateExpenseReportRequest;
import jo.accountant.expenses.dto.ExpenseReportResponse;
import jo.accountant.expenses.entity.ExpenseCategory;
import jo.accountant.expenses.entity.ExpenseLine;
import jo.accountant.expenses.entity.ExpenseReport;
import jo.accountant.expenses.entity.ExpenseReportStatus;
import jo.accountant.expenses.repository.ExpenseCategoryRepository;
import jo.accountant.expenses.repository.ExpenseLineRepository;
import jo.accountant.expenses.repository.ExpenseReportRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des notes de frais (module :expenses).
 *
 * <p>Cycle de vie : DRAFT (création, lignes éditables) → SUBMITTED (verrouillé, en attente
 * d'approbation) → APPROVED (génère l'écriture comptable) → PAID (paiement effectif).
 * REJECTED est possible au stade SUBMITTED (revient à DRAFT pour correction).
 *
 * <p><b>Choix d'approbation</b> (§2.2 du prompt) : délègue à `JOURNAL_ENTRY_POST` plutôt
 * que de réinventer un `ApprovalActionType` dédié — cohérent avec `:invoicing`,
 * `:fixed-assets`, `:inventory`. La transition APPROVED → génération d'écriture se fait en
 * une seule étape côté service (la validation par seuil reste gérée par
 * `:accounting-engine` au postage).
 *
 * <p><b>Écriture comptable générée à l'approbation</b> :
 * <ul>
 * <li>Débit Charges (par ligne, sur `expenseAccountId` ou fallback générique) pour le total.</li>
 * <li>Si {@code paidDirectly = false} : Crédit Tiers-Employé (compte dédié du tiers) pour
 * le total. L'employé a une créance à recevoir.</li>
 * <li>Si {@code paidDirectly = true} : Crédit Trésorerie (compte ACTIF marqué
 * `taxMappingCode = "CASH"`, fallback SYSCOHADA `"570000"/"57"`) pour le total.</li>
 * </ul>
 *
 * <p><b>Code journal `DP` (dépenses)</b> — doit exister (sinon `422 JOURNAL_DP_NOT_FOUND`).
 */
@Service
public class ExpensesService {

 private static final Logger LOG = LoggerFactory.getLogger(ExpensesService.class);

 private final ExpenseReportRepository reportRepository;
 private final ExpenseLineRepository lineRepository;
 private final ExpenseCategoryRepository categoryRepository;
 private final ThirdPartyRepository thirdPartyRepository;
 private final AccountRepository accountRepository;
 private final JournalRepository journalRepository;
 private final AccountingEngineService accountingEngineService;
 // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
 private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;

 public ExpensesService(ExpenseReportRepository reportRepository,
 ExpenseLineRepository lineRepository,
 ExpenseCategoryRepository categoryRepository,
 ThirdPartyRepository thirdPartyRepository,
 AccountRepository accountRepository,
 JournalRepository journalRepository,
 AccountingEngineService accountingEngineService,
 jo.accountant.chartofaccounts.service.AccountResolver accountResolver) {
 this.reportRepository = reportRepository;
 this.lineRepository = lineRepository;
 this.categoryRepository = categoryRepository;
 this.thirdPartyRepository = thirdPartyRepository;
 this.accountRepository = accountRepository;
 this.journalRepository = journalRepository;
 this.accountingEngineService = accountingEngineService;
 this.accountResolver = accountResolver;
 }

 // --- Création ---

 @Transactional
 public ExpenseReportResponse create(UUID companyId, CreateExpenseReportRequest req) {
 ThirdParty tp = null;
 if (req.thirdPartyId() != null) {
 tp = loadEmployee(companyId, req.thirdPartyId());
 }

 ExpenseReport report = new ExpenseReport();
 report.setCompanyId(companyId);
 report.setThirdPartyId(tp != null ? tp.getId() : null);
 report.setStatus(ExpenseReportStatus.DRAFT);
 report.setExpenseDate(req.expenseDate());
 report.setCurrency(req.currency() != null ? req.currency().toUpperCase() : "HTG");
 report.setDescription(req.description());
 report.setPaidDirectly(req.paidDirectly());
 report.setTotalAmount(BigDecimal.ZERO);
 ExpenseReport saved = reportRepository.save(report);

 BigDecimal total = BigDecimal.ZERO;
 Map<String, BigDecimal> newAmountsByCategory = new HashMap<>();
 for (var lineDto : req.lines()) {
 ExpenseLine line = new ExpenseLine();
 line.setCompanyId(companyId);
 line.setReportId(saved.getId());
 line.setCategory(lineDto.category());
 line.setDescription(lineDto.description());
 line.setAmount(lineDto.amount());
 line.setExpenseAccountId(lineDto.expenseAccountId());
 lineRepository.save(line);
 total = total.add(lineDto.amount());
 if (lineDto.category() != null) {
 newAmountsByCategory.merge(lineDto.category(), lineDto.amount(), BigDecimal::add);
 }
 }
 saved.setTotalAmount(total);
 reportRepository.save(saved);

 // valider les plafonds journaliers/mensuels par catégorie
 validateCategoryLimits(companyId, req.expenseDate(), newAmountsByCategory);

 LOG.info("Note de frais créée : id={} total={} employé={}",
 saved.getId(), total, tp != null ? tp.getName() : "(aucun)");
 return loadResponse(companyId, saved.getId());
 }

 // --- Soumission ---

 @Transactional
 public ExpenseReportResponse submit(UUID companyId, UUID reportId) {
 ExpenseReport report = loadReport(companyId, reportId);
 if (report.getStatus() != ExpenseReportStatus.DRAFT) {
 throw new ConflictException("EXPENSE_NOT_DRAFT",
 "Seules les notes DRAFT peuvent être soumises. Statut : " + report.getStatus());
 }
 if (report.isPaidDirectly() == false && report.getThirdPartyId() == null) {
 throw new ValidationException("EMPLOYEE_REQUIRED_FOR_REIMBURSEMENT",
 "Une note de frais à rembourser (paidDirectly=false) doit être rattachée " +
 "à un tiers employé.");
 }

 // re-valider les plafonds journaliers/mensuels à la soumission (le
 // contexte a pu changer entre la création DRAFT et la soumission : autres notes
 // soumises entre-temps, plafonds reconfigurés, etc.).
 Map<String, BigDecimal> amountsByCategory = new HashMap<>();
 for (ExpenseLine line : lineRepository.findByReportIdOrderByCreatedAt(report.getId())) {
 if (line.getCategory() != null) {
 amountsByCategory.merge(line.getCategory(), line.getAmount(), BigDecimal::add);
 }
 }
 validateCategoryLimits(companyId, report.getExpenseDate(), amountsByCategory);

 report.setStatus(ExpenseReportStatus.SUBMITTED);
 reportRepository.save(report);
 LOG.info("Note de frais soumise : id={}", report.getId());
 return loadResponse(companyId, report.getId());
 }

 // --- Approbation (génère l'écriture) ---

 @Transactional
 public ExpenseReportResponse approve(UUID companyId, UUID reportId) {
 ExpenseReport report = loadReport(companyId, reportId);
 if (report.getStatus() != ExpenseReportStatus.SUBMITTED) {
 throw new ConflictException("EXPENSE_NOT_SUBMITTED",
 "Seules les notes SUBMITTED peuvent être approuvées. Statut : " + report.getStatus());
 }
 generateExpenseEntry(companyId, report);
 report.setStatus(ExpenseReportStatus.APPROVED);
 reportRepository.save(report);
 LOG.info("Note de frais approuvée : id={} entry={}", report.getId(), report.getJournalEntryId());
 return loadResponse(companyId, report.getId());
 }

 /**
 * Génère l'écriture comptable d'approbation d'une note de frais.
 *
 * <p>Débit : Charges (par ligne, sur `expenseAccountId` ou fallback générique).
 * Crédit : Tiers-Employé (si paidDirectly=false) ou Trésorerie (si paidDirectly=true).
 */
 private void generateExpenseEntry(UUID companyId, ExpenseReport report) {
 List<ExpenseLine> lines = lineRepository.findByReportIdOrderByCreatedAt(report.getId());
 if (lines.isEmpty()) {
 throw new ValidationException("EXPENSE_HAS_NO_LINES",
 "La note de frais " + report.getId() + " n'a aucune ligne.");
 }

 Map<String, BigDecimal> chargesByAccount = new HashMap<>();
 for (ExpenseLine line : lines) {
 Account chargeAccount = resolveChargeAccount(companyId, line.getExpenseAccountId());
 chargesByAccount.merge(chargeAccount.getCode(), line.getAmount(), BigDecimal::add);
 }

 // V8.2 Phase 3 — getOrCreateJournal retourne le journal existant ou le crée avec
 // le code/label par défaut du type (jamais d'exception pour les types standards).
 String journalCode = accountingEngineService.getOrCreateJournal(companyId,
 jo.accountant.accountingengine.entity.JournalType.DEPENSES).getCode();

 List<LineDto> entryLines = new ArrayList<>();
 for (var entry : chargesByAccount.entrySet()) {
 entryLines.add(new LineDto(entry.getKey(), null,
 entry.getValue(), null,
 "Note de frais — " + report.getDescription(), List.of()));
 }

 if (report.isPaidDirectly()) {
 // Crédit Trésorerie — résolution référentiel-agnostique via AccountResolver (audit #3)
 Account cashAccount = accountResolver.resolveOrThrow(
 companyId, ReportingClass.ACTIF, "CASH",
 "CASH_ACCOUNT_NOT_FOUND",
 "Aucun compte de trésorerie trouvé. Configurer un compte ACTIF " +
 "marqué taxMappingCode=\"CASH\" dans le plan comptable.",
 "570000", "57");
 entryLines.add(new LineDto(cashAccount.getCode(), null,
 null, report.getTotalAmount(),
 "Décaissement — Note de frais " + report.getId(), List.of()));
 } else {
 // Crédit Tiers-Employé (compte dédié du tiers)
 ThirdParty tp = thirdPartyRepository.findById(report.getThirdPartyId())
 .orElseThrow(() -> new ValidationException("THIRD_PARTY_NOT_FOUND",
 "Tiers employé introuvable : " + report.getThirdPartyId()));
 // Audit v4.7 §6.2 — defense-in-depth
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", report.getThirdPartyId().toString());
 }
 UUID employeeAccountId = tp.getDedicatedAccountId() != null
 ? tp.getDedicatedAccountId() : tp.getCollectiveAccountId();
 Account employeeAccount = accountRepository.findById(employeeAccountId)
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte employé introuvable"));
 // Audit v4.7 §6.2 — defense-in-depth
 if (!employeeAccount.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", employeeAccountId.toString());
 }
 entryLines.add(new LineDto(employeeAccount.getCode(), tp.getId(),
 null, report.getTotalAmount(),
 "Employé à rembourser — Note de frais " + report.getId(), List.of()));
 }

 CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
 journalCode, report.getExpenseDate(),
 "Note de frais " + report.getId() + " — " + report.getDescription(),
 entryLines, JournalEntrySourceModule.EXPENSES);

 JournalEntryResponse entry = accountingEngineService.createJournalEntry(
 companyId, "expenses-" + report.getId(), entryReq);
 JournalEntryResponse posted = accountingEngineService.postJournalEntry(
 companyId, entry.id(), List.of());

 report.setJournalEntryId(posted.id());
 }

 // --- Rejet ---

 @Transactional
 public ExpenseReportResponse reject(UUID companyId, UUID reportId) {
 ExpenseReport report = loadReport(companyId, reportId);
 if (report.getStatus() != ExpenseReportStatus.SUBMITTED) {
 throw new ConflictException("EXPENSE_NOT_SUBMITTED",
 "Seules les notes SUBMITTED peuvent être rejetées. Statut : " + report.getStatus());
 }
 report.setStatus(ExpenseReportStatus.REJECTED);
 reportRepository.save(report);
 LOG.info("Note de frais rejetée : id={}", report.getId());
 return loadResponse(companyId, report.getId());
 }

 // --- Paiement effectif (APPROVED → PAID) ---

 @Transactional
 public ExpenseReportResponse pay(UUID companyId, UUID reportId) {
 ExpenseReport report = loadReport(companyId, reportId);
 if (report.getStatus() != ExpenseReportStatus.APPROVED) {
 throw new ConflictException("EXPENSE_NOT_APPROVED",
 "Seules les notes APPROVED peuvent être payées. Statut : " + report.getStatus());
 }
 report.setStatus(ExpenseReportStatus.PAID);
 reportRepository.save(report);
 LOG.info("Note de frais payée : id={}", report.getId());
 return loadResponse(companyId, report.getId());
 }

 // --- Lecture ---

 /**
 * Liste les notes de frais d'une entreprise, triées par {@code expenseDate} décroissant.
 *
 * <p>Si {@code fiscalYearId} est fourni (restructuration 2026-07-25 suite 4), résout l'exercice
 * via {@link AccountingEngineService#resolveFiscalYear(UUID, UUID)} et filtre par
 * {@code expenseDate} entre les bornes start/end de l'exercice. Si l'exercice n'est pas trouvé,
 * la liste filtrée est vide.
 *
 * <p>Si {@code fiscalYearId} est {@code null}, retourne toutes les notes de l'entreprise
 * (comportement historique, rétro-compatible).
 */
 @Transactional(readOnly = true)
 public List<ExpenseReportResponse> list(UUID companyId, UUID fiscalYearId) {
 if (fiscalYearId != null) {
 java.util.Optional<jo.accountant.accountingengine.entity.FiscalYear> fy =
 accountingEngineService.resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isPresent()) {
 return reportRepository
 .findByCompanyIdAndExpenseDateBetweenOrderByExpenseDateDesc(
 companyId, fy.get().getStartDate(), fy.get().getEndDate())
 .stream().map(r -> loadResponse(companyId, r.getId())).toList();
 }
 // Exercice introuvable → retourne une liste vide (filtre ne matche rien).
 return List.of();
 }
 return reportRepository.findByCompanyIdOrderByExpenseDateDesc(companyId).stream()
 .map(r -> loadResponse(companyId, r.getId()))
 .toList();
 }

 /**
 * Liste paginée des notes de frais — .
 *
 * <p>Variante paginée de {@link #list(UUID, UUID)} — utilise les méthodes {@code Page<>} du
 * repository pour ne charger qu'une page à la fois. Filtre optionnel par exercice fiscal.
 *
 * @param companyId identifiant de l'entreprise
 * @param fiscalYearId filtre optionnel par exercice (null = toutes les notes)
 * @param pageable paramètres de pagination (page, size — size cappé à 200 côté controller)
 * @return page de {@link ExpenseReportResponse} (avec lignes + catégories résolues)
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<ExpenseReportResponse> list(
 UUID companyId, UUID fiscalYearId,
 org.springframework.data.domain.Pageable pageable) {
 org.springframework.data.domain.Page<jo.accountant.expenses.entity.ExpenseReport> page;
 if (fiscalYearId != null) {
 java.util.Optional<jo.accountant.accountingengine.entity.FiscalYear> fy =
 accountingEngineService.resolveFiscalYear(companyId, fiscalYearId);
 if (fy.isPresent()) {
 page = reportRepository.findByCompanyIdAndExpenseDateBetweenOrderByExpenseDateDesc(
 companyId, fy.get().getStartDate(), fy.get().getEndDate(), pageable);
 } else {
 // Exercice introuvable → retourne une page vide.
 return org.springframework.data.domain.Page.empty(pageable);
 }
 } else {
 page = reportRepository.findByCompanyIdOrderByExpenseDateDesc(companyId, pageable);
 }
 return page.map(r -> loadResponse(companyId, r.getId()));
 }

 @Transactional(readOnly = true)
 public ExpenseReportResponse get(UUID companyId, UUID reportId) {
 return loadResponse(companyId, reportId);
 }

 // --- Helpers ---

 private ExpenseReportResponse loadResponse(UUID companyId, UUID reportId) {
 ExpenseReport report = loadReport(companyId, reportId);
 List<ExpenseLine> lines = lineRepository.findByReportIdOrderByCreatedAt(report.getId());
 List<ExpenseReportResponse.LineResponse> lineResponses = lines.stream()
 .map(l -> new ExpenseReportResponse.LineResponse(
 l.getId(), l.getCategory(), l.getDescription(), l.getAmount(),
 l.getExpenseAccountId()))
 .toList();
 String tpName = "";
 if (report.getThirdPartyId() != null) {
 try {
 // Audit v4.7 §6.2 — defense-in-depth
 ThirdParty tp = thirdPartyRepository.findById(report.getThirdPartyId())
 .filter(t -> t.getCompanyId().equals(companyId))
 .orElse(null);
 if (tp != null) tpName = tp.getName();
 } catch (Exception ignored) { }
 }
 return new ExpenseReportResponse(
 report.getId(), report.getCompanyId(), report.getThirdPartyId(), tpName,
 report.getStatus(), report.getExpenseDate(), report.getCurrency(),
 report.getDescription(), report.getTotalAmount(), report.isPaidDirectly(),
 report.getJournalEntryId(), lineResponses,
 report.getCreatedAt(), report.getUpdatedAt());
 }

 private ExpenseReport loadReport(UUID companyId, UUID reportId) {
 ExpenseReport report = reportRepository.findById(reportId)
 .orElseThrow(() -> new NotFoundException("ExpenseReport", reportId));
 if (!report.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ExpenseReport", reportId);
 }
 return report;
 }

 private ThirdParty loadEmployee(UUID companyId, UUID thirdPartyId) {
 ThirdParty tp = thirdPartyRepository.findById(thirdPartyId)
 .orElseThrow(() -> new NotFoundException("ThirdParty", thirdPartyId));
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", thirdPartyId);
 }
 if (tp.getType() != ThirdPartyType.EMPLOYEE) {
 throw new ValidationException("THIRD_PARTY_NOT_EMPLOYEE",
 "Le tiers " + tp.getName() + " n'est pas un employé (type="
 + tp.getType() + ").");
 }
 return tp;
 }

 private Account resolveChargeAccount(UUID companyId, UUID expenseAccountId) {
 if (expenseAccountId != null) {
 Account acc = accountRepository.findById(expenseAccountId)
 .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
 "Compte de charge introuvable : " + expenseAccountId));
 // Audit v4.7 §6.2 IDOR critique : sans ce guard, un BOOKKEEPER de la
 // company A pouvait soumettre une note de frais avec expenseAccountId = UUID d'un compte
 // de la company B. L'écriture comptable était créée dans la company A mais référençait
 // le code de compte de la company B → fuite du plan comptable concurrent + corruption.
 if (!acc.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", expenseAccountId.toString());
 }
 if (acc.getReportingClass() != ReportingClass.CHARGES) {
 throw new ValidationException("ACCOUNT_NOT_CHARGE",
 "Le compte " + acc.getCode() + " n'est pas un compte de CHARGES.");
 }
 return acc;
 }
 // Compte de charges — résolution référentiel-agnostique via AccountResolver (audit #3)
 return accountResolver.resolveOrThrow(
 companyId, ReportingClass.CHARGES, "OPERATING_EXPENSE",
 "EXPENSE_ACCOUNT_NOT_FOUND",
 "Aucun compte de charges trouvé. Configurer un compte CHARGES (idéalement " +
 "marqué taxMappingCode=\"OPERATING_EXPENSE\"), ou préciser expenseAccountId " +
 "sur chaque ligne de la note de frais.",
 "601000", "601");
 }

 /**
 * Valide les plafonds journaliers et mensuels par catégorie (audit batch 1).
 *
 * <p>Pour chaque catégorie présente dans {@code newAmountsByCategory}, on charge la
 * configuration {@link ExpenseCategory} correspondante (si elle existe). Si un plafond
 * (journalier ou mensuel) est configuré (non null), on calcule le total déjà engagé pour
 * cette catégorie sur la période correspondante (jour ou mois de la {@code expenseDate}),
 * on y ajoute le montant des nouvelles lignes, et on lève une {@link ValidationException}
 * si le plafond est dépassé.
 *
 * <p>Les notes REJECTED sont exclues du calcul (elles ne consomment pas de plafond).
 * Les notes DRAFT, SUBMITTED, APPROVED et PAID sont incluses (elles consomment le plafond
 * tant qu'elles ne sont pas rejetées).
 *
 * <p>Si aucune configuration {@link ExpenseCategory} n'existe pour le couple
 * (companyId, category), aucune validation n'est effectuée (comportement historique — pas
 * de plafond).
 *
 * <p>Codes d'erreur :
 * <ul>
 * <li>{@code EXPENSE_DAILY_LIMIT_EXCEEDED} — plafond journalier dépassé</li>
 * <li>{@code EXPENSE_MONTHLY_LIMIT_EXCEEDED} — plafond mensuel dépassé</li>
 * </ul>
 *
 * @param companyId tenant
 * @param expenseDate date de la note de frais (détermine le jour/mois de plafond)
 * @param newAmountsByCategory montants des nouvelles lignes, groupés par catégorie
 */
 private void validateCategoryLimits(UUID companyId, LocalDate expenseDate,
 Map<String, BigDecimal> newAmountsByCategory) {
 if (newAmountsByCategory == null || newAmountsByCategory.isEmpty() || expenseDate == null) {
 return;
 }
 List<ExpenseReportStatus> excludedStatuses = List.of(ExpenseReportStatus.REJECTED);
 LocalDate monthStart = expenseDate.withDayOfMonth(1);
 LocalDate monthEnd = expenseDate.withDayOfMonth(expenseDate.lengthOfMonth());

 for (var entry : newAmountsByCategory.entrySet()) {
 String category = entry.getKey();
 if (category == null || category.isBlank()) continue;
 BigDecimal newAmount = entry.getValue();
 if (newAmount == null || newAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

 // Charger la configuration de la catégorie (peut être absente → pas de plafond)
 ExpenseCategory config = categoryRepository.findByCompanyIdAndCode(companyId, category)
 .orElse(null);
 if (config == null) continue;
 if (config.getDailyLimit() == null && config.getMonthlyLimit() == null) continue;

 // Plafond journalier
 if (config.getDailyLimit() != null) {
 BigDecimal existingForDay = lineRepository.sumAmountByCategoryAndDateRange(
 companyId, category, expenseDate, expenseDate, excludedStatuses);
 BigDecimal totalForDay = existingForDay.add(newAmount);
 if (totalForDay.compareTo(config.getDailyLimit()) > 0) {
 throw new ValidationException("EXPENSE_DAILY_LIMIT_EXCEEDED",
 "Plafond journalier dépassé pour la catégorie '" + category + "' : " +
 totalForDay + " > " + config.getDailyLimit() + " (déjà engagé: " +
 existingForDay + ", nouvelle ligne: " + newAmount + ") à la date " +
 expenseDate + ".");
 }
 }

 // Plafond mensuel
 if (config.getMonthlyLimit() != null) {
 BigDecimal existingForMonth = lineRepository.sumAmountByCategoryAndDateRange(
 companyId, category, monthStart, monthEnd, excludedStatuses);
 BigDecimal totalForMonth = existingForMonth.add(newAmount);
 if (totalForMonth.compareTo(config.getMonthlyLimit()) > 0) {
 throw new ValidationException("EXPENSE_MONTHLY_LIMIT_EXCEEDED",
 "Plafond mensuel dépassé pour la catégorie '" + category + "' : " +
 totalForMonth + " > " + config.getMonthlyLimit() + " (déjà engagé: " +
 existingForMonth + ", nouvelle ligne: " + newAmount + ") pour " +
 monthStart.getMonth() + " " + monthStart.getYear() + ".");
 }
 }
 }
 }
}

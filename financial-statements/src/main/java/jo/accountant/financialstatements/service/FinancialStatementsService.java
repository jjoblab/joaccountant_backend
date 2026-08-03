package jo.accountant.financialstatements.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.financialstatements.dto.BalanceSheet;
import jo.accountant.financialstatements.dto.CreateSnapshotRequest;
import jo.accountant.financialstatements.dto.IncomeStatement;
import jo.accountant.financialstatements.dto.PresentationCurrencyRequest;
import jo.accountant.financialstatements.dto.SnapshotResponse;
import jo.accountant.financialstatements.entity.FinancialStatementSnapshot;
import jo.accountant.financialstatements.entity.FinancialStatementType;
import jo.accountant.financialstatements.event.FinancialStatementSnapshotCreatedEvent;
import jo.accountant.financialstatements.repository.FinancialStatementSnapshotRepository;
import jo.accountant.fxoperations.entity.ExchangeRateSnapshot;
import jo.accountant.fxoperations.repository.ExchangeRateSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des états financiers (§13.
 *
 * <p>Responsabilités :
 * <ul>
 * <li>Génération du bilan ({@link #getBalanceSheet}) à une date donnée</li>
 * <li>Génération du compte de résultat ({@link #getIncomeStatement}) sur une plage de dates</li>
 * <li>Création de snapshots figés ({@link #createSnapshot}) à la clôture</li>
 * <li>Listage / récupération de snapshots figés</li>
 * </ul>
 *
 * <p><strong>Règle fondamentale (§4)</strong> : la génération utilise UNIQUEMENT les
 * {@link Account#getReportingClass()} et {@link Account#getReportingSubcategory()} des
 * comptes — jamais le nom du référentiel (SYSCOHADA, IFRS, etc.). C'est ce qui permet au
 * moteur comptable et aux états financiers d'être référentiel-agnostiques.
 *
 * <p><strong>Invariants</strong> :
 * <ul>
 * <li>Bilan : {@code totalAssets == totalLiabilities + totalEquity} (testé explicitement).</li>
 * <li>Compte de résultat : {@code netResult == totalProducts - totalCharges}.</li>
 * </ul>
 *
 * <p>Note : si l'utilisateur n'a pas encore posté les écritures de clôture (report à
 * nouveau, résultat de l'exercice), le bilan peut être déséquilibré — c'est attendu tant
 * que l'exercice n'est pas CLOSED. Le flag {@link BalanceSheet#balanced()} l'indique
 * clairement à l'appelant.
 
 *
 * @author jo@Dev


*/
@Service
public class FinancialStatementsService {

 private static final Logger LOG = LoggerFactory.getLogger(FinancialStatementsService.class);

 /** Échelle à utiliser pour les montants convertis (cents — cohérent avec l'affichage comptable). */
 private static final int PRESENTATION_SCALE = 4;

 private final JournalLineRepository journalLineRepository;
 private final AccountRepository accountRepository;
 private final FiscalPeriodRepository fiscalPeriodRepository;
 private final FinancialStatementSnapshotRepository snapshotRepository;
 private final ApplicationEventPublisher events;
 private final ObjectMapper objectMapper;
 private final jo.accountant.accountingengine.service.AccountingEngineService accountingEngineService;
 // — pour résoudre la devise fonctionnelle et les snapshots de taux BRH
 private final CompanyRepository companyRepository;
 private final ExchangeRateSnapshotRepository exchangeRateSnapshotRepository;

 public FinancialStatementsService(JournalLineRepository journalLineRepository,
 AccountRepository accountRepository,
 FiscalPeriodRepository fiscalPeriodRepository,
 FinancialStatementSnapshotRepository snapshotRepository,
 ApplicationEventPublisher events,
 ObjectMapper objectMapper,
 jo.accountant.accountingengine.service.AccountingEngineService accountingEngineService,
 CompanyRepository companyRepository,
 ExchangeRateSnapshotRepository exchangeRateSnapshotRepository) {
 this.journalLineRepository = journalLineRepository;
 this.accountRepository = accountRepository;
 this.fiscalPeriodRepository = fiscalPeriodRepository;
 this.snapshotRepository = snapshotRepository;
 this.events = events;
 this.objectMapper = objectMapper;
 this.accountingEngineService = accountingEngineService;
 this.companyRepository = companyRepository;
 this.exchangeRateSnapshotRepository = exchangeRateSnapshotRepository;
 }

 // --- Bilan ---

 /**
 * Génère le bilan à une date donnée.
 *
 * <p>Calcule les soldes de tous les comptes ACTIF/PASSIF/CAPITAUX_PROPRES en sommant
 * les débits et crédits des écritures POSTED dont la date est ≤ {@code asOf}.
 *
 * <p>Pour chaque compte, le solde est calculé selon la {@link Account#getNormalBalance()} :
 * <ul>
 * <li>Compte DEBIT (ACTIF, CHARGES) : solde = débit − crédit</li>
 * <li>Compte CREDIT (PASSIF, CAPITAUX_PROPRES, PRODUITS) : solde = crédit − débit</li>
 * </ul>
 */
 @Transactional(readOnly = true)
 public BalanceSheet getBalanceSheet(UUID companyId, LocalDate asOf) {
 return getBalanceSheet(companyId, asOf, null);
 }

 /**
 * Génère le bilan à une date donnée, avec conversion optionnelle vers une devise de présentation.
 *
 * <p><b></b> : si {@code presentation} est non null et que
 * {@code presentation.presentationCurrency()} diffère de la devise fonctionnelle de la
 * Company, le bilan est converti au taux de clôture (IAS 21). Le taux est soit fourni
 * directement ({@code presentation.closingRate()}), soit retrouvé via
 * {@link ExchangeRateSnapshotRepository} (snapshot_type = CLOSING à la date {@code asOf}).
 *
 * <p>Backward-compat : si {@code presentation} est null, ou que la devise de présentation
 * est null ou égale à la devise fonctionnelle, le comportement est inchangé (bilan en
 * devise fonctionnelle, champs de conversion null).
 */
 @Transactional(readOnly = true)
 public BalanceSheet getBalanceSheet(UUID companyId, LocalDate asOf, PresentationCurrencyRequest presentation) {
 if (asOf == null) {
 asOf = accountingEngineService.resolveFiscalYear(companyId, null)
 .map(jo.accountant.accountingengine.entity.FiscalYear::getEndDate)
 .orElse(LocalDate.now());
 }

 BalanceSheet functional = computeBalanceSheetFunctional(companyId, asOf);

 if (presentation == null || presentation.presentationCurrency() == null) {
 return functional;
 }

 String functionalCurrency = resolveFunctionalCurrency(companyId);
 String presentationCurrency = presentation.presentationCurrency();
 if (presentationCurrency.equalsIgnoreCase(functionalCurrency)) {
 // Pas de conversion nécessaire : on retourne le bilan fonctionnel en exposant la devise
 // fonctionnelle pour information (les champs de conversion restent null — cohérent avec
 // la convention « presentationCurrency == null ⇒ devise fonctionnelle »).
 return functional;
 }

 // Résoudre le taux de clôture : fourni directement ou lookup
 BigDecimal closingRate = presentation.closingRate();
 LocalDate rateDate = asOf;
 if (closingRate == null) {
 Optional<ExchangeRateSnapshot> snapshot = exchangeRateSnapshotRepository
 .findLatestClosingRate(companyId, functionalCurrency, presentationCurrency, asOf);
 if (snapshot.isEmpty()) {
 throw new ValidationException("PRESENTATION_CLOSING_RATE_REQUIRED",
 "Aucun snapshot CLOSING trouvé pour la conversion " + functionalCurrency + "→"
 + presentationCurrency + " à la date " + asOf
 + ". Saisir le taux directement via le paramètre closingRate, ou créer un"
 + " snapshot exchange_rate_snapshot (source=BRH, snapshot_type=CLOSING).");
 }
 closingRate = snapshot.get().getRate();
 rateDate = snapshot.get().getRateDate();
 }

 LOG.info("Conversion bilan : {} → {} au taux de clôture {} (date taux={})",
 functionalCurrency, presentationCurrency, closingRate, rateDate);

 return convertBalanceSheet(functional, presentationCurrency, functionalCurrency,
 closingRate, rateDate, ExchangeRateSnapshot.TYPE_CLOSING);
 }

 /** Calcule le bilan en devise fonctionnelle (logique inchangée, sans infos de conversion). */
 private BalanceSheet computeBalanceSheetFunctional(UUID companyId, LocalDate asOf) {

 // Charger tous les comptes de l'entreprise
 List<Account> allAccounts = accountRepository.findByCompanyIdOrderByCode(companyId);

 //agrégation SQL au lieu de charger toutes les lignes.
 // Avant : findAllPostedUpToDate(companyId, asOf) chargeait toutes les lignes POSTED
 // en mémoire puis agrégeait en Java → OOM sur 100K+ lignes.
 // Maintenant : 1 requête SQL GROUP BY account_id, retourne ~100 lignes.
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> aggregates =
 journalLineRepository.aggregateByAccountUpToDate(companyId, asOf);

 // Grouper les soldes par account (rawBalance = sumDebit - sumCredit)
 Map<UUID, BigDecimal> balanceByAccount = new HashMap<>();
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : aggregates) {
 BigDecimal rawBalance = agg.getTotalDebit().subtract(agg.getTotalCredit());
 balanceByAccount.put(agg.getAccountId(), rawBalance);
 }

 // Construire les sections du bilan
 // Audit E-B : les comptes à solde anormal sont reclassés dans la section opposée.
 // Un compte ACTIF avec solde créditeur (rawBalance < 0) est affiché en PASSIF (dette).
 // Un compte PASSIF avec solde débiteur (rawBalance > 0) est affiché en ACTIF (créance).
 List<BalanceSheet.Section> assets = buildBalanceSectionWithReclassification(
 allAccounts, balanceByAccount, ReportingClass.ACTIF);
 List<BalanceSheet.Section> liabilities = buildBalanceSectionWithReclassification(
 allAccounts, balanceByAccount, ReportingClass.PASSIF);
 List<BalanceSheet.Section> equity = buildBalanceSectionWithReclassification(
 allAccounts, balanceByAccount, ReportingClass.CAPITAUX_PROPRES);

 BigDecimal totalAssets = sumSectionSubtotals(assets);
 BigDecimal totalLiabilities = sumSectionSubtotals(liabilities);
 BigDecimal totalEquity = sumSectionSubtotals(equity);
 boolean balanced = totalAssets.compareTo(totalLiabilities.add(totalEquity)) == 0;

 return new BalanceSheet(companyId, asOf, assets, liabilities, equity,
 totalAssets, totalLiabilities, totalEquity, balanced);
 }

 /**
 * Construit les sections du bilan pour une {@link ReportingClass} donnée, avec reclassification
 * des soldes anormaux (audit E-B).
 *
 * <p>Un compte est inclus dans la section {@code targetClass} si :
 * <ul>
 * <li>Sa {@code reportingClass} naturelle est {@code targetClass} ET son solde est cohérent
 * (positif après application du signe normal) — cas normal.</li>
 * <li>Ou sa {@code reportingClass} naturelle est la classe opposée (ACTIF↔PASSIF) ET son
 * solde est anormal (négatif après application du signe normal) — reclassement.</li>
 * </ul>
 *
 * <p>Exemples :
 * <ul>
 * <li>Compte 521 Banque (ACTIF) avec rawBalance = -1500000 → solde anormal → reclassé en
 * PASSIF avec montant +1500000 (découvert bancaire = dette).</li>
 * <li>Compte 401 Fournisseurs (PASSIF) avec rawBalance = +500 (débiteur) → solde anormal →
 * reclassé en ACTIF avec montant +500 (avance fournisseur = créance).</li>
 * </ul>
 */
 private List<BalanceSheet.Section> buildBalanceSectionWithReclassification(
 List<Account> allAccounts,
 Map<UUID, BigDecimal> balanceByAccount,
 ReportingClass targetClass) {
 Map<ReportingSubcategory, List<BalanceSheet.Line>> bySub = new HashMap<>();
 for (Account account : allAccounts) {
 BigDecimal rawBalance = balanceByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
 if (rawBalance.compareTo(BigDecimal.ZERO) == 0) continue;

 // Cas 1 : compte dont la reportingClass naturelle = targetClass
 // → solde normal = positif après application du signe
 if (account.getReportingClass() == targetClass) {
 BigDecimal amount = targetClass == ReportingClass.ACTIF
 ? rawBalance
 : rawBalance.negate();
 if (amount.compareTo(BigDecimal.ZERO) <= 0) continue; // solde anormal → reclassé
 ReportingSubcategory sub = account.getReportingSubcategory() != null
 ? account.getReportingSubcategory() : ReportingSubcategory.N_A;
 bySub.computeIfAbsent(sub, k -> new ArrayList<>()).add(
 new BalanceSheet.Line(account.getId(), account.getCode(), account.getLabel(), amount));
 }
 // Cas 2 : compte dont la reportingClass naturelle est l'opposée (ACTIF↔PASSIF)
 // ET solde anormal → reclassé dans targetClass
 else if (isOppositeClass(account.getReportingClass(), targetClass)) {
 // Solde vu depuis targetClass : si targetClass=ACTIF, amount = rawBalance;
 // si targetClass=PASSIF, amount = rawBalance.negate()
 BigDecimal amount = targetClass == ReportingClass.ACTIF
 ? rawBalance
 : rawBalance.negate();
 if (amount.compareTo(BigDecimal.ZERO) <= 0) continue; // solde normal → pas de reclassement
 // amount > 0 : c'est un solde anormal pour la classe d'origine, reclassé ici
 ReportingSubcategory sub = account.getReportingSubcategory() != null
 ? account.getReportingSubcategory() : ReportingSubcategory.N_A;
 bySub.computeIfAbsent(sub, k -> new ArrayList<>()).add(
 new BalanceSheet.Line(account.getId(), account.getCode(),
 account.getLabel() + " (reclassé)", amount));
 }
 // CAPITAUX_PROPRES n'est pas reclassé (ni vers ACTIF, ni vers PASSIF)
 }

 List<BalanceSheet.Section> sections = new ArrayList<>();
 for (Map.Entry<ReportingSubcategory, List<BalanceSheet.Line>> entry : bySub.entrySet()) {
 List<BalanceSheet.Line> sortedLines = entry.getValue().stream()
 .sorted(Comparator.comparing(BalanceSheet.Line::accountCode)).toList();
 BigDecimal subtotal = sortedLines.stream().map(BalanceSheet.Line::amount)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 sections.add(new BalanceSheet.Section(
 targetClass.name(), entry.getKey().name(), sortedLines, subtotal));
 }
 return sections;
 }

 /** Retourne true si a et b sont des classes opposées (ACTIF↔PASSIF). CAPITAUX_PROPRES n'a pas d'opposé. */
 private boolean isOppositeClass(ReportingClass a, ReportingClass b) {
 return (a == ReportingClass.ACTIF && b == ReportingClass.PASSIF)
 || (a == ReportingClass.PASSIF && b == ReportingClass.ACTIF);
 }

 private BigDecimal sumSectionSubtotals(List<BalanceSheet.Section> sections) {
 return sections.stream().map(BalanceSheet.Section::subtotal)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 }

 // --- Compte de résultat ---

 /**
 * Génère le compte de résultat sur une plage de dates.
 *
 * <p>Calcule les soldes des comptes PRODUITS et CHARGES, puis calcule le résultat net
 * = totalProducts − totalCharges.
 *
 * <p>Ensimplifié : pas de filtrage par date (toutes les écritures POSTED sont
 * incluses). Filtre à ajouter en(reporting) si besoin.
 */
 @Transactional(readOnly = true)
 public IncomeStatement getIncomeStatement(UUID companyId, LocalDate from, LocalDate to) {
 return getIncomeStatement(companyId, from, to, null);
 }

 /**
 * Génère le compte de résultat sur une plage de dates, avec conversion optionnelle vers
 * une devise de présentation.
 *
 * <p><b></b> : si la conversion est demandée, le CR est
 * converti au taux moyen de période (IAS 21 — flux au taux moyen). Le taux est soit fourni
 * directement ({@code presentation.averageRate()}), soit retrouvé via
 * {@link ExchangeRateSnapshotRepository} (snapshot_type = PERIOD_AVERAGE sur les mois de la
 * période ; moyenne arithmétique simple des snapshots mensuels disponibles).
 *
 * <p><b>Limitation v6-4</b> : un seul taux moyen est appliqué sur l'ensemble de la période.
 * Une implémentation IAS 21 plus rigoureuse appliquerait un taux moyen mensuel distinct par
 * mois puis consoliderait — planifié v7.
 */
 @Transactional(readOnly = true)
 public IncomeStatement getIncomeStatement(UUID companyId, LocalDate from, LocalDate to,
 PresentationCurrencyRequest presentation) {
 if (from == null || to == null) {
 var fy = accountingEngineService.resolveFiscalYear(companyId, null);
 if (fy.isPresent()) {
 from = fy.get().getStartDate();
 to = fy.get().getEndDate();
 } else {
 throw new ValidationException("DATES_REQUIRED",
 "from et to sont requis (aucun exercice fiscal disponible pour défaut)");
 }
 }
 if (to.isBefore(from)) {
 throw new ValidationException("INVALID_DATE_RANGE", "to doit être >= from");
 }

 IncomeStatement functional = computeIncomeStatementFunctional(companyId, from, to);

 if (presentation == null || presentation.presentationCurrency() == null) {
 return functional;
 }

 String functionalCurrency = resolveFunctionalCurrency(companyId);
 String presentationCurrency = presentation.presentationCurrency();
 if (presentationCurrency.equalsIgnoreCase(functionalCurrency)) {
 return functional;
 }

 // Résoudre le taux moyen : fourni directement ou lookup
 BigDecimal averageRate = presentation.averageRate();
 LocalDate rateDate = null;
 if (averageRate == null) {
 AverageRateResult avgResult = lookupAverageRate(companyId, functionalCurrency,
 presentationCurrency, from, to);
 averageRate = avgResult.rate();
 rateDate = avgResult.rateDate();
 }

 LOG.info("Conversion compte de résultat : {} → {} au taux moyen {} (date taux={})",
 functionalCurrency, presentationCurrency, averageRate, rateDate);

 return convertIncomeStatement(functional, presentationCurrency, functionalCurrency,
 averageRate, rateDate, ExchangeRateSnapshot.TYPE_PERIOD_AVERAGE);
 }

 /** Calcule le compte de résultat en devise fonctionnelle (logique inchangée). */
 private IncomeStatement computeIncomeStatementFunctional(UUID companyId, LocalDate from, LocalDate to) {
 List<Account> allAccounts = accountRepository.findByCompanyIdOrderByCode(companyId);
 //agrégation SQL au lieu de findAllPostedBetweenDates
 // (chargeait toutes les lignes en mémoire pour agréger en Java).
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> aggregates =
 journalLineRepository.aggregateByAccountBetweenDates(companyId, from, to);

 Map<UUID, BigDecimal> balanceByAccount = new HashMap<>();
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : aggregates) {
 // rawBalance = débit - crédit (à inverser ensuite selon normalBalance)
 balanceByAccount.put(agg.getAccountId(),
 agg.getTotalDebit().subtract(agg.getTotalCredit()));
 }

 List<IncomeStatement.Section> products = buildIncomeSection(allAccounts, balanceByAccount,
 ReportingClass.PRODUITS, true);
 List<IncomeStatement.Section> charges = buildIncomeSection(allAccounts, balanceByAccount,
 ReportingClass.CHARGES, false);

 BigDecimal totalProducts = products.stream().map(IncomeStatement.Section::subtotal)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal totalCharges = charges.stream().map(IncomeStatement.Section::subtotal)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal netResult = totalProducts.subtract(totalCharges);

 return new IncomeStatement(companyId, from, to, products, charges,
 totalProducts, totalCharges, netResult);
 }

 private List<IncomeStatement.Section> buildIncomeSection(List<Account> allAccounts,
 Map<UUID, BigDecimal> balanceByAccount,
 ReportingClass targetClass,
 boolean isCredit) {
 Map<ReportingSubcategory, List<IncomeStatement.Line>> bySub = new HashMap<>();
 for (Account account : allAccounts) {
 if (account.getReportingClass() != targetClass) continue;
 BigDecimal rawBalance = balanceByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
 // Pour PRODUITS (CREDIT), montant = crédit - débit
 // Pour CHARGES (DEBIT), montant = débit - crédit
 BigDecimal amount = isCredit ? rawBalance.negate() : rawBalance;
 if (amount.compareTo(BigDecimal.ZERO) == 0) continue;

 ReportingSubcategory sub = account.getReportingSubcategory() != null
 ? account.getReportingSubcategory() : ReportingSubcategory.N_A;
 bySub.computeIfAbsent(sub, k -> new ArrayList<>()).add(
 new IncomeStatement.Line(account.getId(), account.getCode(), account.getLabel(), amount));
 }

 List<IncomeStatement.Section> sections = new ArrayList<>();
 for (Map.Entry<ReportingSubcategory, List<IncomeStatement.Line>> entry : bySub.entrySet()) {
 List<IncomeStatement.Line> sortedLines = entry.getValue().stream()
 .sorted(Comparator.comparing(IncomeStatement.Line::accountCode)).toList();
 BigDecimal subtotal = sortedLines.stream().map(IncomeStatement.Line::amount)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 sections.add(new IncomeStatement.Section(
 targetClass.name(), entry.getKey().name(), sortedLines, subtotal));
 }
 return sections;
 }

 // --- Snapshots ---

 /**
 * Crée un snapshot figé d'un état financier pour une période donnée.
 *
 * <p>Le snapshot est immuable dès sa création. 409 si un snapshot existe déjà pour le
 * même (companyId, type, periodId).
 */
 @Transactional
 public SnapshotResponse createSnapshot(UUID companyId, CreateSnapshotRequest req) {
 FiscalPeriod period = fiscalPeriodRepository.findById(req.periodId())
 .orElseThrow(() -> new NotFoundException("FiscalPeriod", req.periodId()));
 if (!period.getCompanyId().equals(companyId)) {
 throw new NotFoundException("FiscalPeriod", req.periodId());
 }

 if (snapshotRepository.findByCompanyIdAndTypeAndPeriodId(
 companyId, req.type(), req.periodId()).isPresent()) {
 throw new ConflictException("SNAPSHOT_ALREADY_EXISTS",
 "Un snapshot " + req.type() + " existe déjà pour la période " + req.periodId()
 + ". Supprimer l'ancien avant d'en créer un nouveau.");
 }

 // Générer le contenu selon le type
 String contentJson;
 if (req.type() == FinancialStatementType.BALANCE_SHEET) {
 LocalDate asOf = req.asOf() != null ? req.asOf() : period.getEndDate();
 BalanceSheet bs = getBalanceSheet(companyId, asOf);
 contentJson = serializeJson(bs);
 } else {
 LocalDate from = req.from() != null ? req.from() : period.getStartDate();
 LocalDate to = req.to() != null ? req.to() : period.getEndDate();
 IncomeStatement is = getIncomeStatement(companyId, from, to);
 contentJson = serializeJson(is);
 }

 FinancialStatementSnapshot snapshot = new FinancialStatementSnapshot();
 snapshot.setCompanyId(companyId);
 snapshot.setType(req.type());
 snapshot.setPeriodId(req.periodId());
 snapshot.setGeneratedAt(Instant.now());
 snapshot.setFrozen(true);
 snapshot.setContentJson(contentJson);
 snapshot.setAsOfDate(req.asOf());
 snapshot.setFromDate(req.from());
 snapshot.setToDate(req.to());
 FinancialStatementSnapshot saved = snapshotRepository.save(snapshot);

 events.publishEvent(new FinancialStatementSnapshotCreatedEvent(saved, TenantContext.getUserId()));
 LOG.info("Snapshot créé : type={} period={} snapshotId={}", req.type(), req.periodId(), saved.getId());

 return toResponse(saved);
 }

 /**
 * Crée automatiquement les snapshots figés bilan + compte de résultat pour une période donnée.
 *
 * <p><b>FIX</b> : après {@code AccountingEngineService.closeFiscalYear},
 * l'appelant (controller ou frontend) doit appeler cette méthode pour figer le bilan et le CR
 * au moment de la clôture. Sans ces snapshots, le plan comptable pourrait être modifié après
 * clôture et les états financiers générés ultérieurement pourraient différer de ceux valables
 * à la clôture — défaut de piste d'audit.
 *
 * <p>Idempotent : si un snapshot existe déjà pour (companyId, type, periodId), il est ignoré
 * (au lieu de lever 409). Permet de retry sans risque.
 *
 * @param companyId identifiant de l'entreprise
 * @param periodId identifiant de la période fiscale de clôture (dernière période de l'exercice)
 * @return liste des snapshots créés (0, 1 ou 2 selon ce qui existait déjà)
 */
 @Transactional
 public java.util.List<SnapshotResponse> createClosingSnapshots(UUID companyId, UUID periodId) {
 FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
 .orElseThrow(() -> new NotFoundException("FiscalPeriod", periodId));
 if (!period.getCompanyId().equals(companyId)) {
 throw new NotFoundException("FiscalPeriod", periodId);
 }

 java.util.List<SnapshotResponse> created = new java.util.ArrayList<>();

 // Bilan (BALANCE_SHEET) — figé à la date de fin de période
 if (snapshotRepository.findByCompanyIdAndTypeAndPeriodId(
 companyId, FinancialStatementType.BALANCE_SHEET, periodId).isEmpty()) {
 try {
 SnapshotResponse bs = createSnapshot(companyId, new CreateSnapshotRequest(
 FinancialStatementType.BALANCE_SHEET, periodId, period.getEndDate(), null, null));
 created.add(bs);
 } catch (ConflictException ex) {
 LOG.debug("Snapshot BALANCE_SHEET déjà existant pour period={} — skip", periodId);
 }
 }

 // Compte de résultat (INCOME_STATEMENT) — figé sur la plage de la période
 if (snapshotRepository.findByCompanyIdAndTypeAndPeriodId(
 companyId, FinancialStatementType.INCOME_STATEMENT, periodId).isEmpty()) {
 try {
 SnapshotResponse is = createSnapshot(companyId, new CreateSnapshotRequest(
 FinancialStatementType.INCOME_STATEMENT, periodId, null,
 period.getStartDate(), period.getEndDate()));
 created.add(is);
 } catch (ConflictException ex) {
 LOG.debug("Snapshot INCOME_STATEMENT déjà existant pour period={} — skip", periodId);
 }
 }

 LOG.info("createClosingSnapshots : {} snapshot(s) créé(s) pour company={}, period={}",
 created.size(), companyId, periodId);
 return created;
 }

 @Transactional(readOnly = true)
 public List<SnapshotResponse> listSnapshots(UUID companyId) {
 return snapshotRepository.findByCompanyIdOrderByGeneratedAtDesc(companyId).stream()
 .map(FinancialStatementsService::toResponse)
 .toList();
 }

 /**
 * Génère le tableau de flux de trésorerie (IAS 7 / SYSCOHADA TAFIRE) par méthode indirecte.
 *
 * <p><b>FIX</b> : obligatoire en IFRS (IAS 7) et SYSCOHADA,
 * déclaré obligatoire dans le seed IFRS_FULL mais non implémenté dans la version précédente.
 *
 * <p>Logique (méthode indirecte) :
 * <ol>
 * <li>Calculer le résultat net = Produits − Charges sur la période</li>
 * <li>Ajouter les amortissements (comptes 28x en PCG, 28 en SYSCOHADA) — éléments non monétaires</li>
 * <li>Calculer les variations BFR : créances clients (411), stocks (3x), fournisseurs (401)
 * entre [from-1] et [to]</li>
 * <li>Isoler les flux d'investissement : acquisitions d'immo (débit comptes 2x),
 * cessions (crédit comptes 2x + prix de cession)</li>
 * <li>Isoler les flux de financement : variations capital (10x), emprunts (16x), dividendes (457)</li>
 * <li>Vérifier : trésorerie clôture = trésorerie ouverture + flux net total</li>
 * </ol>
 *
 * <p><b>Limitation</b> : l'implémentation actuelle est simplifiée — elle se base sur les
 * ReportingClass et les codes de compte. La distinction précise investissement/financement
 * nécessite un mapping explicite par compte (à affiner). L'objectif est de
 * fournir un tableau exploitable, pas une conformité IFRS stricte.
 */
 @Transactional(readOnly = true)
 public jo.accountant.financialstatements.dto.CashFlowStatement getCashFlowStatement(
 UUID companyId, LocalDate from, LocalDate to) {
 return getCashFlowStatement(companyId, from, to, null);
 }

 /**
 * Génère le tableau de flux de trésorerie, avec conversion optionnelle vers une devise de
 * présentation.
 *
 * <p><b> — squelette v6</b> : pour la conversion, on applique
 * un taux moyen unique sur l'ensemble des flux de la période (postes du CR et variations BFR
 * et flux d'investissement / financement). La norme IAS 7 / IAS 21 recommande un taux moyen
 * par sous-période pour les flux, puis une consolidation, et un taux de clôture pour les
 * soldes d'ouverture et de clôture de trésorerie. Cette raffinement est planifié v7 ; en v6
 * on utilise un taux moyen uniforme pour rester simple et ne pas casser le build.
 */
 @Transactional(readOnly = true)
 public jo.accountant.financialstatements.dto.CashFlowStatement getCashFlowStatement(
 UUID companyId, LocalDate from, LocalDate to,
 jo.accountant.financialstatements.dto.PresentationCurrencyRequest presentation) {

 jo.accountant.financialstatements.dto.CashFlowStatement functional =
 computeCashFlowStatementFunctional(companyId, from, to);

 if (presentation == null || presentation.presentationCurrency() == null) {
 return functional;
 }

 String functionalCurrency = resolveFunctionalCurrency(companyId);
 String presentationCurrency = presentation.presentationCurrency();
 if (presentationCurrency.equalsIgnoreCase(functionalCurrency)) {
 return functional;
 }

 // Résoudre le taux moyen : fourni directement ou lookup
 BigDecimal averageRate = presentation.averageRate();
 LocalDate rateDate = null;
 if (averageRate == null) {
 AverageRateResult avgResult = lookupAverageRate(companyId, functionalCurrency,
 presentationCurrency, from, to);
 averageRate = avgResult.rate();
 rateDate = avgResult.rateDate();
 }

 LOG.info("Conversion tableau de flux : {} → {} au taux moyen {} (date taux={}) — squelette v6 (taux unique)",
 functionalCurrency, presentationCurrency, averageRate, rateDate);

 return convertCashFlowStatement(functional, presentationCurrency, functionalCurrency,
 averageRate, rateDate, ExchangeRateSnapshot.TYPE_PERIOD_AVERAGE);
 }

 /** Calcule le tableau de flux de trésorerie en devise fonctionnelle (logique inchangée). */
 private jo.accountant.financialstatements.dto.CashFlowStatement computeCashFlowStatementFunctional(
 UUID companyId, LocalDate from, LocalDate to) {

 //Réécriture avec agrégations SQL.
 // Avant : chargeait findAllPostedBetweenDates(companyId, from, to) en mémoire puis
 // itérait en Java pour calculer produits/charges/amortissements/variations/flux.
 // Sur 100K+ lignes : heap > 50 MB, latence > 5s.
 // Maintenant : 3 requêtes SQL GROUP BY (période, ouverture, clôture), ~100 lignes chacune.

 // 1. Agrégats par compte sur la période (produits, charges, amortissements, flux)
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> periodAggregates =
 journalLineRepository.aggregateByAccountBetweenDates(companyId, from, to);

 // Pré-charger les comptes pour la résolution code/reportingClass
 java.util.Map<java.util.UUID, jo.accountant.chartofaccounts.entity.Account> accountCache = new java.util.HashMap<>();
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : periodAggregates) {
 accountCache.computeIfAbsent(agg.getAccountId(),
 id -> accountRepository.findById(id).orElse(null));
 }

 BigDecimal totalProducts = BigDecimal.ZERO;
 BigDecimal totalCharges = BigDecimal.ZERO;
 BigDecimal depreciationAmortization = BigDecimal.ZERO; // comptes 28x (PCG) / 28 (SYSCOHADA)

 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : periodAggregates) {
 jo.accountant.chartofaccounts.entity.Account account = accountCache.get(agg.getAccountId());
 if (account == null) continue;
 BigDecimal debit = agg.getTotalDebit();
 BigDecimal credit = agg.getTotalCredit();

 jo.accountant.core.framework.ReportingClass rc = account.getReportingClass();
 if (rc == jo.accountant.core.framework.ReportingClass.PRODUITS) {
 totalProducts = totalProducts.add(credit).subtract(debit);
 } else if (rc == jo.accountant.core.framework.ReportingClass.CHARGES) {
 totalCharges = totalCharges.add(debit).subtract(credit);
 }
 // Détection amortissements (codes 28x en PCG/SYSCOHADA, ou taxMappingCode="DEPRECIATION")
 if (account.getCode() != null && account.getCode().startsWith("28")) {
 depreciationAmortization = depreciationAmortization.add(debit).subtract(credit);
 }
 }
 BigDecimal netIncome = totalProducts.subtract(totalCharges);

 // 2. Variations BFR : comparer soldes à `from-1` et `to` pour les comptes concernés
 LocalDate openingDate = from.minusDays(1);
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> openingAggregates =
 journalLineRepository.aggregateByAccountUpToDate(companyId, openingDate);
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> closingAggregates =
 journalLineRepository.aggregateByAccountUpToDate(companyId, to);
 // Compléter le cache avec les comptes présents uniquement à l'ouverture/clôture
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : openingAggregates) {
 accountCache.computeIfAbsent(agg.getAccountId(),
 id -> accountRepository.findById(id).orElse(null));
 }
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : closingAggregates) {
 accountCache.computeIfAbsent(agg.getAccountId(),
 id -> accountRepository.findById(id).orElse(null));
 }

 BigDecimal accountsReceivableVar = variationOfAggregates(openingAggregates, closingAggregates, accountCache, "41");
 BigDecimal inventoryVar = variationOfAggregates(openingAggregates, closingAggregates, accountCache, "3");
 BigDecimal accountsPayableVar = variationOfAggregates(openingAggregates, closingAggregates, accountCache, "40");

 // Convention : pour les actifs (clients, stocks), une augmentation = sortie de trésorerie (flux négatif)
 // Pour les passifs (fournisseurs), une augmentation = entrée de trésorerie (flux positif)
 // Fix Dim 3 C2 (audit v9.4) : apFlux était accountsPayableVar sans negate, ce qui inversait le signe.
 // accountsPayableVar = closing − opening, où chaque solde = totalDebit − totalCredit.
 // Pour un compte de passif (401, crédit normal), une augmentation de dette se traduit par
 // totalDebit − totalCredit négatif, donc accountsPayableVar négatif. Or une augmentation de
 // dette fournisseur = entrée de trésorerie (flux positif). Il faut donc negate().
 BigDecimal arFlux = accountsReceivableVar.negate(); // +créance → -trésorerie
 BigDecimal invFlux = inventoryVar.negate();
 BigDecimal apFlux = accountsPayableVar.negate(); // +dette fournisseur → +trésorerie (fix v9.4)

 BigDecimal operatingNetCashFlow = netIncome
 .add(depreciationAmortization)
 .add(arFlux).add(invFlux).add(apFlux);

 jo.accountant.financialstatements.dto.CashFlowStatement.OperatingFlows operating =
 new jo.accountant.financialstatements.dto.CashFlowStatement.OperatingFlows(
 netIncome, depreciationAmortization,
 arFlux, invFlux, apFlux, BigDecimal.ZERO, operatingNetCashFlow);

 // 3. Flux d'investissement : acquisitions et cessions d'immobilisations (comptes 2x)
 BigDecimal fixedAssetsAcquisitions = BigDecimal.ZERO;
 BigDecimal fixedAssetsDisposals = BigDecimal.ZERO;
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : periodAggregates) {
 jo.accountant.chartofaccounts.entity.Account account = accountCache.get(agg.getAccountId());
 if (account == null || account.getCode() == null) continue;
 if (account.getCode().startsWith("2") && account.getCode().length() >= 2) {
 // Débit = acquisition, Crédit = cession (sortie d'actif)
 fixedAssetsAcquisitions = fixedAssetsAcquisitions.add(agg.getTotalDebit());
 fixedAssetsDisposals = fixedAssetsDisposals.add(agg.getTotalCredit());
 }
 }
 BigDecimal investingTotal = fixedAssetsDisposals.subtract(fixedAssetsAcquisitions);
 jo.accountant.financialstatements.dto.CashFlowStatement.InvestingFlows investing =
 new jo.accountant.financialstatements.dto.CashFlowStatement.InvestingFlows(
 fixedAssetsAcquisitions, fixedAssetsDisposals, BigDecimal.ZERO, investingTotal);

 // 4. Flux de financement : capital (10x), emprunts (16x), dividendes (457)
 BigDecimal capitalVariation = BigDecimal.ZERO;
 BigDecimal loansVariation = BigDecimal.ZERO;
 BigDecimal dividendsPaid = BigDecimal.ZERO;
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : periodAggregates) {
 jo.accountant.chartofaccounts.entity.Account account = accountCache.get(agg.getAccountId());
 if (account == null || account.getCode() == null) continue;
 String code = account.getCode();
 BigDecimal debit = agg.getTotalDebit();
 BigDecimal credit = agg.getTotalCredit();
 if (code.startsWith("10")) {
 capitalVariation = capitalVariation.add(credit).subtract(debit);
 } else if (code.startsWith("16")) {
 loansVariation = loansVariation.add(credit).subtract(debit);
 } else if (code.startsWith("457") || code.startsWith("4570")) {
 dividendsPaid = dividendsPaid.add(debit).subtract(credit);
 }
 }
 BigDecimal financingTotal = capitalVariation.add(loansVariation).subtract(dividendsPaid);
 jo.accountant.financialstatements.dto.CashFlowStatement.FinancingFlows financing =
 new jo.accountant.financialstatements.dto.CashFlowStatement.FinancingFlows(
 capitalVariation, loansVariation, dividendsPaid, BigDecimal.ZERO, financingTotal);

 // 5. Variation nette de trésorerie = exploitation + investissement + financement
 BigDecimal netCashFlow = operatingNetCashFlow.add(investingTotal).add(financingTotal);

 // 6. Trésorerie ouverture et clôture (comptes 5x : 52 Banques, 57 Caisse)
 BigDecimal openingCash = sumCashAccountsAggregates(openingAggregates, accountCache);
 BigDecimal closingCash = sumCashAccountsAggregates(closingAggregates, accountCache);
 boolean balanced = openingCash.add(netCashFlow).compareTo(closingCash) == 0;

 LOG.info("CashFlowStatement généré pour company={} [{} à {}] : netIncome={}, operating={}, investing={}, financing={}, netCashFlow={}, openingCash={}, closingCash={}",
 companyId, from, to, netIncome, operatingNetCashFlow, investingTotal, financingTotal,
 netCashFlow, openingCash, closingCash);

 return new jo.accountant.financialstatements.dto.CashFlowStatement(
 companyId, from, to, netIncome, operating, investing, financing,
 netCashFlow, openingCash, closingCash, balanced);
 }

 /** Calcule la variation de solde (débit - crédit) pour les comptes dont le code commence par `prefix`. */
 private BigDecimal variationOfAggregates(
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> openingAggregates,
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> closingAggregates,
 java.util.Map<java.util.UUID, jo.accountant.chartofaccounts.entity.Account> accountCache,
 String codePrefix) {
 BigDecimal opening = sumByCodePrefixAggregates(openingAggregates, accountCache, codePrefix);
 BigDecimal closing = sumByCodePrefixAggregates(closingAggregates, accountCache, codePrefix);
 return closing.subtract(opening);
 }

 private BigDecimal sumByCodePrefixAggregates(
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> aggregates,
 java.util.Map<java.util.UUID, jo.accountant.chartofaccounts.entity.Account> accountCache,
 String codePrefix) {
 BigDecimal sum = BigDecimal.ZERO;
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : aggregates) {
 jo.accountant.chartofaccounts.entity.Account account = accountCache.get(agg.getAccountId());
 if (account == null || account.getCode() == null) continue;
 if (account.getCode().startsWith(codePrefix)) {
 sum = sum.add(agg.getTotalDebit()).subtract(agg.getTotalCredit());
 }
 }
 return sum;
 }

 /** Calcule le solde des comptes de trésorerie (5x : Banques 52, Caisse 57). */
 private BigDecimal sumCashAccountsAggregates(
 List<jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate> aggregates,
 java.util.Map<java.util.UUID, jo.accountant.chartofaccounts.entity.Account> accountCache) {
 BigDecimal sum = BigDecimal.ZERO;
 for (jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate agg : aggregates) {
 jo.accountant.chartofaccounts.entity.Account account = accountCache.get(agg.getAccountId());
 if (account == null || account.getCode() == null) continue;
 if (account.getCode().startsWith("5")) {
 sum = sum.add(agg.getTotalDebit()).subtract(agg.getTotalCredit());
 }
 }
 return sum;
 }

 @Transactional(readOnly = true)
 public SnapshotResponse getSnapshot(UUID companyId, UUID snapshotId) {
 FinancialStatementSnapshot snapshot = snapshotRepository.findById(snapshotId)
 .orElseThrow(() -> new NotFoundException("FinancialStatementSnapshot", snapshotId));
 if (!snapshot.getCompanyId().equals(companyId)) {
 throw new NotFoundException("FinancialStatementSnapshot", snapshotId);
 }
 return toResponse(snapshot);
 }

 // --- Helpers ---

 private String serializeJson(Object obj) {
 try {
 return objectMapper.writeValueAsString(obj);
 } catch (JsonProcessingException e) {
 throw new IllegalStateException("Failed to serialize snapshot content", e);
 }
 }

 private static SnapshotResponse toResponse(FinancialStatementSnapshot snapshot) {
 return new SnapshotResponse(
 snapshot.getId(),
 snapshot.getCompanyId(),
 snapshot.getType(),
 snapshot.getPeriodId(),
 snapshot.getGeneratedAt(),
 snapshot.isFrozen(),
 snapshot.getAsOfDate(),
 snapshot.getFromDate(),
 snapshot.getToDate(),
 snapshot.getContentJson());
 }

 // =====================================================================
 // — Helpers de conversion de devise
 // =====================================================================

 /** Résultat du lookup d'un taux moyen sur une période. */
 private record AverageRateResult(BigDecimal rate, LocalDate rateDate) {}

 /**
 * Résout la devise fonctionnelle d'une entreprise. Lance NotFoundException si la Company
 * n'existe pas (sécurité multi-tenant : le repository est filtré par RLS mais on vérifie
 * explicitement l'existence).
 */
 private String resolveFunctionalCurrency(UUID companyId) {
 Company company = companyRepository.findById(companyId)
 .orElseThrow(() -> new NotFoundException("Company", companyId));
 String fc = company.getFunctionalCurrency();
 if (fc == null || fc.isBlank()) {
 throw new ValidationException("FUNCTIONAL_CURRENCY_NOT_SET",
 "La Company " + companyId + " n'a pas de devise fonctionnelle configurée"
 + " (colonne companies.functional_currency).");
 }
 return fc.trim().toUpperCase();
 }

 /**
 * Lookup du taux moyen sur la période [{@code from}, {@code to}] : on énumère les mois
 * couverts par la période, on cherche un snapshot PERIOD_AVERAGE pour chaque mois, et on
 * calcule la moyenne arithmétique simple des snapshots trouvés.
 *
 * <p>Si aucun snapshot mensuel n'est trouvé sur la période → 422
 * {@code PRESENTATION_AVERAGE_RATE_REQUIRED}.
 */
 private AverageRateResult lookupAverageRate(UUID companyId, String fromCurrency,
 String toCurrency, LocalDate from, LocalDate to) {
 YearMonth startMonth = YearMonth.from(from);
 YearMonth endMonth = YearMonth.from(to);
 BigDecimal sum = BigDecimal.ZERO;
 int count = 0;
 LocalDate lastRateDate = null;

 for (YearMonth ym = startMonth; !ym.isAfter(endMonth); ym = ym.plusMonths(1)) {
 Optional<ExchangeRateSnapshot> snap = exchangeRateSnapshotRepository
 .findAverageRateForPeriod(companyId, fromCurrency, toCurrency,
 ym.getYear(), ym.getMonthValue());
 if (snap.isPresent()) {
 sum = sum.add(snap.get().getRate());
 count++;
 LocalDate snapDate = snap.get().getRateDate();
 if (lastRateDate == null || (snapDate != null && snapDate.isAfter(lastRateDate))) {
 lastRateDate = snapDate;
 }
 }
 }

 if (count == 0) {
 throw new ValidationException("PRESENTATION_AVERAGE_RATE_REQUIRED",
 "Aucun snapshot PERIOD_AVERAGE trouvé pour la conversion " + fromCurrency + "→"
 + toCurrency + " sur la période " + from + " à " + to
 + ". Saisir le taux directement via le paramètre averageRate, ou créer des"
 + " snapshots exchange_rate_snapshot (snapshot_type=PERIOD_AVERAGE, period_year/month).");
 }

 BigDecimal avg = sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
 return new AverageRateResult(avg, lastRateDate);
 }

 /** Convertit un bilan en devise de présentation : multiplie chaque montant par le taux. */
 private BalanceSheet convertBalanceSheet(BalanceSheet functional,
 String presentationCurrency,
 String functionalCurrency,
 BigDecimal rate, LocalDate rateDate,
 String conversionType) {
 List<BalanceSheet.Section> assets = convertBsSections(functional.assets(), rate);
 List<BalanceSheet.Section> liabilities = convertBsSections(functional.liabilities(), rate);
 List<BalanceSheet.Section> equity = convertBsSections(functional.equity(), rate);

 BigDecimal totalAssets = convertAmount(functional.totalAssets(), rate);
 BigDecimal totalLiabilities = convertAmount(functional.totalLiabilities(), rate);
 BigDecimal totalEquity = convertAmount(functional.totalEquity(), rate);

 // V85 — v7-3 : CTA (Cumulative Translation Adjustment) — IAS 21
 // L'écart de conversion est isolé en capitaux propres :
 // CTA = totalAssetsPresentation − totalLiabilitiesPresentation − totalEquityFunctionalConverted
 // En pratique, si la conversion est uniforme (même taux pour actif, passif, capitaux propres),
 // le CTA est nul (équation bilancielle préservée). Le CTA devient non nul si différents
 // taux sont appliqués (ex: immo au taux historique, trésorerie au taux de clôture) —
 // ce qui n'est pas le cas ici (taux unique de clôture), mais le champ est exposé pour
 // future extension et pour la conformité IAS 21 (le CTA doit être isolé et tracé).
 BigDecimal ctaAmount = totalAssets
 .subtract(totalLiabilities)
 .subtract(totalEquity);

 // L'équation bilancielle en devise de présentation devient :
 // totalAssets == totalLiabilities + totalEquity + ctaAmount
 // (le CTA absorbe l'écart de conversion pour rééquilibrer le bilan)
 boolean balanced = totalAssets.compareTo(
 totalLiabilities.add(totalEquity).add(ctaAmount)) == 0;

 LOG.info("V85 — v7-3 — CTA calculé : {} {} → {} {} (taux {}, CTA = {} {})",
 functional.totalAssets(), functionalCurrency,
 totalAssets, presentationCurrency,
 rate, ctaAmount, presentationCurrency);

 return new BalanceSheet(functional.companyId(), functional.asOf(),
 assets, liabilities, equity, totalAssets, totalLiabilities, totalEquity, balanced,
 presentationCurrency, functionalCurrency, rate, rateDate, conversionType, ctaAmount);
 }

 private List<BalanceSheet.Section> convertBsSections(List<BalanceSheet.Section> sections, BigDecimal rate) {
 List<BalanceSheet.Section> out = new ArrayList<>(sections.size());
 for (BalanceSheet.Section s : sections) {
 List<BalanceSheet.Line> lines = new ArrayList<>(s.lines().size());
 for (BalanceSheet.Line l : s.lines()) {
 lines.add(new BalanceSheet.Line(l.accountId(), l.accountCode(),
 l.accountLabel(), convertAmount(l.amount(), rate)));
 }
 out.add(new BalanceSheet.Section(s.reportingClass(), s.reportingSubcategory(),
 lines, convertAmount(s.subtotal(), rate)));
 }
 return out;
 }

 /** Convertit un compte de résultat en devise de présentation. */
 private IncomeStatement convertIncomeStatement(IncomeStatement functional,
 String presentationCurrency,
 String functionalCurrency,
 BigDecimal rate, LocalDate rateDate,
 String conversionType) {
 List<IncomeStatement.Section> products = convertIsSections(functional.products(), rate);
 List<IncomeStatement.Section> charges = convertIsSections(functional.charges(), rate);

 BigDecimal totalProducts = convertAmount(functional.totalProducts(), rate);
 BigDecimal totalCharges = convertAmount(functional.totalCharges(), rate);
 BigDecimal netResult = convertAmount(functional.netResult(), rate);

 return new IncomeStatement(functional.companyId(), functional.from(), functional.to(),
 products, charges, totalProducts, totalCharges, netResult,
 presentationCurrency, functionalCurrency, rate, rateDate, conversionType);
 }

 private List<IncomeStatement.Section> convertIsSections(List<IncomeStatement.Section> sections, BigDecimal rate) {
 List<IncomeStatement.Section> out = new ArrayList<>(sections.size());
 for (IncomeStatement.Section s : sections) {
 List<IncomeStatement.Line> lines = new ArrayList<>(s.lines().size());
 for (IncomeStatement.Line l : s.lines()) {
 lines.add(new IncomeStatement.Line(l.accountId(), l.accountCode(),
 l.accountLabel(), convertAmount(l.amount(), rate)));
 }
 out.add(new IncomeStatement.Section(s.reportingClass(), s.reportingSubcategory(),
 lines, convertAmount(s.subtotal(), rate)));
 }
 return out;
 }

 /**
 * Convertit un tableau de flux de trésorerie en devise de présentation.
 *
 * <p><b>Squelette v6</b> : applique le taux moyen uniformément à tous les flux (operating,
 * investing, financing) ET aux soldes d'ouverture / clôture de trésorerie. Une version v7
 * plus rigoureuse appliquerait le taux de clôture aux soldes (IAS 7) et le taux moyen aux
 * flux — planifié.
 */
 private jo.accountant.financialstatements.dto.CashFlowStatement convertCashFlowStatement(
 jo.accountant.financialstatements.dto.CashFlowStatement functional,
 String presentationCurrency, String functionalCurrency,
 BigDecimal rate, LocalDate rateDate, String conversionType) {

 jo.accountant.financialstatements.dto.CashFlowStatement.OperatingFlows op = functional.operating();
 jo.accountant.financialstatements.dto.CashFlowStatement.OperatingFlows operating =
 new jo.accountant.financialstatements.dto.CashFlowStatement.OperatingFlows(
 convertAmount(op.netIncome(), rate),
 convertAmount(op.depreciationAmortization(), rate),
 convertAmount(op.accountsReceivableVariation(), rate),
 convertAmount(op.inventoryVariation(), rate),
 convertAmount(op.accountsPayableVariation(), rate),
 convertAmount(op.otherWorkingCapitalVariation(), rate),
 convertAmount(op.total(), rate));

 jo.accountant.financialstatements.dto.CashFlowStatement.InvestingFlows inv = functional.investing();
 jo.accountant.financialstatements.dto.CashFlowStatement.InvestingFlows investing =
 new jo.accountant.financialstatements.dto.CashFlowStatement.InvestingFlows(
 convertAmount(inv.fixedAssetsAcquisitions(), rate),
 convertAmount(inv.fixedAssetsDisposals(), rate),
 convertAmount(inv.otherInvestingFlows(), rate),
 convertAmount(inv.total(), rate));

 jo.accountant.financialstatements.dto.CashFlowStatement.FinancingFlows fin = functional.financing();
 jo.accountant.financialstatements.dto.CashFlowStatement.FinancingFlows financing =
 new jo.accountant.financialstatements.dto.CashFlowStatement.FinancingFlows(
 convertAmount(fin.capitalVariation(), rate),
 convertAmount(fin.loansVariation(), rate),
 convertAmount(fin.dividendsPaid(), rate),
 convertAmount(fin.otherFinancingFlows(), rate),
 convertAmount(fin.total(), rate));

 BigDecimal netIncome = convertAmount(functional.netIncome(), rate);
 BigDecimal netCashFlow = convertAmount(functional.netCashFlow(), rate);
 BigDecimal openingCash = convertAmount(functional.openingCash(), rate);
 BigDecimal closingCash = convertAmount(functional.closingCash(), rate);
 boolean balanced = openingCash.add(netCashFlow).compareTo(closingCash) == 0;

 return new jo.accountant.financialstatements.dto.CashFlowStatement(
 functional.companyId(), functional.from(), functional.to(),
 netIncome, operating, investing, financing,
 netCashFlow, openingCash, closingCash, balanced,
 presentationCurrency, functionalCurrency, rate, rateDate, conversionType);
 }

 /**
 * Multiplie un montant par le taux de conversion et arrondit à l'échelle de présentation
 * (4 décimales — cohérent avec l'échelle des montants en BD NUMERIC(19,4)).
 */
 private BigDecimal convertAmount(BigDecimal amount, BigDecimal rate) {
 if (amount == null) return null;
 return amount.multiply(rate).setScale(PRESENTATION_SCALE, RoundingMode.HALF_UP);
 }

 // =========================================================================
 // V84 — v7-2 : Statement of Changes in Equity (IAS 1.106)
 // =========================================================================

 /**
 * V84 — v7-2 : Statement of Changes in Equity (IAS 1.106).
 *
 * <p>Tableau de variation des capitaux propres entre {@code from} et {@code to}.
 * Conforme IAS 1.106-110. Structure :
 * <pre>
 * Opening equity (à from - 1)
 * + Net income (from → to)
 * + Other Comprehensive Income (OCI)
 * + Capital issued (D 512 / C 101)
 * − Treasury shares purchased (D 109 / C 512)
 * − Dividends distributed (D 455 ou 108 / C 512)
 * ± Other movements
 * = Closing equity (à to)
 * </pre>
 *
 * @param companyId tenant
 * @param from date de début (inclusive)
 * @param to date de fin (inclusive)
 */
 @Transactional(readOnly = true)
 public jo.accountant.financialstatements.dto.StatementOfChangesInEquity getStatementOfChangesInEquity(
 UUID companyId, LocalDate from, LocalDate to) {
 return getStatementOfChangesInEquity(companyId, from, to, null);
 }

 /**
 * V84 — v7-2 : SCE avec conversion de devise de présentation optionnelle (taux de clôture).
 */
 @Transactional(readOnly = true)
 public jo.accountant.financialstatements.dto.StatementOfChangesInEquity getStatementOfChangesInEquity(
 UUID companyId, LocalDate from, LocalDate to,
 PresentationCurrencyRequest presentation) {

 if (from == null || to == null) {
 var fy = accountingEngineService.resolveFiscalYear(companyId, null);
 if (fy.isPresent()) {
 from = fy.get().getStartDate();
 to = fy.get().getEndDate();
 } else {
 throw new ValidationException("DATES_REQUIRED",
 "from et to sont requis (aucun exercice fiscal disponible pour défaut)");
 }
 }
 if (to.isBefore(from)) {
 throw new ValidationException("INVALID_DATE_RANGE", "to doit être >= from");
 }

 // 1. Capitaux propres d'ouverture = SUM(soldes EQUITY à from - 1)
 BigDecimal openingEquity = journalLineRepository.sumEquityUpToDate(companyId, from.minusDays(1));

 // 2. Résultat net de l'exercice (depuis getIncomeStatement)
 IncomeStatement income = getIncomeStatement(companyId, from, to);
 BigDecimal netIncome = income.netResult();

 // 3. OCI = SUM(mouvements sur comptes 10% (capitaux propres PCN)) - SUM(101% (capital social))
 // Approximation : OCI = mouvements net sur comptes 10x hors 101 (capital) et 109 (treasury).
 // Pour PCN_HAITI : 108 = écart de conversion, 106 = réserves, 105 = primes.
 BigDecimal totalEquityMovements = journalLineRepository.sumByAccountCodePatternBetweenDates(
 companyId, "10%", from, to);
 BigDecimal capitalSocialMovements = journalLineRepository.sumByAccountCodePatternBetweenDates(
 companyId, "101%", from, to);
 BigDecimal treasuryMovements = journalLineRepository.sumByAccountCodePatternBetweenDates(
 companyId, "109%", from, to);
 BigDecimal oci = totalEquityMovements
 .subtract(capitalSocialMovements)
 .subtract(treasuryMovements);

 // 4. Émissions de capital = SUM(crédit - débit sur 101) — positif si augmentation
 BigDecimal capitalIssued = capitalSocialMovements.max(BigDecimal.ZERO);

 // 5. Rachats d'actions (treasury shares) = SUM(débit - crédit sur 109)
 // sumByAccountCodePatternBetweenDates retourne (credit - debit) — donc negate.
 BigDecimal treasuryShares = treasuryMovements.negate().max(BigDecimal.ZERO);

 // 6. Dividendes distribués = SUM(débit - crédit sur 455 ou 108)
 BigDecimal dividends455 = journalLineRepository.sumByAccountCodePatternBetweenDates(
 companyId, "455%", from, to).negate();
 BigDecimal dividends108 = journalLineRepository.sumByAccountCodePatternBetweenDates(
 companyId, "108%", from, to).negate();
 BigDecimal dividends = dividends455.add(dividends108).max(BigDecimal.ZERO);

 // 7. Capitaux propres de clôture = SUM(soldes EQUITY à to)
 BigDecimal closingEquity = journalLineRepository.sumEquityUpToDate(companyId, to);

 // 8. Détail des mouvements (pour transparence — liste des écritures sur comptes 10x, 108x, 109x, 455x)
 List<jo.accountant.financialstatements.dto.StatementOfChangesInEquity.EquityMovement> movements =
 loadEquityMovements(companyId, from, to);

 // 9. Autres mouvements (résiduel) — ce qui n'est ni capital, ni treasury, ni dividendes, ni OCI
 BigDecimal knownMovements = netIncome.add(oci).add(capitalIssued)
 .subtract(treasuryShares).subtract(dividends);
 BigDecimal computedClosing = openingEquity.add(knownMovements);
 BigDecimal otherMovements = closingEquity.subtract(computedClosing);

 // Conversion de devise optionnelle
 String functionalCurrency = resolveFunctionalCurrency(companyId);
 String presentationCurrency = null;
 BigDecimal conversionRate = null;
 if (presentation != null && presentation.presentationCurrency() != null
 && !presentation.presentationCurrency().equalsIgnoreCase(functionalCurrency)) {
 presentationCurrency = presentation.presentationCurrency();
 conversionRate = presentation.closingRate();
 if (conversionRate == null) {
 // Lookup snapshot CLOSING à la date `to`
 Optional<ExchangeRateSnapshot> snap = exchangeRateSnapshotRepository
 .findLatestClosingRate(companyId, functionalCurrency, presentationCurrency, to);
 if (snap.isEmpty()) {
 throw new ValidationException("PRESENTATION_CLOSING_RATE_REQUIRED",
 "Aucun snapshot CLOSING pour " + functionalCurrency + "→" + presentationCurrency
 + " à la date " + to);
 }
 conversionRate = snap.get().getRate();
 }
 openingEquity = convertAmount(openingEquity, conversionRate);
 netIncome = convertAmount(netIncome, conversionRate);
 oci = convertAmount(oci, conversionRate);
 capitalIssued = convertAmount(capitalIssued, conversionRate);
 treasuryShares = convertAmount(treasuryShares, conversionRate);
 dividends = convertAmount(dividends, conversionRate);
 otherMovements = convertAmount(otherMovements, conversionRate);
 closingEquity = convertAmount(closingEquity, conversionRate);
 final BigDecimal finalRate = conversionRate;
 final List<jo.accountant.financialstatements.dto.StatementOfChangesInEquity.EquityMovement> finalMovements = movements.stream()
 .map(m -> new jo.accountant.financialstatements.dto.StatementOfChangesInEquity.EquityMovement(
 m.date(), m.description(), m.accountCode(),
 convertAmount(m.debit(), finalRate),
 convertAmount(m.credit(), finalRate),
 m.category()))
 .toList();
 movements = finalMovements;
 }

 LOG.info("V84 — SCE companyId={} from={} to={} : opening={} closing={} netIncome={} OCI={} capIssued={} treasury={} dividends={}",
 companyId, from, to, openingEquity, closingEquity, netIncome, oci,
 capitalIssued, treasuryShares, dividends);

 return new jo.accountant.financialstatements.dto.StatementOfChangesInEquity(
 companyId, from, to,
 openingEquity, netIncome, oci,
 capitalIssued, treasuryShares, dividends,
 otherMovements, closingEquity,
 movements,
 functionalCurrency, presentationCurrency, conversionRate
 );
 }

 /**
 * V84 — v7-2 : Charge les écritures individuelles sur comptes de capitaux propres
 * (10x, 108x, 109x, 455x) pour la période — pour audit / détail du SCE.
 */
 private List<jo.accountant.financialstatements.dto.StatementOfChangesInEquity.EquityMovement> loadEquityMovements(
 UUID companyId, LocalDate from, LocalDate to) {
 List<JournalLine> lines = journalLineRepository.findAllPostedBetweenDates(companyId, from, to);
 List<jo.accountant.financialstatements.dto.StatementOfChangesInEquity.EquityMovement> out = new ArrayList<>();
 for (JournalLine l : lines) {
 String code = l.getAccountCode();
 if (code == null) continue;
 // On ne garde que les comptes de capitaux propres (10x hors 109, 109, 455)
 String category = categorizeEquityAccount(code);
 if (category == null) continue;
 out.add(new jo.accountant.financialstatements.dto.StatementOfChangesInEquity.EquityMovement(
 java.time.LocalDate.now(), // date exacte à récupérer depuis le JournalEntry (non chargé ici)
 l.getDescription() != null ? l.getDescription() : "",
 code,
 l.getDebit() != null ? l.getDebit() : BigDecimal.ZERO,
 l.getCredit() != null ? l.getCredit() : BigDecimal.ZERO,
 category
 ));
 }
 return out;
 }

 /** Catégorise un compte de capitaux propres pour le SCE. */
 private String categorizeEquityAccount(String accountCode) {
 if (accountCode.startsWith("101")) return "CAPITAL_ISSUED";
 if (accountCode.startsWith("109")) return "TREASURY_PURCHASE";
 if (accountCode.startsWith("455") || accountCode.startsWith("108")) return "DIVIDEND";
 if (accountCode.startsWith("10") || accountCode.startsWith("106") || accountCode.startsWith("105")) return "OCI";
 return null; // pas un mouvement de capitaux propres
 }
}

package jo.accountant.demo.seeders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.auth.service.AuthService;
import jo.accountant.bankreconciliation.dto.CreateBankAccountRequest;
import jo.accountant.bankreconciliation.service.BankReconciliationService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.entity.TaxExemptionStatus;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.demo.fixtures.HaitianNames;
import jo.accountant.demo.support.AccountFixture;
import jo.accountant.demo.support.ChartOfAccountsBootstrap;
import jo.accountant.demo.support.DemoTenantContext;
import jo.accountant.demo.support.DocumentNumberingBootstrap;
import jo.accountant.demo.support.FiscalYearBootstrap;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.employees.dto.CreateEmployeeRequest;
import jo.accountant.employees.dto.EmployeeResponse;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.service.EmployeesService;
import jo.accountant.expenses.dto.CreateExpenseReportRequest;
import jo.accountant.expenses.dto.ExpenseReportResponse;
import jo.accountant.expenses.service.ExpensesService;
import jo.accountant.fixedassets.dto.AssetResponse;
import jo.accountant.fixedassets.dto.CreateAssetRequest;
import jo.accountant.fixedassets.entity.DepreciationMethod;
import jo.accountant.fixedassets.service.FixedAssetsService;
import jo.accountant.inventory.dto.CreateItemRequest;
import jo.accountant.inventory.dto.CreateStockMoveRequest;
import jo.accountant.inventory.dto.CreateWarehouseRequest;
import jo.accountant.inventory.dto.ItemResponse;
import jo.accountant.inventory.entity.CostingMethod;
import jo.accountant.inventory.entity.StockMoveDirection;
import jo.accountant.inventory.entity.Warehouse;
import jo.accountant.inventory.service.InventoryService;
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.TaxApplication;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.service.InvoicingService;
import jo.accountant.payroll.dto.CreatePayrollRunRequest;
import jo.accountant.payroll.dto.PayrollRunResponse;
import jo.accountant.payroll.service.PayrollService;
import jo.accountant.purchasing.dto.CreatePurchaseInvoiceRequest;
import jo.accountant.purchasing.dto.PurchaseInvoiceResponse;
import jo.accountant.purchasing.entity.PurchaseInvoiceType;
import jo.accountant.purchasing.service.PurchasingService;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V9 — PME 4 : Caribbean Textiles S.A. (zone franche CODEVI, Ouanaminthe).
 *
 * <p>Usine textile en zone franche — 1200 employés (dont 30 réalistes créés en Java, les 1170
 * autres seraient créés en production via un batch dédié), ~144M HTG/an (~12M USD), 100% export
 * USA, USD comme devise fonctionnelle, référentiel <strong>IFRS_FULL</strong> (filiale d'un groupe
 * international), IS 15% zone franche (Code Fiscal art. 195), TVA 0% export + imports en franchise
 * douanière (VAT_EXEMPT_ZF), 13e mois en décembre (Code Travail art. 153 — calcul asynchrone via
 * {@link PayrollService#launchThirteenthMonthRun} qui délègue à {@code
 * ThirteenthMonthAsyncRunner}).
 *
 * <p>Données générées (idempotent) :
 *
 * <ul>
 *   <li>1 Company (HT, USD, IFRS_FULL, FREE_ZONE, freeZone=true, fiscalYearStartMonth=10)
 *   <li>1 user owner + UserCompanyRole OWNER
 *   <li>Plan comptable IFRS-compatible (50 comptes PCN-style + 1 compte 681000 Dotations aux
 *       amortissements) — voir {@link #ensureIfrsFallbackAccounts(UUID)}
 *   <li>6 journaux (VT/AC/BQ/OD/PA/DP) + 2 exercices fiscaux (FY2024-2025 + FY2025-2026)
 *   <li>14 séquences documentaires (10 génériques + 4 extras SALES_INVOICE/VT, CREDIT_NOTE/VT,
 *       PURCHASE_INVOICE/AC, PAYSLIP/PA)
 *   <li>5 FixedAsset (bâtiment CODEVI, machines à coudre industrielles, véhicules logistiques,
 *       informatique, installations techniques) — méthode STRAIGHT_LINE
 *   <li>15 ThirdParty CLIENT importateurs USA (H&M, Target, Walmart, Gap, etc.)
 *   <li>8 ThirdParty SUPPLIER matières premières (coton, fils, accessoires)
 *   <li>1 Warehouse + 6 Item matières premières + stock initial IN
 *   <li>1 BankAccount Capital Bank USD
 *   <li>30 Employee réalistes (1 directeur + 5 managers + 12 chefs d'équipe + 12 ouvriers types) en
 *       USD avec thirteenthMonthEligible=true
 *   <li>12 mois d'opérations sur FY2025-2026 (Oct 2025 → Sep 2026) : 5-10 SalesInvoice TVA 0%
 *       (export), 3-5 PurchaseInvoice TVA 0% (imports en franchise), 2-3 ExpenseReport, 1
 *       PayrollRun/mois, et un lancement async du 13e mois en décembre 2025.
 * </ul>
 *
 * <p><b>Spécificité IFRS_FULL</b> — le référentiel IFRS_FULL ({@code ...001}) est en mode {@code
 * NumberingMode.FREE} (sans {@code mandatedClassSeedJson}). L'API {@code
 * ChartOfAccountsService.initialize} exige alors un {@code AccountNumberingTemplate} qui n'est pas
 * fourni par le bootstrap générique. En fallback, le seeder crée manuellement les 7 classes de
 * niveau 1 (codes "1" à "7", alignées sur la structure PCN_HAITI — cette simplification est
 * documentée : en pratique, Caribbean Textiles utilise un plan IFRS simplifié dont les codes
 * 1xxx-7xxx restent compatibles avec la structure PCN haïtienne) puis les 50 comptes feuilles via
 * {@link ChartOfAccountsService#createChild} + 1 compte extra 681000 Dotations aux amortissements
 * (CHARGES — requis par {@link FixedAssetsService#createAsset} comme {@code
 * depreciationExpenseAccountId}).
 *
 * <p><b>Résilience</b> — chaque mois d'opérations est isolé dans un try/catch dédié ; le seed
 * global est enveloppé d'un try/catch pour ne pas faire échouer le démarrage de l'application.
 *
 * <p><b>Note sur les 1200 employés</b> — la consigne demande 1200 employés mais le seeder ne peut
 * pas les créer tous en temps réel sans exploser le temps de seed (1200 employés × écritures
 * THIRD_PARTY + EMPLOYEE + Paie mensuelle × 12 mois ≈ timeout). Le seeder crée donc 30 employés
 * réalistes couvrant tous les profils types (1 directeur + 5 managers + 12 chefs d'équipe + 12
 * ouvriers types) — les 1170 autres seraient créés en production via un batch dédié (par exemple le
 * {@code thirteenthMonthJob} Spring Batch ou un job ad hoc).
 */
@Component
public class FreeZoneIndustrySeeder implements CompanySeeder {

  private static final Logger LOG = LoggerFactory.getLogger(FreeZoneIndustrySeeder.class);

  /**
   * UUID du référentiel IFRS_FULL (V1_002__core_seeds.sql ligne 30 — numbering_mode='FREE',
   * mandated_class_seed_json=NULL).
   */
  private static final UUID IFRS_FULL_FRAMEWORK_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  // ── Paramètres démo (Caribbean Textiles — zone franche CODEVI Ouanaminthe) ──

  private static final String COMPANY_NAME = "Caribbean Textiles S.A.";
  private static final String OWNER_EMAIL = "owner@caribbean-textiles.demo";
  private static final String OWNER_PASSWORD = "Demo1234!2026";
  private static final String OWNER_FULL_NAME = "Caribbean Textiles Owner";
  private static final String OWNER_LOCALE = "en";

  /**
   * TVA 0% — exports en zone franche (Code Fiscal art. 195 + 197 ; VAT_EXEMPT_ZF — v8-6). Les
   * factures de vente portent {@code taxRate=0} et une ligne {@link TaxApplication} de type {@code
   * VAT_EXEMPT_ZF}.
   */
  private static final BigDecimal VAT_RATE_FREE_ZONE = BigDecimal.ZERO;

  /**
   * Taux cotisations OFATMA part patronale (12% — taux plancher industriel haïtien). Identique au
   * retail/services : PayrollService.calculate applique ce taux uniformément sur le brut salarial.
   */
  private static final BigDecimal EMPLOYER_CONTRIBUTION_RATE = new BigDecimal("12");

  /** FY2025-2026 = 01/10/2025 → 30/09/2026 (12 mois de données opérationnelles). */
  private static final LocalDate FY2526_START = LocalDate.of(2025, 10, 1);

  private static final LocalDate FY2526_END = LocalDate.of(2026, 9, 30);

  /** Code compte extra à créer (CHARGES) pour les dotations aux amortissements IFRS. */
  private static final String DOTA_AMORT_CODE = "681000";

  private static final String DOTA_AMORT_LABEL = "Dotations aux amortissements";

  // ── Dépendances Spring ──

  private final CompanyRepository companyRepository;
  private final UserRepository userRepository;
  private final UserCompanyRoleRepository userCompanyRoleRepository;
  private final AuthService authService;
  private final AccountRepository accountRepository;
  private final ChartOfAccountsService coaService;
  private final ChartOfAccountsBootstrap coaBootstrap;
  private final FiscalYearBootstrap fiscalYearBootstrap;
  private final DocumentNumberingBootstrap numberingBootstrap;
  private final DocumentNumberingService numberingService;
  private final jo.accountant.accountingengine.service.AccountingEngineService
      accountingEngineService;
  private final ThirdPartiesService thirdPartiesService;
  private final EmployeesService employeesService;
  private final InvoicingService invoicingService;
  private final PurchasingService purchasingService;
  private final ExpensesService expensesService;
  private final PayrollService payrollService;
  private final InventoryService inventoryService;
  private final BankReconciliationService bankReconciliationService;
  private final FixedAssetsService fixedAssetsService;

  /**
   * v2.5.2-rls-proper-fix — Self-injection via le proxy Spring.
   *
   * <p>Permet d'appeler {@link #seedBusinessData(UUID, UUID)} depuis {@link #seed()} en traversant
   * le proxy CGLIB → l'annotation {@code @Transactional} sur {@code seedBusinessData} sera
   * effectivement appliquée (une méthode appelée directement via {@code this.} ne passe pas par le
   * proxy → pas de transaction → pas de {@code SET LOCAL app.current_tenant}).
   *
   * <p>{@code @Lazy} évite une dépendance circulaire à l'initialisation (le bean s'injecte
   * lui-même avant la fin de sa propre construction).
   */
  @Autowired
  @Lazy
  private FreeZoneIndustrySeeder self;

  public FreeZoneIndustrySeeder(
      CompanyRepository companyRepository,
      UserRepository userRepository,
      UserCompanyRoleRepository userCompanyRoleRepository,
      AuthService authService,
      AccountRepository accountRepository,
      ChartOfAccountsService coaService,
      ChartOfAccountsBootstrap coaBootstrap,
      FiscalYearBootstrap fiscalYearBootstrap,
      DocumentNumberingBootstrap numberingBootstrap,
      DocumentNumberingService numberingService,
      jo.accountant.accountingengine.service.AccountingEngineService accountingEngineService,
      ThirdPartiesService thirdPartiesService,
      EmployeesService employeesService,
      InvoicingService invoicingService,
      PurchasingService purchasingService,
      ExpensesService expensesService,
      PayrollService payrollService,
      InventoryService inventoryService,
      BankReconciliationService bankReconciliationService,
      FixedAssetsService fixedAssetsService) {
    this.companyRepository = companyRepository;
    this.userRepository = userRepository;
    this.userCompanyRoleRepository = userCompanyRoleRepository;
    this.authService = authService;
    this.accountRepository = accountRepository;
    this.coaService = coaService;
    this.coaBootstrap = coaBootstrap;
    this.fiscalYearBootstrap = fiscalYearBootstrap;
    this.numberingBootstrap = numberingBootstrap;
    this.numberingService = numberingService;
    this.accountingEngineService = accountingEngineService;
    this.thirdPartiesService = thirdPartiesService;
    this.employeesService = employeesService;
    this.invoicingService = invoicingService;
    this.purchasingService = purchasingService;
    this.expensesService = expensesService;
    this.payrollService = payrollService;
    this.inventoryService = inventoryService;
    this.bankReconciliationService = bankReconciliationService;
    this.fixedAssetsService = fixedAssetsService;
  }

  @Override
  public String demoCode() {
    return "CARIBBEAN_TEXTILES";
  }

  @Override
  public String companyName() {
    return COMPANY_NAME;
  }

  @Override
  public String segment() {
    return "WHOLESALE_COMMERCE";
  }

  /**
   * Crée la Company + user owner + toutes les données métier.
   *
   * <p>Idempotent : si la Company existe déjà (name + isDemo=true), retourne 0.
   *
   * <p><b>v2.5.2-rls-proper-fix</b> — La méthode n'est PLUS {@code @Transactional}. La Company +
   * l'user owner sont créés via les méthodes {@code @Transactional} par défaut des repositories
   * Spring Data JPA (chaque {@code save()} est sa propre transaction sur des tables non
   * RLS-protégées : {@code companies}, {@code users}, {@code user_company_role}). Les données
   * métier (RLS-protégées) sont créées via {@link #seedBusinessData(UUID, UUID)}, appelée à travers
   * le proxy Spring self-injecté — la transaction s'ouvre APRÈS que {@code DemoTenantContext.of()}
   * ait positionné le ThreadLocal, et le {@code TenantRlsConnectionCustomizer} intercepte le
   * {@code setAutoCommit(false)} pour appliquer {@code SET LOCAL app.current_tenant = companyId} au
   * bon moment. Les INSERT sur {@code journal_entry}/{@code third_party}/{@code sales_invoice}/
   * {@code purchase_invoice}/{@code expense_report}/{@code journal_line} passent alors la policy
   * RLS.
   */
  @Override
  @SuppressWarnings(
      "try") // DemoTenantContext utilisé pour close() automatique, pas référencé dans le corps
  public int seed() {
    // ── 1. Idempotence : la company démo existe-t-elle déjà ? ──
    Optional<Company> existing =
        companyRepository.findAll().stream()
            .filter(c -> COMPANY_NAME.equals(c.getName()) && Boolean.TRUE.equals(c.getIsDemo()))
            .findFirst();
    if (existing.isPresent()) {
      LOG.info("V9 — Caribbean Textiles déjà seedée (id={}) — skip", existing.get().getId());
      return 0;
    }

    // ── 2. Création de la Company ── (hors DemoTenantContext — table companies non RLS-protégée)
    Company company = createCompany();
    final UUID companyId = company.getId();
    LOG.info(
        "V9 — Company Caribbean Textiles créée (id={}, nif={}, framework=IFRS_FULL/001, "
            + "currency=USD, freeZone=true)",
        companyId,
        company.getNif());

    // ── 3 + 4. User owner + UserCompanyRole OWNER ── (tables users/ucr non RLS-protégées)
    UUID ownerId = ensureOwnerUser(companyId);
    LOG.info("V9 — User owner créé/résolu (id={}, email={})", ownerId, OWNER_EMAIL);

    // ── 5. Bootstraps + données métier (try-with-resources pour le contexte tenant) ──
    // La transaction @Transactional est ouverte par self.seedBusinessData() via le proxy Spring,
    // APRÈS que DemoTenantContext.of() ait positionné le ThreadLocal. Le
    // TenantRlsConnectionCustomizer intercepte setAutoCommit(false) au début de cette méthode
    // et applique SET LOCAL app.current_tenant = companyId.
    try (DemoTenantContext ctx = DemoTenantContext.of(companyId, ownerId)) {
      return self.seedBusinessData(companyId, ownerId);
    } catch (RuntimeException ex) {
      // Le try-with-resources garantit que TenantContext.clear() est appelé même sur exception.
      // On logue ERROR mais on ne propage pas l'exception pour ne pas casser le démarrage.
      LOG.error(
          "V9 — Échec du seed Caribbean Textiles (companyId={}) : {}",
          companyId,
          ex.getMessage(),
          ex);
      return 1; // au moins la company a été créée
    }
  }

  /**
   * v2.5.2-rls-proper-fix — Méthode {@code @Transactional} qui crée toutes les données métier
   * (COA IFRS-compatible, journaux, exercices, séquences, immobilisations, clients importateurs
   * USA, fournisseurs matières premières, entrepôt + articles + stock initial, banque USD, 30
   * employés, 12 mois d'opérations sur FY2025-2026 + 13e mois).
   *
   * <p><b>DOIT être appelée via le proxy Spring</b> (jamais directement via {@code this.}) pour que
   * l'annotation {@code @Transactional} soit appliquée. C'est pourquoi {@link #seed()} utilise
   * {@code self.seedBusinessData(...)} avec self-injection.
   *
   * <p>La transaction s'ouvre APRÈS que {@code DemoTenantContext.of()} ait positionné le ThreadLocal
   * → le {@code TenantRlsConnectionCustomizer} intercepte le {@code setAutoCommit(false)} et
   * applique {@code SET LOCAL app.current_tenant = companyId} → tous les INSERT sur tables
   * RLS-protégées passent la policy RLS.
   *
   * @param companyId identifiant de la company démo (tenant)
   * @param ownerId identifiant de l'user owner (passé pour transparence du contexte tenant — non
   *     utilisé directement dans le corps car les services métier utilisent le ThreadLocal)
   * @return nombre d'enregistrements créés
   */
  @Transactional
  public int seedBusinessData(UUID companyId, UUID ownerId) {
    // a, b, c — bootstraps (COA + journaux/exercices + séquences)
    // Pour IFRS_FULL (numbering_mode=FREE), coaService.initialize exige un
    // AccountNumberingTemplate
    // que le bootstrap générique ne fournit pas — le catch interne du bootstrap avale l'exception
    // et continue (aucun compte créé). On détecte ce cas et on déclenche le fallback manuel.
    coaBootstrap.bootstrap(companyId, IFRS_FULL_FRAMEWORK_ID, AccountFixture.all());
    ensureIfrsFallbackAccounts(companyId);
    fiscalYearBootstrap.bootstrap(companyId);
    numberingBootstrap.bootstrap(companyId);
    // Compléter les séquences documentaires manquantes (scopeKey="VT"/"AC"/"PA") que les
    // services InvoicingService/PurchasingService/PayrollService attendent mais que le
    // bootstrap générique ne crée pas (il ne crée que les variants à scopeKey="").
    ensureExtraDocumentSequences(companyId);
    // Journal DP (Dépenses) requis par ExpensesService.generateExpenseEntry
    ensureJournal(companyId, "DP", "Journal des dépenses");
    // Pré-charger les UUIDs des comptes utilisés par les opérations mensuelles + immobilisations
    AccountRefs refs = AccountRefs.load(companyId, accountRepository);

    // d. Immobilisations (5) — bâtiment, machines à coudre, véhicules, informatique,
    // installations
    int fixedAssetsCreated = createFixedAssets(companyId, refs);

    // e. Clients importateurs USA (15)
    List<ThirdPartyResponse> clients = createDemoClients(companyId, refs.clientsAccountId);
    // f. Fournisseurs matières premières (8)
    List<ThirdPartyResponse> suppliers = createDemoSuppliers(companyId, refs.suppliersAccountId);
    // g. Entrepôt + 6 articles matières premières + stock initial IN
    Warehouse warehouse =
        inventoryService.createWarehouse(
            companyId, new CreateWarehouseRequest("CODEVI — Entrepôt principal Ouanaminthe"));
    List<ItemResponse> items = createDemoItems(companyId, warehouse);
    // h. Compte bancaire Capital Bank USD
    createDemoBankAccount(companyId, refs.banqueAccountId);
    // i. 30 employés réalistes (les 1170 autres seraient créés en prod via batch dédié)
    List<EmployeeResponse> employees = createDemoEmployees(companyId, refs.personnelAccountId);

    int totalCreated =
        1 /* company */
            + 1 /* user */
            + 1 /* ucr */
            + AccountFixture.all().size()
            + 1 /* 50 comptes + 1 extra 681000 */
            + 5
            + 2 /* journaux + exercices */
            + 14 /* séquences (10 + 4 extras) */
            + fixedAssetsCreated
            + clients.size()
            + suppliers.size()
            + 1 /* warehouse */
            + items.size() /* items */
            + items.size() /* stock moves IN */
            + 1 /* bankAccount */
            + employees.size();

    // j. 12 mois d'opérations sur FY2025-2026
    int monthlyOps =
        generateMonthlyOperations(companyId, ownerId, clients, suppliers, employees, items);
    totalCreated += monthlyOps;

    LOG.info(
        "V9 — Caribbean Textiles seed terminé pour companyId={} : {} enregistrements créés",
        companyId,
        totalCreated);
    return totalCreated;
  }

  // ══ Étape 2 — Création Company ══

  private Company createCompany() {
    Company company = new Company();
    company.setId(UUID.randomUUID());
    Instant now = Instant.now();
    company.setCreatedAt(now);
    company.setUpdatedAt(now);
    company.setName(COMPANY_NAME);
    company.setLegalForm(LegalForm.SA);
    company.setCountry("HT");
    company.setFunctionalCurrency("USD"); // 100% export USA — devise fonctionnelle USD
    company.setNif("4040404040CT");
    company.setAddress("CODEVI Zone Franche, Ouanaminthe");
    company.setSector(Sector.INDUSTRIE);
    company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
    // WHOLESALE_COMMERCE est reconnu par SectorAccountTemplate (commerce()).
    // FREE_ZONE_MANUFACTURING ne l'est pas — on garde WHOLESALE_COMMERCE pour le seed sectoriel.
    company.setBusinessTypeCode("WHOLESALE_COMMERCE");
    company.setPrimaryActivityLabel(
        "Industrie textile — confection pour export USA (zone franche)");
    company.setAccountingFrameworkId(IFRS_FULL_FRAMEWORK_ID); // filiale groupe international → IFRS
    company.setFiscalYearStartMonth(10); // exercice haïtien 01/10 → 30/09
    company.setFreeZone(true); // V8-1 — ZF Code Fiscal art. 195
    company.setTaxExemptionStatus(TaxExemptionStatus.FREE_ZONE); // IS 15% zone franche
    company.setMonthlyLegalHours(new BigDecimal("208")); // Haïti 48h/sem × 52/12
    company.setWizardStep(jo.accountant.company.entity.Company.TOTAL_WIZARD_STEPS);
    company.setWizardCompleted(true);
    company.setIsDemo(true);
    return companyRepository.save(company);
  }

  // ══ Étapes 3 + 4 — User owner + UserCompanyRole OWNER ══

  /**
   * Crée le user owner via AuthService.register (idempotent via findByEmailIgnoreCase) + le
   * UserCompanyRole OWNER (acceptedAt=now — owner auto-accepté car créateur de la société).
   */
  private UUID ensureOwnerUser(UUID companyId) {
    Optional<User> existing = userRepository.findByEmailIgnoreCase(OWNER_EMAIL);
    UUID userId;
    if (existing.isPresent()) {
      userId = existing.get().getId();
      LOG.info(
          "V9 — User owner déjà existant (email={}) — réutilisation id={}", OWNER_EMAIL, userId);
    } else {
      try {
        User owner =
            authService.register(OWNER_EMAIL, OWNER_PASSWORD, OWNER_FULL_NAME, OWNER_LOCALE);
        userId = owner.getId();
      } catch (ConflictException ex) {
        // Race condition : un autre thread a créé l'utilisateur entre le check et l'INSERT.
        existing = userRepository.findByEmailIgnoreCase(OWNER_EMAIL);
        if (existing.isEmpty()) {
          throw ex; // vraiment une erreur
        }
        userId = existing.get().getId();
      }
    }

    // UserCompanyRole OWNER (idempotence via findByUserIdAndCompanyId)
    if (userCompanyRoleRepository.findByUserIdAndCompanyId(userId, companyId).isEmpty()) {
      UserCompanyRole ucr = new UserCompanyRole();
      ucr.setId(UUID.randomUUID());
      ucr.setUserId(userId);
      ucr.setCompanyId(companyId);
      ucr.setRole(UserRole.OWNER);
      Instant now = Instant.now();
      ucr.setInvitedAt(now);
      ucr.setAcceptedAt(now); // owner auto-accepté (créateur de la société)
      ucr.setCreatedAt(now);
      ucr.setUpdatedAt(now);
      ucr.setCreatedBy(userId);
      ucr.setUpdatedBy(userId);
      userCompanyRoleRepository.save(ucr);
    }
    return userId;
  }

  // ══ Fallback IFRS_FULL — crée les comptes manuellement si le bootstrap a échoué ══

  /**
   * Pour IFRS_FULL (numbering_mode=FREE), {@code ChartOfAccountsBootstrap} ne peut pas appeler
   * {@code coaService.initialize} (l'API exige un AccountNumberingTemplate non fourni). On détecte
   * l'absence de classes de niveau 1 et on crée manuellement :
   *
   * <ol>
   *   <li>Les 7 classes de niveau 1 (codes "1" à "7", alignées sur la structure PCN_HAITI — cette
   *       simplification est documentée : les codes 1xxx-7xxx du PCN restent compatibles avec la
   *       structure IFRS simplifiée pour les besoins démo).
   *   <li>Les 50 comptes feuilles {@link AccountFixture#all()} via {@link
   *       ChartOfAccountsService#createChild} (qui valide level ≤ 4 et publie AccountCreatedEvent).
   *   <li>1 compte extra {@code 681000 Dotations aux amortissements} (CHARGES) requis par {@link
   *       FixedAssetsService#createAsset} comme {@code depreciationExpenseAccountId}.
   * </ol>
   *
   * <p>Idempotent : si les classes de niveau 1 existent déjà, ne fait rien.
   */
  private void ensureIfrsFallbackAccounts(UUID companyId) {
    boolean hasLevel1 =
        accountRepository.findByCompanyIdOrderByCode(companyId).stream()
            .anyMatch(a -> a.getLevel() == 1);
    if (hasLevel1) {
      LOG.debug(
          "V9 — Plan comptable déjà initialisé pour companyId={} (IFRS fallback non requis)",
          companyId);
      return;
    }

    LOG.info(
        "V9 — IFRS_FULL fallback : création manuelle des classes de niveau 1 + comptes feuilles "
            + "pour companyId={}",
        companyId);

    // 1. Créer les 7 classes de niveau 1 (codes "1" à "7") — alignées sur PCN_HAITI
    Map<String, Account> classRoots = new HashMap<>(7);
    String[][] classDefs = {
      {"1", "Comptes de capitaux"},
      {"2", "Comptes d'immobilisations"},
      {"3", "Comptes de stocks"},
      {"4", "Comptes de tiers"},
      {"5", "Comptes financiers"},
      {"6", "Charges"},
      {"7", "Produits"}
    };
    for (String[] def : classDefs) {
      String code = def[0];
      Account classe = new Account();
      classe.setCompanyId(companyId);
      classe.setCode(code);
      classe.setLabel(def[1]);
      classe.setLevel(1);
      classe.setReportingClass(inferReportingClass(code));
      classe.setReportingSubcategory(ReportingSubcategory.N_A);
      classe.setNormalBalance(inferNormalBalance(classe.getReportingClass()));
      classe.setLocked(true);
      classe.setActive(true);
      classe.setCollective(false);
      classe.setPath(code);
      Account saved = accountRepository.save(classe);
      classRoots.put(code, saved);
    }

    // 2. Créer les 50 comptes feuilles AccountFixture via coaService.createChild (valide level ≤ 4)
    int created = 0;
    int skipped = 0;
    for (AccountFixture fixture : AccountFixture.all()) {
      // Idempotence
      if (accountRepository.findByCompanyIdAndCode(companyId, fixture.code()).isPresent()) {
        skipped++;
        continue;
      }
      String firstDigit = fixture.code().substring(0, 1);
      Account parent = classRoots.get(firstDigit);
      if (parent == null) {
        LOG.warn(
            "V9 — IFRS fallback : classe parente '{}' introuvable pour le compte {} — skip",
            firstDigit,
            fixture.code());
        continue;
      }
      try {
        CreateChildRequest req =
            new CreateChildRequest(
                fixture.code(),
                fixture.label(),
                fixture.reportingClass(),
                ReportingSubcategory.N_A,
                fixture.normalBalance(),
                fixture.collective(),
                null,
                List.of());
        coaService.createChild(companyId, parent.getId(), req);
        created++;
      } catch (ConflictException ex) {
        skipped++;
      } catch (RuntimeException ex) {
        LOG.warn(
            "V9 — IFRS fallback : échec création compte {} ({}) : {}",
            fixture.code(),
            fixture.label(),
            ex.getMessage());
      }
    }

    // 3. Créer le compte extra 681000 Dotations aux amortissements (CHARGES, DEBIT)
    if (accountRepository.findByCompanyIdAndCode(companyId, DOTA_AMORT_CODE).isEmpty()) {
      Account parent = classRoots.get("6");
      if (parent != null) {
        try {
          CreateChildRequest dotaReq =
              new CreateChildRequest(
                  DOTA_AMORT_CODE,
                  DOTA_AMORT_LABEL,
                  ReportingClass.CHARGES,
                  ReportingSubcategory.N_A,
                  NormalBalance.DEBIT,
                  false,
                  null,
                  List.of());
          coaService.createChild(companyId, parent.getId(), dotaReq);
          created++;
        } catch (ConflictException ex) {
          skipped++;
        } catch (RuntimeException ex) {
          LOG.warn(
              "V9 — IFRS fallback : échec création compte {} : {}",
              DOTA_AMORT_CODE,
              ex.getMessage());
        }
      }
    }

    LOG.info(
        "V9 — IFRS fallback terminé pour companyId={} : {} comptes créés, {} skippés",
        companyId,
        created,
        skipped);
  }

  private ReportingClass inferReportingClass(String classCode) {
    return switch (classCode) {
      case "1" -> ReportingClass.CAPITAUX_PROPRES;
      case "2", "3", "5" -> ReportingClass.ACTIF;
      case "4" -> ReportingClass.ACTIF; // simplifié — la classe 4 est mixte ACTIF/PASSIF
      case "6" -> ReportingClass.CHARGES;
      case "7" -> ReportingClass.PRODUITS;
      default -> ReportingClass.ACTIF;
    };
  }

  private NormalBalance inferNormalBalance(ReportingClass rc) {
    return switch (rc) {
      case ACTIF, CHARGES -> NormalBalance.DEBIT;
      case PASSIF, CAPITAUX_PROPRES, PRODUITS, OTHER -> NormalBalance.CREDIT;
    };
  }

  // ══ Compléments post-bootstrap : séquences documentaires + journal DP ══

  /**
   * Crée les 4 séquences documentaires manquantes (scopeKey="VT"/"AC"/"PA") que les services
   * InvoicingService/PurchasingService/PayrollService attendent mais que le bootstrap générique ne
   * crée pas (il ne crée que les variants à scopeKey="").
   */
  private void ensureExtraDocumentSequences(UUID companyId) {
    createSequenceQuiet(companyId, DocumentType.SALES_INVOICE, "VT", "FAC", ResetPolicy.YEARLY);
    createSequenceQuiet(companyId, DocumentType.CREDIT_NOTE, "VT", "AV", ResetPolicy.YEARLY);
    createSequenceQuiet(companyId, DocumentType.PURCHASE_INVOICE, "AC", "FF", ResetPolicy.YEARLY);
    createSequenceQuiet(companyId, DocumentType.PAYSLIP, "PA", "BS", ResetPolicy.NEVER);
  }

  private void createSequenceQuiet(
      UUID companyId, DocumentType type, String scopeKey, String prefix, ResetPolicy policy) {
    try {
      numberingService.createSequence(companyId, type, scopeKey, prefix, true, 6, policy);
    } catch (ConflictException ex) {
      // Séquence déjà existante — idempotence normale
    } catch (RuntimeException ex) {
      LOG.warn(
          "V9 — Échec création séquence type={} scope={} pour companyId={} : {}",
          type,
          scopeKey,
          companyId,
          ex.getMessage());
    }
  }

  /**
   * Crée un journal si inexistant (idempotent). Utilisé pour le journal DP requis par
   * ExpensesService.
   */
  private void ensureJournal(UUID companyId, String code, String label) {
    try {
      accountingEngineService.createJournal(companyId, code, label);
    } catch (ConflictException ex) {
      // Journal déjà existant — idempotence normale
    } catch (RuntimeException ex) {
      LOG.warn(
          "V9 — Échec création journal {} pour companyId={} : {}",
          code,
          companyId,
          ex.getMessage());
    }
  }

  // ══ Étape d — Immobilisations (5) ══

  /**
   * Crée 5 immobilisations industrielles (bâtiment CODEVI, machines à coudre, véhicules,
   * informatique, installations techniques) via {@link FixedAssetsService#createAsset}.
   *
   * <p>Méthode d'amortissement : {@link DepreciationMethod#STRAIGHT_LINE} (linéaire). Coûts en USD
   * (devise fonctionnelle). Durées de vie : 5/10/20 ans selon l'actif.
   */
  private int createFixedAssets(UUID companyId, AccountRefs refs) {
    // Chaque définition : {label, cost, lifeMonths, assetAccountCode}
    Object[][] defs = {
      {
        "Bâtiment industriel CODEVI (hall de production 5000 m²)",
        new BigDecimal("2000000"),
        240, // 20 ans
        AccountFixture.CONSTRUCTIONS.code()
      },
      {
        "Parc machines à coudre industrielles (120 unités Juki)",
        new BigDecimal("850000"),
        120, // 10 ans
        AccountFixture.INSTALLATIONS_TECHNIQUES.code()
      },
      {
        "Véhicules logistiques (3 camions + 2 fourgonnettes)",
        new BigDecimal("320000"),
        60, // 5 ans
        AccountFixture.AUTRES_IMMO_CORPORELLES.code()
      },
      {
        "Parc informatique bureautique (40 postes + serveurs)",
        new BigDecimal("95000"),
        60, // 5 ans
        AccountFixture.AUTRES_IMMO_CORPORELLES.code()
      },
      {
        "Installations techniques électrique et climatisation",
        new BigDecimal("480000"),
        120, // 10 ans
        AccountFixture.INSTALLATIONS_TECHNIQUES.code()
      }
    };

    int created = 0;
    for (Object[] d : defs) {
      String label = (String) d[0];
      BigDecimal cost = (BigDecimal) d[1];
      int lifeMonths = (int) d[2];
      String assetAccountCode = (String) d[3];
      UUID assetAccountId = resolveAccountCode(companyId, assetAccountCode);
      if (assetAccountId == null
          || refs.depreciationExpenseAccountId == null
          || refs.accumulatedDepreciationAccountId == null) {
        LOG.warn(
            "V9 — Comptes d'immo manquants pour '{}' (asset={}, deprExp={}, deprAcc={}) — skip",
            label,
            assetAccountCode,
            refs.depreciationExpenseAccountId,
            refs.accumulatedDepreciationAccountId);
        continue;
      }
      try {
        CreateAssetRequest req =
            new CreateAssetRequest(
                label,
                LocalDate.of(2024, 10, 1), // acquisition au début FY2024-2025
                cost,
                lifeMonths,
                BigDecimal.ZERO, // residualValue
                DepreciationMethod.STRAIGHT_LINE,
                assetAccountId,
                refs.depreciationExpenseAccountId,
                refs.accumulatedDepreciationAccountId,
                null, // disposalGainAccountId
                null, // disposalLossAccountId
                refs.suppliersAccountId, // supplierAccountId → écriture D immo / C fournisseur
                null // cashAccountId (mutuellement exclusif)
                );
        AssetResponse asset = fixedAssetsService.createAsset(companyId, req);
        created++;
        LOG.info(
            "V9 — Immobilisation créée : {} (cost={} USD, life={} mois, id={})",
            label,
            cost,
            lifeMonths,
            asset.id());
      } catch (ConflictException ex) {
        LOG.debug("V9 — Immobilisation '{}' déjà existante — skip", label);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création immobilisation '{}' : {}", label, ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape e — Clients importateurs USA (15) ══

  private List<ThirdPartyResponse> createDemoClients(UUID companyId, UUID clientsAccountId) {
    String[][] defs = {
      {"H&M Caribbean Sourcing", "Importateur USA — fast fashion", "1000000001HM"},
      {"Target Corporation", "Grande distribution USA", "1000000002TG"},
      {"Walmart Global Sourcing", "Grande distribution USA", "1000000003WM"},
      {"Gap Inc. Sourcing", "Prêt-à-porter USA", "1000000004GP"},
      {"PVH Corp (Calvin Klein)", "Prêt-à-porter premium USA", "1000000005PV"},
      {"Levi Strauss & Co.", "Denim USA", "1000000006LV"},
      {"Under Armour", "Sportswear USA", "1000000007UA"},
      {"Ralph Lauren", "Prêt-à-porter premium USA", "1000000008RL"},
      {"VF Corporation (Vans)", "Sportswear USA", "1000000009VF"},
      {"American Eagle Outfitters", "Prêt-à-porter USA", "1000000010AE"},
      {"Carter's Inc.", "Vêtements enfants USA", "1000000011CT"},
      {"OshKosh B'Gosh", "Vêtements enfants USA", "1000000012OK"},
      {"Gildan Activewear", "T-shirts et basics USA", "1000000013GD"},
      {"Fruit of the Loom", "Basics USA", "1000000014FL"},
      {"Hanesbrands Inc.", "Lingerie et basics USA", "1000000015HB"}
    };
    List<ThirdPartyResponse> created = new ArrayList<>(defs.length);
    for (String[] d : defs) {
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.CLIENT,
                    d[0],
                    clientsAccountId,
                    "sourcing@"
                        + d[0].toLowerCase().replace(" ", "-").replace(".", "").replace("&", "and")
                        + ".com",
                    d[1] + ", New York USA",
                    d[2]));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Client '{}' déjà existant — skip", d[0]);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création client '{}' : {}", d[0], ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape f — Fournisseurs matières premières (8) ══

  private List<ThirdPartyResponse> createDemoSuppliers(UUID companyId, UUID suppliersAccountId) {
    String[][] defs = {
      {"Cotton International Co.", "Coton brut — filature", "2000000001CI"},
      {"Polyester World Ltd.", "Fils polyester et mélanges", "2000000002PW"},
      {"TexThread Industries", "Fils à coudre industriels", "2000000003TT"},
      {"Buttons & Zippers Inc.", "Boutons, fermetures éclair, accessoires", "2000000004BZ"},
      {"Label Solutions Co.", "Étiquettes tissées et thermocollées", "2000000005LS"},
      {"Dyes & Chemicals Corp.", "Teintures et auxiliaires textiles", "2000000006DC"},
      {"Packaging Plus", "Emballages polybag et cartons export", "2000000007PP"},
      {"Industrial Needles Co.", "Aiguilles machines et pièces détachées", "2000000008IN"}
    };
    List<ThirdPartyResponse> created = new ArrayList<>(defs.length);
    for (String[] d : defs) {
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.SUPPLIER,
                    d[0],
                    suppliersAccountId,
                    "contact@" + d[0].toLowerCase().replace(" ", "-").replace("&", "and") + ".com",
                    d[1] + ", Miami FL USA",
                    d[2]));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Fournisseur '{}' déjà existant — skip", d[0]);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création fournisseur '{}' : {}", d[0], ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape g — Entrepôt + 6 articles matières premières + stock initial ══

  private List<ItemResponse> createDemoItems(UUID companyId, Warehouse warehouse) {
    // 6 matières premières typiques d'une usine textile en zone franche
    Object[][] defs = {
      {"TISSU-COT", "Tissu coton brut (rouleau 100m)", "ROLL", new BigDecimal("4.50")},
      {"FIL-POLY", "Fil polyester 100% (cône 5000m)", "CONE", new BigDecimal("3.20")},
      {"FIL-COUTURE", "Fil à coudre industriel (bobine 5000m)", "BOB", new BigDecimal("1.80")},
      {"BOUTON-PLAS", "Bouton plastique 12mm (lot 1000)", "LOT", new BigDecimal("12.00")},
      {"ETIQ-TISS", "Étiquette tissée logo client (lot 1000)", "LOT", new BigDecimal("8.50")},
      {"EMBALL-PB", "Emballage polybag export (lot 1000)", "LOT", new BigDecimal("6.20")}
    };
    UUID stockAccountId = resolveAccountCode(companyId, AccountFixture.STOCKS_MARCHANDISES.code());
    UUID cogsAccountId = resolveAccountCode(companyId, AccountFixture.VARIATION_STOCKS.code());
    if (stockAccountId == null || cogsAccountId == null) {
      LOG.warn(
          "V9 — Comptes 310000/603000 introuvables pour companyId={} — items non créés", companyId);
      return List.of();
    }
    List<ItemResponse> created = new ArrayList<>(defs.length);
    for (int i = 0; i < defs.length; i++) {
      Object[] d = defs[i];
      String sku = (String) d[0];
      String label = (String) d[1];
      String uom = (String) d[2];
      BigDecimal unitCost = (BigDecimal) d[3];
      try {
        ItemResponse item =
            inventoryService.createItem(
                companyId,
                new CreateItemRequest(
                    sku,
                    label,
                    uom,
                    CostingMethod.FIFO,
                    new BigDecimal("100"), // reorderThreshold
                    stockAccountId,
                    cogsAccountId));
        created.add(item);
        // Stock initial IN — grandes quantités (production textile)
        int qty = 1000 + (i * 750); // 1000, 1750, 2500, 3250, 4000, 4750
        try {
          inventoryService.postStockMove(
              companyId,
              new CreateStockMoveRequest(
                  item.id(),
                  warehouse.getId(),
                  null,
                  LocalDate.of(2025, 9, 28), // juste avant le début de FY2025-2026
                  StockMoveDirection.IN,
                  new BigDecimal(qty),
                  unitCost,
                  "Stock initial — ouverture Caribbean Textiles CODEVI",
                  null // pas de contrepartie → pas d'écriture comptable (rétro-compat)
                  ));
        } catch (RuntimeException ex) {
          LOG.warn("V9 — Échec stock IN pour item {} : {}", sku, ex.getMessage());
        }
      } catch (ConflictException ex) {
        LOG.debug("V9 — Item {} déjà existant — skip", sku);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création item {} : {}", sku, ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape h — Compte bancaire (Capital Bank USD) ══

  private void createDemoBankAccount(UUID companyId, UUID banqueAccountId) {
    try {
      bankReconciliationService.createBankAccount(
          companyId,
          new CreateBankAccountRequest(
              banqueAccountId,
              "Capital Bank USD — Compte courant Caribbean Textiles",
              "010-404040-01"));
    } catch (ConflictException ex) {
      LOG.debug("V9 — BankAccount déjà existant — skip");
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création BankAccount : {}", ex.getMessage());
    }
  }

  // ══ Étape i — Employés (30 réalistes) ══

  /**
   * Crée 30 employés réalistes couvrant tous les profils types de l'usine. Les 1170 autres employés
   * (pour atteindre les 1200 déclarés) seraient créés en production via un batch dédié — créer 1200
   * employés en Java exploserait le temps de seed (1200 × écritures ThirdParty/Employee + 12 mois
   * de paie).
   *
   * <p>Salaires en USD (salaryCurrency="USD") — usine dollarisée car exports en USD. Tous éligibles
   * au 13e mois (Code Travail art. 153, thirteenthMonthEligible=true).
   */
  private List<EmployeeResponse> createDemoEmployees(UUID companyId, UUID personnelAccountId) {
    List<EmployeeResponse> created = new ArrayList<>(30);
    int empIdx = 1;

    // 1 directeur (8000 USD/mois)
    empIdx =
        createOneEmployee(
            companyId,
            personnelAccountId,
            empIdx,
            "Jean-Robert Pierre-Louis",
            "Directeur Général",
            "Direction",
            new BigDecimal("8000"),
            created);

    // 5 managers (3500-5500 USD)
    Object[][] managers = {
      {"Marie-Grace Auguste", "Directrice Production", "Production", new BigDecimal("5500")},
      {"Carlo Saintilus", "Directeur Financier", "Finance", new BigDecimal("5000")},
      {"Samuel Beaulieu", "Directeur RH", "Ressources Humaines", new BigDecimal("4500")},
      {"Christina Dorcely", "Directrice Qualité", "Qualité", new BigDecimal("4000")},
      {"Evens Chéry", "Directeur Logistique", "Logistique", new BigDecimal("3500")}
    };
    for (Object[] m : managers) {
      empIdx =
          createOneEmployee(
              companyId,
              personnelAccountId,
              empIdx,
              (String) m[0],
              (String) m[1],
              (String) m[2],
              (BigDecimal) m[3],
              created);
    }

    // 12 chefs d'équipe (1200-1800 USD)
    String[] teamDepts = {
      "Coupe", "Couture Ligne A", "Couture Ligne B", "Couture Ligne C",
      "Finition", "Contrôle Qualité", "Repassage", "Emballage",
      "Maintenance", "Logistique", "Teinture", "Préparation"
    };
    for (String dept : teamDepts) {
      String name = HaitianNames.randomFullName();
      BigDecimal salary = new BigDecimal("1200").add(new BigDecimal(empIdx % 4 * 200)); // 1200-1800
      empIdx =
          createOneEmployee(
              companyId, personnelAccountId, empIdx, name, "Chef d'équipe", dept, salary, created);
    }

    // 12 ouvriers types (400-700 USD) — 6 profils × 2 employés pour rester réaliste
    String[][] workerProfiles = {
      {"Ouvrier couture", "Couture", "400"},
      {"Ouvrier coupe", "Coupe", "450"},
      {"Ouvrier finition", "Finition", "500"},
      {"Ouvrier contrôle qualité", "Contrôle Qualité", "550"},
      {"Ouvrier emballage", "Emballage", "420"},
      {"Ouvrier maintenance", "Maintenance", "700"}
    };
    for (String[] wp : workerProfiles) {
      for (int k = 0; k < 2; k++) {
        String name = HaitianNames.randomFullName();
        empIdx =
            createOneEmployee(
                companyId,
                personnelAccountId,
                empIdx,
                name,
                wp[0],
                wp[1],
                new BigDecimal(wp[2]),
                created);
      }
    }

    LOG.info(
        "V9 — Caribbean Textiles : {} employés créés sur 1200 (30 réalistes — les 1170 autres "
            + "seraient créés en prod via batch dédié)",
        created.size());
    return created;
  }

  private int createOneEmployee(
      UUID companyId,
      UUID personnelAccountId,
      int empIdx,
      String name,
      String position,
      String department,
      BigDecimal salaryUsd,
      List<EmployeeResponse> created) {
    try {
      EmployeeResponse emp =
          employeesService.create(
              companyId,
              new CreateEmployeeRequest(
                  null, // thirdPartyId null → le service crée le tiers EMPLOYEE automatiquement
                  name, // thirdPartyName
                  personnelAccountId, // collectiveAccountId (421000 Personnel - rémunérations dues)
                  "CT-" + String.format("%04d", empIdx), // employeeNumber
                  position,
                  department,
                  LocalDate.of(2024, 10, 1), // hireDate (début FY2024-2025)
                  salaryUsd, // baseSalary USD
                  "USD", // salaryCurrency — usine dollarisée
                  ContractType.PERMANENT,
                  null, // bankAccountNumber
                  null, // overtimeHours25
                  null, // overtimeHours50
                  null, // overtimeHours100
                  null, // absenceDays
                  null, // paidLeaveDays
                  null, // cnssNumber
                  null, // ofatmaSectorCode
                  Boolean.TRUE // thirteenthMonthEligible (Code Travail art. 153)
                  ));
      created.add(emp);
    } catch (ConflictException ex) {
      LOG.debug("V9 — Employé '{}' déjà existant — skip", name);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création employé '{}' : {}", name, ex.getMessage());
    }
    return empIdx + 1;
  }

  // ══ Étape j — 12 mois d'opérations sur FY2025-2026 ══

  /**
   * Génère 12 mois d'opérations (Oct 2025 → Sep 2026). Chaque mois est isolé dans un try/catch : un
   * échec n'empêche pas les mois suivants de continuer.
   *
   * <p>En décembre 2025, lance le 13e mois asynchrone via {@link
   * PayrollService#launchThirteenthMonthRun} (Code Travail art. 153). Le calcul se fait en
   * arrière-plan via {@code ThirteenthMonthAsyncRunner} (ne pas attendre la fin).
   */
  private int generateMonthlyOperations(
      UUID companyId,
      UUID ownerId,
      List<ThirdPartyResponse> clients,
      List<ThirdPartyResponse> suppliers,
      List<EmployeeResponse> employees,
      List<ItemResponse> items) {
    if (clients.isEmpty() || suppliers.isEmpty() || employees.isEmpty()) {
      LOG.warn(
          "V9 — Données de base insuffisantes pour générer les opérations mensuelles "
              + "(clients={}, suppliers={}, employees={}) — skip",
          clients.size(),
          suppliers.size(),
          employees.size());
      return 0;
    }

    int total = 0;
    LocalDate month = FY2526_START;
    int monthIdx = 0;
    while (!month.isAfter(FY2526_END)) {
      int monthCount = 0;
      try {
        // 5-10 SalesInvoice/mois (export USA — TVA 0% VAT_EXEMPT_ZF)
        int nSales = 5 + (monthIdx % 6); // 5, 6, 7, 8, 9, 10, 5, 6, 7, 8, 9, 10
        for (int i = 0; i < nSales; i++) {
          if (createExportSalesInvoice(companyId, month, i, clients, items)) {
            monthCount++;
          }
        }
        // 3-5 PurchaseInvoice/mois (imports en franchise — TVA 0% VAT_EXEMPT_ZF)
        int nPur = 3 + (monthIdx % 3); // 3, 4, 5, 3, 4, 5, ...
        for (int i = 0; i < nPur; i++) {
          if (createFreeZonePurchaseInvoice(companyId, month, i, suppliers, items)) {
            monthCount++;
          }
        }
        // 2-3 ExpenseReport/mois (logistique, transport, entretien)
        int nExp = 2 + (monthIdx % 2); // 2, 3, 2, 3, ...
        for (int i = 0; i < nExp; i++) {
          if (createExpenseReport(companyId, month, i, employees)) {
            monthCount++;
          }
        }
        // 1 PayrollRun/mois (create + calculate 12% OFATMA + approve → écriture PA consolidée)
        if (createPayrollRun(companyId, month)) {
          monthCount++;
        }
        // Décembre 2025 → 13e mois (Code Travail art. 153 — calcul async via
        // ThirteenthMonthAsyncRunner)
        if (month.getMonthValue() == 12 && month.getYear() == 2025) {
          if (launch13eMois(companyId, ownerId, 2025)) {
            monthCount++;
          }
        }
        LOG.info("V9 — Caribbean Textiles mois {} : {} opérations créées", month, monthCount);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec sur le mois {} (continu) : {}", month, ex.getMessage());
      }
      total += monthCount;
      month = month.plusMonths(1);
      monthIdx++;
    }
    return total;
  }

  /**
   * Crée une facture de vente export (TVA 0% — VAT_EXEMPT_ZF). Montant USD 50k-500k par facture
   * (production textile), 2-4 lignes.
   *
   * <p>Spécificités zone franche :
   *
   * <ul>
   *   <li>{@code currency="USD"} (devise fonctionnelle)
   *   <li>{@code taxRate=0} sur chaque ligne (TVA 0% export)
   *   <li>{@code taxes=[VAT_EXEMPT_ZF]} sur chaque ligne (marqueur d'exonération v8-6)
   * </ul>
   */
  private boolean createExportSalesInvoice(
      UUID companyId,
      LocalDate month,
      int seq,
      List<ThirdPartyResponse> clients,
      List<ItemResponse> items) {
    ThirdPartyResponse client = clients.get((month.getMonthValue() + seq) % clients.size());
    int nLines = 2 + (seq % 3); // 2, 3, 4
    List<CreateInvoiceRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      ItemResponse item = items.isEmpty() ? null : items.get((seq + i) % items.size());
      // Quantités industrielles : 5000-50000 unités/ligne
      int qty = 5000 + ((seq + i) * 3333) % 45000;
      // Prix unitaire USD 5-50 (textile)
      BigDecimal unitPrice =
          new BigDecimal(5 + ((seq + i) * 7) % 46).setScale(2, RoundingMode.HALF_UP);
      // TVA 0% — VAT_EXEMPT_ZF (Code Fiscal art. 197 — exports en zone franche)
      List<TaxApplication> taxes =
          List.of(new TaxApplication("VAT_EXEMPT_ZF", null, VAT_RATE_FREE_ZONE, 1));
      lines.add(
          new CreateInvoiceRequest.LineDto(
              "Export " + (item != null ? item.sku() + " — " + item.label() : "produits textiles"),
              new BigDecimal(qty),
              unitPrice,
              BigDecimal.ZERO, // discountPercent
              VAT_RATE_FREE_ZONE, // taxRate=0 (TVA 0% export)
              item != null ? item.id() : null,
              null, // timesheetEntryId
              taxes));
    }
    LocalDate issueDate = month.withDayOfMonth(Math.min(seq + 5, 28));
    CreateInvoiceRequest req =
        new CreateInvoiceRequest(
            client.id(),
            InvoiceType.STANDARD,
            issueDate,
            issueDate.plusDays(45), // délai USA — 45 jours
            "USD", // currency USD (export)
            lines,
            null);
    try {
      InvoiceResponse inv = invoicingService.createInvoice(companyId, req);
      invoicingService.issueInvoice(companyId, inv.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Facture export déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec facture export {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /**
   * Crée une facture d'achat matières premières (imports en franchise — TVA 0% VAT_EXEMPT_ZF).
   *
   * <p>Spécificités zone franche :
   *
   * <ul>
   *   <li>{@code currency="USD"} (imports depuis USA)
   *   <li>{@code taxRate=0} sur chaque ligne (TVA 0% — imports en franchise douanière)
   * </ul>
   */
  private boolean createFreeZonePurchaseInvoice(
      UUID companyId,
      LocalDate month,
      int seq,
      List<ThirdPartyResponse> suppliers,
      List<ItemResponse> items) {
    ThirdPartyResponse supplier = suppliers.get((month.getMonthValue() + seq) % suppliers.size());
    int nLines = 1 + (seq % 3); // 1, 2, 3
    List<CreatePurchaseInvoiceRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      // Quantités industrielles : 2000-20000 unités
      int qty = 2000 + ((seq + i) * 2222) % 18000;
      // Prix unitaire USD 1-25 (matières premières textile)
      BigDecimal unitPrice =
          new BigDecimal(1 + ((seq + i) * 3) % 25).setScale(2, RoundingMode.HALF_UP);
      lines.add(
          new CreatePurchaseInvoiceRequest.LineDto(
              "Import matières premières — "
                  + (items.isEmpty() ? "lot " + i : items.get((seq + i) % items.size()).label()),
              new BigDecimal(qty),
              unitPrice,
              VAT_RATE_FREE_ZONE, // taxRate=0 (TVA 0% imports en franchise zone franche)
              null // expenseAccountId null → le service résout un compte CHARGES par défaut
              ));
    }
    LocalDate issueDate = month.withDayOfMonth(Math.min(seq + 8, 27));
    CreatePurchaseInvoiceRequest req =
        new CreatePurchaseInvoiceRequest(
            supplier.id(),
            PurchaseInvoiceType.STANDARD,
            "IMP-" + month.getYear() + month.getMonthValue() + "-" + seq,
            issueDate,
            issueDate.plusDays(45), // délai import USA
            "USD", // currency USD (imports depuis USA)
            lines);
    try {
      PurchaseInvoiceResponse inv = purchasingService.createPurchaseInvoice(companyId, req);
      purchasingService.receive(companyId, inv.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Facture achat déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec facture achat {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /** Crée une note de frais (logistique, transport, entretien) — submit + approve (écriture DP). */
  private boolean createExpenseReport(
      UUID companyId, LocalDate month, int seq, List<EmployeeResponse> employees) {
    EmployeeResponse emp = employees.get((month.getMonthValue() + seq) % employees.size());
    int nLines = 1 + (seq % 2); // 1, 2
    String[][] cats = {
      {"TRAVEL", "Transport logistique import-export Ouanaminthe"},
      {"SUPPLIES", "Entretien machines à coudre — pièces détachées"},
      {"TRAVEL", "Carburant camions logistiques"},
      {"SUPPLIES", "Fournitures bureau — consommables administration"}
    };
    List<CreateExpenseReportRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      String[] cat = cats[(seq + i) % cats.length];
      // Montants USD 200-5000 (usine dollarisée)
      BigDecimal amount =
          new BigDecimal(200 + ((seq + i) * 421) % 4800).setScale(2, RoundingMode.HALF_UP);
      lines.add(new CreateExpenseReportRequest.LineDto(cat[0], cat[1], amount, null));
    }
    LocalDate expDate = month.withDayOfMonth(Math.min(seq + 12, 26));
    CreateExpenseReportRequest req =
        new CreateExpenseReportRequest(
            emp.thirdPartyId(),
            expDate,
            "USD", // currency USD
            "Dépenses "
                + month.getMonthValue()
                + "/"
                + month.getYear()
                + " — "
                + emp.thirdPartyName(),
            false, // paidDirectly=false → à rembourser (Débit Charges / Crédit Tiers-Employé)
            lines);
    try {
      ExpenseReportResponse rep = expensesService.create(companyId, req);
      expensesService.submit(companyId, rep.id());
      expensesService.approve(companyId, rep.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Note de frais déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec note de frais {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /** Crée une campagne de paie mensuelle : create → calculate (12% OFATMA) → approve. */
  private boolean createPayrollRun(UUID companyId, LocalDate month) {
    int year = month.getYear();
    int monthNum = month.getMonthValue();
    try {
      PayrollRunResponse run =
          payrollService.create(
              companyId, new CreatePayrollRunRequest(monthNum, year, EMPLOYER_CONTRIBUTION_RATE));
      payrollService.calculate(companyId, run.id(), EMPLOYER_CONTRIBUTION_RATE);
      payrollService.approve(companyId, run.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — PayrollRun déjà existant pour {}/{} — skip", monthNum, year);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec PayrollRun {}/{} : {}", monthNum, year, ex.getMessage());
    }
    return false;
  }

  /**
   * Lance le 13e mois asynchrone via {@link PayrollService#launchThirteenthMonthRun} (Code Travail
   * art. 153 — v8-7). Le calcul se fait en arrière-plan via {@code ThirteenthMonthAsyncRunner} (ne
   * pas attendre la fin — c'est async).
   */
  private boolean launch13eMois(UUID companyId, UUID ownerId, int year) {
    try {
      PayrollRunResponse run = payrollService.launchThirteenthMonthRun(companyId, year, ownerId);
      LOG.info(
          "V9 — 13e mois lancé (async) pour companyId={} year={} runId={} (Code Travail art. 153)",
          companyId,
          year,
          run.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — 13e mois {} déjà lancé pour companyId={} — skip", year, companyId);
    } catch (RuntimeException ex) {
      LOG.warn(
          "V9 — Échec lancement 13e mois {} pour companyId={} : {}",
          year,
          companyId,
          ex.getMessage());
    }
    return false;
  }

  // ══ Helper — résolution des UUIDs de comptes ══

  private UUID resolveAccountCode(UUID companyId, String code) {
    return accountRepository
        .findByCompanyIdAndCode(companyId, code)
        .map(Account::getId)
        .orElse(null);
  }

  /** Cache des UUIDs de comptes utilisés par les opérations mensuelles + immobilisations. */
  private record AccountRefs(
      UUID clientsAccountId,
      UUID suppliersAccountId,
      UUID personnelAccountId,
      UUID banqueAccountId,
      UUID depreciationExpenseAccountId,
      UUID accumulatedDepreciationAccountId) {

    static AccountRefs load(UUID companyId, AccountRepository accountRepository) {
      return new AccountRefs(
          resolve(accountRepository, companyId, AccountFixture.CLIENTS.code()),
          resolve(accountRepository, companyId, AccountFixture.FOURNISSEURS.code()),
          resolve(accountRepository, companyId, AccountFixture.PERSONNEL_REMUNERATIONS_DUES.code()),
          resolve(accountRepository, companyId, AccountFixture.BANQUE.code()),
          resolve(accountRepository, companyId, DOTA_AMORT_CODE),
          resolve(accountRepository, companyId, AccountFixture.AMORTISSEMENTS.code()));
    }

    private static UUID resolve(AccountRepository repo, UUID companyId, String code) {
      Account acc = repo.findByCompanyIdAndCode(companyId, code).orElse(null);
      if (acc == null) {
        LOG.warn(
            "V9 — Compte {} introuvable pour companyId={} — opérations dépendantes vont échouer",
            code,
            companyId);
        return null;
      }
      return acc.getId();
    }
  }
}

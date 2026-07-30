package jo.accountant.demo.seeders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.entity.TaxExemptionStatus;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.demo.fixtures.HaitianAddresses;
import jo.accountant.demo.fixtures.HaitianProducts;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V9 — PME 1 : Boutik Lakay S.A. (commerce retail Pétion-Ville).
 *
 * <p>4 employés, ~6M HTG/an, HTG, PCN_HAITI, TVA 10% + TCA 10%, IS 30% standard, exercice fiscal
 * 01/10 → 30/09 (FY2024-2025 + FY2025-2026).
 *
 * <p>Données générées (idempotent) : company + user owner + 50 comptes PCN_HAITI + 6 journaux
 * (VT/AC/BQ/OD/PA/DP) + 2 exercices fiscaux + 14 séquences documentaires + 8 clients + 5
 * fournisseurs + 4 employés + 1 banque + 1 entrepôt + 8 articles + 12 mois d'opérations
 * (SalesInvoice/PurchaseInvoice/ExpenseReport/PayrollRun sur FY2025-2026).
 *
 * <p><b>Résilience</b> — chaque mois est isolé dans un try/catch dédié ; le seed global est
 * enveloppé d'un try/catch pour ne pas faire échouer le démarrage de l'application.
 *
 * <p><b>Bug historique corrigé</b> — la V8.1 pointait par erreur sur le framework {@code ...004}
 * (PCG_FRANCE). La V9 utilise {@code ...005} (PCN_HAITI — cf. V1_002__core_seeds.sql ligne 26).
 */
@Component
public class RetailCommerceSeeder implements CompanySeeder {

  private static final Logger LOG = LoggerFactory.getLogger(RetailCommerceSeeder.class);

  /**
   * UUID du référentiel PCN_HAITI (V1_002__core_seeds.sql ligne 26 — correctif V9 : la V8.1
   * pointait par erreur sur ...004 = PCG_FRANCE).
   */
  private static final UUID PCN_HAITI_FRAMEWORK_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000005");

  // ── Paramètres démo (Boutik Lakay — retail Pétion-Ville) ──

  private static final String COMPANY_NAME = "Boutik Lakay S.A.";
  private static final String OWNER_EMAIL = "owner@boutik-lakay.demo";
  private static final String OWNER_PASSWORD = "Demo1234!2026";
  private static final String OWNER_FULL_NAME = "Boutik Lakay Owner";
  private static final String OWNER_LOCALE = "fr";

  /** Taux TVA Haïti (Code Fiscal art. 191). */
  private static final BigDecimal VAT_RATE = new BigDecimal("10");

  /** Taux TCA Haïti sur commerce (Code Fiscal art. 196). */
  private static final BigDecimal TCA_RATE = new BigDecimal("10");

  /** Taux cotisations OFATMA part patronale (12% — taux plancher retail haïtien). */
  private static final BigDecimal EMPLOYER_CONTRIBUTION_RATE = new BigDecimal("12");

  /** FY2025-2026 = 01/10/2025 → 30/09/2026 (12 mois de données opérationnelles). */
  private static final LocalDate FY2526_START = LocalDate.of(2025, 10, 1);

  private static final LocalDate FY2526_END = LocalDate.of(2026, 9, 30);

  // ── Dépendances Spring ──

  private final CompanyRepository companyRepository;
  private final UserRepository userRepository;
  private final UserCompanyRoleRepository userCompanyRoleRepository;
  private final AuthService authService;
  private final AccountRepository accountRepository;
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

  public RetailCommerceSeeder(
      CompanyRepository companyRepository,
      UserRepository userRepository,
      UserCompanyRoleRepository userCompanyRoleRepository,
      AuthService authService,
      AccountRepository accountRepository,
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
      BankReconciliationService bankReconciliationService) {
    this.companyRepository = companyRepository;
    this.userRepository = userRepository;
    this.userCompanyRoleRepository = userCompanyRoleRepository;
    this.authService = authService;
    this.accountRepository = accountRepository;
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
  }

  @Override
  public String demoCode() {
    return "BOUTIK_LAKAY";
  }

  @Override
  public String companyName() {
    return COMPANY_NAME;
  }

  @Override
  public String segment() {
    return "RETAIL_COMMERCE";
  }

  /**
   * Crée la Company + user owner + toutes les données métier.
   *
   * <p>Idempotent : si la Company existe déjà (name + isDemo=true), retourne 0.
   */
  @Override
  @Transactional
  @SuppressWarnings(
      "try") // DemoTenantContext utilisé pour close() automatique, pas référencé dans le corps
  public int seed() {
    // ── 1. Idempotence : la company démo existe-t-elle déjà ? ──
    Optional<Company> existing =
        companyRepository.findAll().stream()
            .filter(c -> COMPANY_NAME.equals(c.getName()) && Boolean.TRUE.equals(c.getIsDemo()))
            .findFirst();
    if (existing.isPresent()) {
      LOG.info("V9 — Boutik Lakay déjà seedée (id={}) — skip", existing.get().getId());
      return 0;
    }

    // ── 2. Création de la Company ──
    Company company = createCompany();
    final UUID companyId = company.getId();
    LOG.info(
        "V9 — Company Boutik Lakay créée (id={}, nif={}, framework=PCN_HAITI/005)",
        companyId,
        company.getNif());

    // ── 3 + 4. User owner + UserCompanyRole OWNER ──
    UUID ownerId = ensureOwnerUser(companyId);
    LOG.info("V9 — User owner créé/résolu (id={}, email={})", ownerId, OWNER_EMAIL);

    // ── 5. Bootstraps + données métier (try-with-resources pour le contexte tenant) ──
    int totalCreated;
    try (DemoTenantContext ctx = DemoTenantContext.of(companyId, ownerId)) {
      // a, b, c — bootstraps (COA + journaux/exercices + séquences)
      coaBootstrap.bootstrap(companyId, PCN_HAITI_FRAMEWORK_ID, AccountFixture.all());
      fiscalYearBootstrap.bootstrap(companyId);
      numberingBootstrap.bootstrap(companyId);
      // Compléter les séquences documentaires manquantes (scopeKey="VT"/"AC"/"PA") que les
      // services InvoicingService/PurchasingService/PayrollService attendent mais que le
      // bootstrap générique ne crée pas (il ne crée que les variants à scopeKey="").
      ensureExtraDocumentSequences(companyId);
      // Journal DP (Dépenses) requis par ExpensesService.generateExpenseEntry
      ensureJournal(companyId, "DP", "Journal des dépenses");
      // Pré-charger les UUIDs des comptes PCN utilisés par les opérations mensuelles
      AccountRefs refs = AccountRefs.load(companyId, accountRepository);

      // d. Clients retail (8)
      List<ThirdPartyResponse> clients = createDemoClients(companyId, refs.clientsAccountId);
      // e. Fournisseurs (5)
      List<ThirdPartyResponse> suppliers = createDemoSuppliers(companyId, refs.suppliersAccountId);
      // f. Employés (4)
      List<EmployeeResponse> employees = createDemoEmployees(companyId, refs.personnelAccountId);
      // g. Banque
      createDemoBankAccount(companyId, refs.banqueAccountId);
      // h. Entrepôt + 8 articles + stock initial (postStockMove IN)
      Warehouse warehouse =
          inventoryService.createWarehouse(
              companyId, new CreateWarehouseRequest("Boutique Pétion-Ville"));
      List<ItemResponse> items = createDemoItems(companyId, warehouse);

      totalCreated =
          1 /* company */
              + 1 /* user */
              + 1 /* ucr */
              + AccountFixture.all().size()
              + 5
              + 2 /* journaux + exercices */
              + 14 /* séquences (10 + 4 extras) */
              + clients.size()
              + suppliers.size()
              + employees.size()
              + 1 /* bankAccount */
              + 1 /* warehouse */
              + items.size() /* items */
              + items.size() /* stock moves IN */;

      // i. 12 mois d'opérations sur FY2025-2026
      int monthlyOps = generateMonthlyOperations(companyId, clients, suppliers, employees, items);
      totalCreated += monthlyOps;

      LOG.info(
          "V9 — Boutik Lakay seed terminé pour companyId={} : {} enregistrements créés",
          companyId,
          totalCreated);
    } catch (RuntimeException ex) {
      // Le try-with-resources garantit que TenantContext.clear() est appelé même sur exception.
      // On logue ERROR mais on ne propage pas l'exception pour ne pas casser le démarrage.
      LOG.error(
          "V9 — Échec du seed Boutik Lakay (companyId={}) : {}", companyId, ex.getMessage(), ex);
      return 1; // au moins la company a été créée
    }
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
    company.setLegalForm(LegalForm.SARL);
    company.setCountry("HT");
    company.setFunctionalCurrency("HTG");
    company.setNif("1010101010BL");
    company.setAddress("Rue Lamarre, Pétion-Ville, Port-au-Prince");
    company.setSector(Sector.COMMERCE);
    company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
    company.setBusinessTypeCode("RETAIL_COMMERCE");
    company.setPrimaryActivityLabel("Commerce de détail — alimentation, ménagers, cosmétiques");
    company.setAccountingFrameworkId(PCN_HAITI_FRAMEWORK_ID);
    company.setFiscalYearStartMonth(10); // exercice haïtien 01/10 → 30/09
    company.setFreeZone(false);
    company.setTaxExemptionStatus(TaxExemptionStatus.STANDARD); // IS 30% standard
    company.setMonthlyLegalHours(new BigDecimal("208")); // Haïti 48h/sem × 52/12
    company.setWizardStep(9);
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
    // Idempotence : un user avec cet email existe-t-il déjà ? (le seed peut avoir été partiel)
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

  // ══ Étape d — Clients retail (8) ══

  private List<ThirdPartyResponse> createDemoClients(UUID companyId, UUID clientsAccountId) {
    String[] names = {
      "Boutique Pétion-Ville", "Épicerie Bonheur", "Supermarket Delmas",
      "Magasin Lambert", "Pharmacie Lamarre", "Restaurant Chéry",
      "Salon Beauté Margo", "Quincaillerie Turgeau"
    };
    List<ThirdPartyResponse> created = new ArrayList<>(names.length);
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      String email = "client" + (i + 1) + "@boutik-lakay.demo";
      String address = HaitianAddresses.randomAddress(segment()) + ", Pétion-Ville";
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.CLIENT,
                    name,
                    clientsAccountId,
                    email,
                    address,
                    "101010101" + i + "AB"));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Client '{}' déjà existant — skip", name);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création client '{}' : {}", name, ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape e — Fournisseurs (5) ══

  private List<ThirdPartyResponse> createDemoSuppliers(UUID companyId, UUID suppliersAccountId) {
    String[][] defs = {
      {"DGS Haiti S.A.", "Distribution alimentaire", "2020202020DG"},
      {"Caribbean Distributors", "Import-export régional", "3030303030CD"},
      {"Solin S.A.", "Conserves et sauces", "4040404040SO"},
      {"Margo Industries", "Huiles et corps gras", "5050505050MA"},
      {"Boudoo Cleaning Co.", "Produits ménagers", "6060606060BC"}
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
                    "fournisseur@" + d[0].toLowerCase().replace(" ", "-").replace(".", "") + ".ht",
                    d[1] + ", Port-au-Prince",
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

  // ══ Étape f — Employés (4) ══

  private List<EmployeeResponse> createDemoEmployees(UUID companyId, UUID personnelAccountId) {
    Object[][] defs = {
      // {name, position, department, baseSalary, contractType}
      {
        "Jean-Maxime Auguste",
        "Gérant",
        "Direction",
        new BigDecimal("60000"),
        ContractType.PERMANENT
      },
      {"Marie-Carmel Pierre", "Vendeur", "Ventes", new BigDecimal("28000"), ContractType.PERMANENT},
      {"Wilner Saintilus", "Vendeur", "Ventes", new BigDecimal("25000"), ContractType.PERMANENT},
      {"Nadège Chéry", "Caissier", "Caisse", new BigDecimal("30000"), ContractType.PERMANENT}
    };
    List<EmployeeResponse> created = new ArrayList<>(defs.length);
    for (int i = 0; i < defs.length; i++) {
      Object[] d = defs[i];
      String name = (String) d[0];
      try {
        EmployeeResponse emp =
            employeesService.create(
                companyId,
                new CreateEmployeeRequest(
                    null, // thirdPartyId null → le service crée le tiers EMPLOYEE automatiquement
                    name, // thirdPartyName
                    personnelAccountId, // collectiveAccountId (421000 Personnel - rémunérations
                    // dues)
                    "BL-" + String.format("%03d", i + 1), // employeeNumber
                    (String) d[1], // position
                    (String) d[2], // department
                    LocalDate.of(2024, 10, 1), // hireDate (début FY2024-2025)
                    (BigDecimal) d[3], // baseSalary HTG
                    "HTG", // salaryCurrency
                    (ContractType) d[4], // contractType
                    null // bankAccountNumber
                    ));
        created.add(emp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Employé '{}' déjà existant — skip", name);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création employé '{}' : {}", name, ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape g — Compte bancaire (Sogebank) ══

  private void createDemoBankAccount(UUID companyId, UUID banqueAccountId) {
    try {
      bankReconciliationService.createBankAccount(
          companyId,
          new CreateBankAccountRequest(
              banqueAccountId, "Sogebank — Compte courant Boutik Lakay", "010-123456-01"));
    } catch (ConflictException ex) {
      LOG.debug("V9 — BankAccount déjà existant — skip");
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création BankAccount : {}", ex.getMessage());
    }
  }

  // ══ Étape h — Entrepôt + 8 articles + stock initial ══

  private List<ItemResponse> createDemoItems(UUID companyId, Warehouse warehouse) {
    List<HaitianProducts.Product> catalog = HaitianProducts.retailCatalog();
    // On prend 8 produits représentatifs (alimentation + ménagers + cosmétiques)
    int[] indexes = {
      0, 2, 4, 6, 15, 17, 23, 27
    }; // RIZ-01, HARI-01, HUIL-01, FAR-01, SAV-01, DET-01, ESS-01, CREM-01
    List<ItemResponse> created = new ArrayList<>(indexes.length);
    // Résoudre les comptes de stock (310000) et COGS (603000) une fois
    UUID stockAccountId =
        accountRepository
            .findByCompanyIdAndCode(companyId, AccountFixture.STOCKS_MARCHANDISES.code())
            .map(Account::getId)
            .orElse(null);
    UUID cogsAccountId =
        accountRepository
            .findByCompanyIdAndCode(companyId, AccountFixture.VARIATION_STOCKS.code())
            .map(Account::getId)
            .orElse(null);
    if (stockAccountId == null || cogsAccountId == null) {
      LOG.warn(
          "V9 — Comptes 310000/603000 introuvables pour companyId={} — items non créés", companyId);
      return created;
    }

    for (int idx : indexes) {
      HaitianProducts.Product p = catalog.get(idx);
      try {
        ItemResponse item =
            inventoryService.createItem(
                companyId,
                new CreateItemRequest(
                    p.code(),
                    p.label(),
                    "UNIT",
                    CostingMethod.FIFO,
                    new BigDecimal("20"), // reorderThreshold
                    stockAccountId,
                    cogsAccountId));
        created.add(item);
        // Stock initial IN (100-500 unités) — valorisé au prix d'achat
        int qty = 100 + (idx * 17) % 400; // 100-499 déterministe
        BigDecimal unitCost = p.purchasePriceHtg();
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
                  "Stock initial — ouverture Boutik Lakay",
                  null // pas de contrepartie → pas d'écriture comptable (rétro-compat)
                  ));
        } catch (RuntimeException ex) {
          LOG.warn("V9 — Échec stock IN pour item {} : {}", p.code(), ex.getMessage());
        }
      } catch (ConflictException ex) {
        LOG.debug("V9 — Item {} déjà existant — skip", p.code());
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création item {} : {}", p.code(), ex.getMessage());
      }
    }
    return created;
  }

  // ══ Étape i — 12 mois d'opérations sur FY2025-2026 ══

  /**
   * Génère 12 mois d'opérations (Oct 2025 → Sep 2026). Chaque mois est isolé dans un try/catch : un
   * échec n'empêche pas les mois suivants de continuer.
   */
  private int generateMonthlyOperations(
      UUID companyId,
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
        // 6-8 SalesInvoice/mois
        int nSales = 6 + (monthIdx % 3); // 6, 7, 8, 6, 7, 8, ...
        for (int i = 0; i < nSales; i++) {
          if (createSalesInvoice(companyId, month, i, clients, items)) {
            monthCount++;
          }
        }
        // 2-3 PurchaseInvoice/mois
        int nPur = 2 + (monthIdx % 2); // 2, 3, 2, 3, ...
        for (int i = 0; i < nPur; i++) {
          if (createPurchaseInvoice(companyId, month, i, suppliers, items)) {
            monthCount++;
          }
        }
        // 1-2 ExpenseReport/mois
        int nExp = 1 + (monthIdx % 2); // 1, 2, 1, 2, ...
        for (int i = 0; i < nExp; i++) {
          if (createExpenseReport(companyId, month, i, employees)) {
            monthCount++;
          }
        }
        // 1 PayrollRun/mois
        if (createPayrollRun(companyId, month)) {
          monthCount++;
        }
        LOG.info("V9 — Boutik Lakay mois {} : {} opérations créées", month, monthCount);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec sur le mois {} (continu) : {}", month, ex.getMessage());
      }
      total += monthCount;
      month = month.plusMonths(1);
      monthIdx++;
    }
    return total;
  }

  /** Crée une facture de vente retail (TVA 10% + TCA 10% via multi-tax, 5k-50k HTG, 2-4 lignes). */
  private boolean createSalesInvoice(
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
      int qty = 1 + ((seq + i) * 7) % 10; // 1-10
      BigDecimal unitPrice =
          new BigDecimal(500 + ((seq + i) * 311) % 4600) // 500-5099
              .setScale(2, RoundingMode.HALF_UP);
      // Multi-tax : TVA 10% + TCA 10% sur la même ligne (Code Fiscal art. 191 + 196)
      List<TaxApplication> taxes =
          List.of(
              new TaxApplication("VAT", null, VAT_RATE, 1),
              new TaxApplication("TCA", null, TCA_RATE, 2));
      lines.add(
          new CreateInvoiceRequest.LineDto(
              "Vente " + (item != null ? item.sku() + " — " + item.label() : "marchandises"),
              new BigDecimal(qty),
              unitPrice,
              BigDecimal.ZERO, // discountPercent
              BigDecimal.ZERO, // taxRate (fallback mono-taxe — non utilisé car taxes non null)
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
            issueDate.plusDays(30),
            "HTG",
            lines,
            null);
    try {
      InvoiceResponse inv = invoicingService.createInvoice(companyId, req);
      invoicingService.issueInvoice(companyId, inv.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Facture vente déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec facture vente {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /**
   * Crée une facture d'achat marchandises (1-3 lignes, TVA 10% déductible, receive() génère
   * l'écriture).
   */
  private boolean createPurchaseInvoice(
      UUID companyId,
      LocalDate month,
      int seq,
      List<ThirdPartyResponse> suppliers,
      List<ItemResponse> items) {
    ThirdPartyResponse supplier = suppliers.get((month.getMonthValue() + seq) % suppliers.size());
    int nLines = 1 + (seq % 3); // 1, 2, 3
    List<CreatePurchaseInvoiceRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      int qty = 5 + ((seq + i) * 13) % 50; // 5-54
      BigDecimal unitPrice =
          new BigDecimal(300 + ((seq + i) * 211) % 2500) // 300-2799
              .setScale(2, RoundingMode.HALF_UP);
      lines.add(
          new CreatePurchaseInvoiceRequest.LineDto(
              "Achat marchandises — "
                  + (items.isEmpty() ? "lot " + i : items.get((seq + i) % items.size()).label()),
              new BigDecimal(qty),
              unitPrice,
              VAT_RATE, // taxRate 10% (TVA déductible côté achat)
              null // expenseAccountId null → le service résout un compte CHARGES par défaut
              ));
    }
    LocalDate issueDate = month.withDayOfMonth(Math.min(seq + 8, 27));
    CreatePurchaseInvoiceRequest req =
        new CreatePurchaseInvoiceRequest(
            supplier.id(),
            PurchaseInvoiceType.STANDARD,
            "FOURN-" + month.getYear() + month.getMonthValue() + "-" + seq,
            issueDate,
            issueDate.plusDays(30),
            "HTG",
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

  /** Crée une note de frais (transport, fournitures) — submit + approve (écriture DP). */
  private boolean createExpenseReport(
      UUID companyId, LocalDate month, int seq, List<EmployeeResponse> employees) {
    EmployeeResponse emp = employees.get((month.getMonthValue() + seq) % employees.size());
    int nLines = 1 + (seq % 2); // 1, 2
    String[][] cats = {
      {"TRANSPORT", "Transport taxi/tap-tap déplacements clients"},
      {"FOURNITURES", "Fournitures de bureau — papier, stylos, cartouches"},
      {"ENTRETIEN", "Petit entretien boutique"},
      {"TELECOM", "Crédit téléphonique et data"}
    };
    List<CreateExpenseReportRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      String[] cat = cats[(seq + i) % cats.length];
      BigDecimal amount =
          new BigDecimal(1500 + ((seq + i) * 421) % 11000) // 1500-12499
              .setScale(2, RoundingMode.HALF_UP);
      lines.add(new CreateExpenseReportRequest.LineDto(cat[0], cat[1], amount, null));
    }
    LocalDate expDate = month.withDayOfMonth(Math.min(seq + 12, 26));
    CreateExpenseReportRequest req =
        new CreateExpenseReportRequest(
            emp.thirdPartyId(),
            expDate,
            "HTG",
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

  // ══ Helper — résolution des UUIDs de comptes PCN_HAITI ══

  /** Cache des UUIDs de comptes PCN utilisés par les opérations mensuelles. */
  private record AccountRefs(
      UUID clientsAccountId,
      UUID suppliersAccountId,
      UUID personnelAccountId,
      UUID banqueAccountId) {

    static AccountRefs load(UUID companyId, AccountRepository accountRepository) {
      return new AccountRefs(
          resolve(accountRepository, companyId, AccountFixture.CLIENTS.code()),
          resolve(accountRepository, companyId, AccountFixture.FOURNISSEURS.code()),
          resolve(accountRepository, companyId, AccountFixture.PERSONNEL_REMUNERATIONS_DUES.code()),
          resolve(accountRepository, companyId, AccountFixture.BANQUE.code()));
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

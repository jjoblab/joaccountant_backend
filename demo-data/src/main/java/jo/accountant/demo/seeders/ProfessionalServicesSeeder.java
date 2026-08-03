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
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.TaxApplication;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.service.InvoicingService;
import jo.accountant.payroll.dto.CreatePayrollRunRequest;
import jo.accountant.payroll.dto.PayrollRunResponse;
import jo.accountant.payroll.service.PayrollService;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import jo.accountant.timebilling.dto.CreateBillableRateRequest;
import jo.accountant.timebilling.dto.CreateProjectRequest;
import jo.accountant.timebilling.dto.CreateTimesheetEntryRequest;
import jo.accountant.timebilling.dto.ProjectResponse;
import jo.accountant.timebilling.dto.TimesheetEntryResponse;
import jo.accountant.timebilling.dto.UnbilledWip;
import jo.accountant.timebilling.entity.BillingType;
import jo.accountant.timebilling.service.TimeBillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V9 — PME 2 : Moïse &amp; Associés S.A. (cabinet de conseil/services pro Port-au-Prince).
 *
 * <p>8 consultants, ~18M HTG/an, HTG, PCN_HAITI, IS 30% standard, TVA 10% + TCA 10% cumulatives, RS
 * 2% retenue par clients pro (Code Fiscal art. 156-1), acompte IS 1% mensuel, exercice fiscal 01/10
 * → 30/09 (FY2024-2025 + FY2025-2026).
 *
 * <p><b>Spécificité time-billing</b> — ce cabinet facture au temps passé (TIME_AND_MATERIALS) :
 *
 * <ul>
 * <li>6 projets de conseil rattachés à des clients pro (banques, opérateurs télécom, etc.)
 * <li>8 BillableRates (6 par projet + 1 par ressource + 1 défaut entreprise) — taux 3500-8000
 * HTG/h selon séniorité du consultant et complexité du projet
 * <li>Timesheets : 8-15 entrées/mois/consultant (heures 4-8h), approuvées par un manager distinct
 * (v7-9 — règle anti-auto-approbation, vérification des quatre yeux)
 * <li>Facturation au temps passé : récupération du WIP non facturé via {@code
 * TimeBillingService.getUnbilled(projectId)} → lignes de facture référençant les {@code
 * timesheetEntryId} → {@code issueInvoice} marque les entrées comme {@code invoiced}
 * </ul>
 *
 * <p>Données générées (idempotent) : company + 2 users (owner consultant + manager approbateur) +
 * 50 comptes PCN_HAITI + 6 journaux (VT/AC/BQ/OD/PA/DP) + 2 exercices fiscaux + 14 séquences
 * documentaires + 12 clients pro + 4 fournisseurs + 8 employés + 1 banque + 6 projets + 8 taux
 * facturables + 12 mois d'opérations (TimesheetEntry + Invoice + Invoice +
 * ExpenseReport + PayrollRun sur FY2025-2026).
 *
 * <p><b>Résilience</b> — chaque mois est isolé dans un try/catch dédié ; le seed global est
 * enveloppé d'un try/catch pour ne pas faire échouer le démarrage de l'application.
 *
 * <p><b>Bug historique corrigé</b> — la version précédente pointait par erreur sur le framework {@code ...004}
 * (PCG_FRANCE). La V9 utilise {@code ...005} (PCN_HAITI — cf. V3__core_seeds.sql ligne 26).
 
 *
 * @author jo@Dev


*/
@Component
public class ProfessionalServicesSeeder implements CompanySeeder {

  private static final Logger LOG = LoggerFactory.getLogger(ProfessionalServicesSeeder.class);

  /**
   * UUID du référentiel PCN_HAITI (V3__core_seeds.sql ligne 26 — correctif V9 : la version précédente
   * pointait par erreur sur ...004 = PCG_FRANCE).
   */
  private static final UUID PCN_HAITI_FRAMEWORK_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000005");

  // ── Paramètres démo (Moïse & Associés — cabinet Port-au-Prince) ──

  private static final String COMPANY_NAME = "Moïse & Associés Conseil S.A.";
  private static final String OWNER_EMAIL = "owner@moise-associes.demo";
  private static final String OWNER_PASSWORD = "Demo1234!2026";
  private static final String OWNER_FULL_NAME = "Maître Moïse Auguste";
  private static final String OWNER_LOCALE = "fr";

  /** Email du manager/approbateur (v7-9 — doit être distinct du owner pour approveEntry). */
  private static final String MANAGER_EMAIL = "manager@moise-associes.demo";

  private static final String MANAGER_PASSWORD = "Demo1234!2026";
  private static final String MANAGER_FULL_NAME = "Carlo Pierre-Louis";
  private static final String MANAGER_LOCALE = "fr";

  /** Taux TVA Haïti (Code Fiscal art. 191). */
  private static final BigDecimal VAT_RATE = new BigDecimal("10");

  /** Taux TCA Haïti sur services (Code Fiscal art. 196). */
  private static final BigDecimal TCA_RATE = new BigDecimal("10");

  /** Taux RS (Retenue à la source) sur prestations locales — 2% (Code Fiscal art. 156-1). */
  private static final BigDecimal RS_RATE = new BigDecimal("2");

  /** Taux cotisations OFATMA part patronale (12% — taux cabinet de services haïtien). */
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
  private final ExpensesService expensesService;
  private final PayrollService payrollService;
  private final BankReconciliationService bankReconciliationService;
  private final TimeBillingService timeBillingService;

  /**
   * rls-proper-fix — Self-injection via le proxy Spring.
   *
   * <p>Permet d'appeler {@link #seedBusinessData(UUID, UUID, UUID)} depuis {@link #seed()} en
   * traversant le proxy CGLIB → l'annotation {@code @Transactional} sur {@code seedBusinessData}
   * sera effectivement appliquée (une méthode appelée directement via {@code this.} ne passe pas
   * par le proxy → pas de transaction → pas de {@code SET LOCAL app.current_tenant}).
   *
   * <p>{@code @Lazy} évite une dépendance circulaire à l'initialisation (le bean s'injecte
   * lui-même avant la fin de sa propre construction).
   */
  @Autowired
  @Lazy
  private ProfessionalServicesSeeder self;

  public ProfessionalServicesSeeder(
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
      ExpensesService expensesService,
      PayrollService payrollService,
      BankReconciliationService bankReconciliationService,
      TimeBillingService timeBillingService) {
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
    this.expensesService = expensesService;
    this.payrollService = payrollService;
    this.bankReconciliationService = bankReconciliationService;
    this.timeBillingService = timeBillingService;
  }

  @Override
  public String demoCode() {
    return "MOISE_ASSOCIES";
  }

  @Override
  public String companyName() {
    return COMPANY_NAME;
  }

  @Override
  public String segment() {
    return "PROFESSIONAL_SERVICES";
  }

  /**
   * Crée la Company + users (owner consultant + manager approbateur) + toutes les données métier.
   *
   * <p>Idempotent : si la Company existe déjà (name + isDemo=true), retourne 0.
   *
   * <p>La méthode n'est PLUS {@code @Transactional}. La Company +
   * les users (owner + manager) sont créés via les méthodes {@code @Transactional} par défaut des
   * repositories Spring Data JPA (chaque {@code save()} est sa propre transaction sur des tables
   * non RLS-protégées : {@code companies}, {@code users}, {@code user_company_role}). Les données
   * métier (RLS-protégées) sont créées via {@link #seedBusinessData(UUID, UUID, UUID)}, appelée à
   * travers le proxy Spring self-injecté — la transaction s'ouvre APRÈS que
   * {@code DemoTenantContext.of()} ait positionné le ThreadLocal, et le
   * {@code TenantRlsConnectionCustomizer} intercepte le {@code setAutoCommit(false)} pour
   * appliquer {@code SET LOCAL app.current_tenant = companyId} au bon moment. Les INSERT sur
   * {@code journal_entry}/{@code third_party}/{@code sales_invoice}/{@code purchase_invoice}/
   * {@code expense_report}/{@code journal_line} passent alors la policy RLS.
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
      LOG.info("V9 — Moïse & Associés déjà seedée (id={}) — skip", existing.get().getId());
      return 0;
    }

    // ── 2. Création de la Company ── (hors DemoTenantContext — table companies non RLS-protégée)
    Company company = createCompany();
    final UUID companyId = company.getId();
    LOG.info(
        "V9 — Company Moïse & Associés créée (id={}, nif={}, framework=PCN_HAITI/005)",
        companyId,
        company.getNif());

    // ── 3 + 4. Users (owner consultant + manager approbateur) + UserCompanyRole ──
    // (tables users/ucr non RLS-protégées)
    UUID ownerId =
        ensureUser(
            companyId, OWNER_EMAIL, OWNER_PASSWORD, OWNER_FULL_NAME, OWNER_LOCALE, UserRole.OWNER);
    UUID managerId =
        ensureUser(
            companyId,
            MANAGER_EMAIL,
            MANAGER_PASSWORD,
            MANAGER_FULL_NAME,
            MANAGER_LOCALE,
            UserRole.ACCOUNTANT);
    LOG.info(
        "V9 — Users créés/résolus : owner={} ({}), manager={} ({})",
        ownerId,
        OWNER_EMAIL,
        managerId,
        MANAGER_EMAIL);

    // ── 5. Bootstraps + données métier (try-with-resources pour le contexte tenant) ──
    // La transaction @Transactional est ouverte par self.seedBusinessData() via le proxy Spring,
    // APRÈS que DemoTenantContext.of() ait positionné le ThreadLocal. Le
    // TenantRlsConnectionCustomizer intercepte setAutoCommit(false) au début de cette méthode
    // et applique SET LOCAL app.current_tenant = companyId.
    try (DemoTenantContext ctx = DemoTenantContext.of(companyId, ownerId)) {
      return self.seedBusinessData(companyId, ownerId, managerId);
    } catch (RuntimeException ex) {
      // Le try-with-resources garantit que TenantContext.clear() est appelé même sur exception.
      LOG.error(
          "V9 — Échec du seed Moïse & Associés (companyId={}) : {}",
          companyId,
          ex.getMessage(),
          ex);
      return 1; // au moins la company a été créée
    }
  }

  /**
   * rls-proper-fix — Méthode {@code @Transactional} qui crée toutes les données métier
   * (COA, journaux, exercices, séquences, clients pro, fournisseurs, employés, banque, projets,
   * billable rates, 12 mois d'opérations time-billing).
   *
   * <p><b>DOIT être appelée via le proxy Spring</b> (jamais directement via {@code this.}) pour que
   * l'annotation {@code @Transactional} soit appliquée. C'est pourquoi {@link #seed()} utilise
   * {@code self.seedBusinessData(...)} avec self-injection.
   *
   * <p>La transaction s'ouvre APRÈS que {@code DemoTenantContext.of()} ait positionné le
   * ThreadLocal → le {@code TenantRlsConnectionCustomizer} intercepte le
   * {@code setAutoCommit(false)} et applique {@code SET LOCAL app.current_tenant = companyId} →
   * tous les INSERT sur tables RLS-protégées passent la policy RLS.
   *
   * @param companyId identifiant de la company démo (tenant)
   * @param ownerId identifiant de l'user owner (consultant — utilisé pour createDemoBillableRates
   * et generateMonthlyOperations)
   * @param managerId identifiant du manager approbateur (utilisé pour approveEntry dans les
   * timesheets — règle anti-auto-approbation)
   * @return nombre d'enregistrements créés
   */
  @Transactional
  public int seedBusinessData(UUID companyId, UUID ownerId, UUID managerId) {
    // a, b, c — bootstraps (COA + journaux/exercices + séquences)
    coaBootstrap.bootstrap(companyId, PCN_HAITI_FRAMEWORK_ID, AccountFixture.all());
    fiscalYearBootstrap.bootstrap(companyId);
    numberingBootstrap.bootstrap(companyId);
    // Compléter les séquences documentaires manquantes (scopeKey="VT"/"AC"/"PA") que les
    // services InvoicingService/InvoicingService/PayrollService attendent mais que le
    // bootstrap générique ne crée pas (il ne crée que les variants à scopeKey="").
    ensureExtraDocumentSequences(companyId);
    // Journal DP (Dépenses) requis par ExpensesService.generateExpenseEntry
    ensureJournal(companyId, "DP", "Journal des dépenses");
    // Pré-charger les UUIDs des comptes PCN utilisés par les opérations mensuelles
    AccountRefs refs = AccountRefs.load(companyId, accountRepository);

    // f. 12 Clients pro (banques, télécom, institutions, grandes entreprises)
    List<ThirdPartyResponse> clients = createDemoClients(companyId, refs.clientsAccountId);
    // g. 4 Fournisseurs (abonnements logiciels, fournitures, etc.)
    List<ThirdPartyResponse> suppliers = createDemoSuppliers(companyId, refs.suppliersAccountId);
    // h. 8 Employés (4 consultants + 1 manager + 1 admin + 2 partners)
    List<EmployeeResponse> employees = createDemoEmployees(companyId, refs.personnelAccountId);
    // i. Banque
    createDemoBankAccount(companyId, refs.banqueAccountId);
    // j. 6 Projets TIME_AND_MATERIALS
    List<ProjectResponse> projects = createDemoProjects(companyId, clients);
    // k. 8 BillableRates (6 par projet + 1 ressource + 1 défaut)
    createDemoBillableRates(companyId, projects, ownerId);

    int totalCreated =
        1 /* company */
            + 2 /* users (owner + manager) */
            + 2 /* ucrs */
            + AccountFixture.all().size()
            + 5
            + 2 /* journaux + exercices */
            + 14 /* séquences (10 + 4 extras) */
            + 1 /* journal DP */
            + clients.size()
            + suppliers.size()
            + employees.size()
            + 1 /* bankAccount */
            + projects.size()
            + 8 /* billable rates */;

    // l. 12 mois d'opérations sur FY2025-2026 (time-billing + factures + achats + notes de frais
    // + paie)
    int monthlyOps =
        generateMonthlyOperations(
            companyId, ownerId, managerId, clients, suppliers, employees, projects);
    totalCreated += monthlyOps;

    LOG.info(
        "V9 — Moïse & Associés seed terminé pour companyId={} : {} enregistrements créés",
        companyId,
        totalCreated);
    return totalCreated;
  }

  // ══Création Company ══

  private Company createCompany() {
    Company company = new Company();
    company.setId(UUID.randomUUID());
    Instant now = Instant.now();
    company.setCreatedAt(now);
    company.setUpdatedAt(now);
    company.setName(COMPANY_NAME);
    company.setLegalForm(LegalForm.SA);
    company.setCountry("HT");
    company.setFunctionalCurrency("HTG");
    company.setNif("2020202020MA");
    company.setAddress("Rue Capois, Port-au-Prince");
    company.setSector(Sector.CABINET_COMPTABLE);
    company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
    company.setBusinessTypeCode("PROFESSIONAL_SERVICES");
    company.setPrimaryActivityLabel("Cabinet de conseil en management et comptabilité");
    company.setAccountingFrameworkId(PCN_HAITI_FRAMEWORK_ID);
    company.setFiscalYearStartMonth(10); // exercice haïtien 01/10 → 30/09
    company.setFreeZone(false);
    company.setTaxExemptionStatus(TaxExemptionStatus.STANDARD); // IS 30% standard
    company.setMonthlyLegalHours(new BigDecimal("208")); // Haïti 48h/sem × 52/12
    company.setWizardStep(jo.accountant.company.entity.Company.TOTAL_WIZARD_STEPS);
    company.setWizardCompleted(true);
    company.setIsDemo(true);
    return companyRepository.save(company);
  }

  // ══ Étapes 3 + 4 — Users (owner + manager) + UserCompanyRole ══

  /**
   * Crée un user via AuthService.register (idempotent via findByEmailIgnoreCase) + le
   * UserCompanyRole avec le rôle demandé (acceptedAt=now si OWNER, sinon juste invitedAt).
   */
  private UUID ensureUser(
      UUID companyId,
      String email,
      String password,
      String fullName,
      String locale,
      UserRole role) {
    Optional<User> existing = userRepository.findByEmailIgnoreCase(email);
    UUID userId;
    if (existing.isPresent()) {
      userId = existing.get().getId();
      LOG.info("V9 — User '{}' déjà existant — réutilisation id={}", email, userId);
    } else {
      try {
        User user = authService.register(email, password, fullName, locale);
        userId = user.getId();
      } catch (ConflictException ex) {
        // Race condition : un autre thread a créé l'utilisateur entre le check et l'INSERT.
        existing = userRepository.findByEmailIgnoreCase(email);
        if (existing.isEmpty()) {
          throw ex;
        }
        userId = existing.get().getId();
      }
    }

    if (userCompanyRoleRepository.findByUserIdAndCompanyId(userId, companyId).isEmpty()) {
      UserCompanyRole ucr = new UserCompanyRole();
      ucr.setId(UUID.randomUUID());
      ucr.setUserId(userId);
      ucr.setCompanyId(companyId);
      ucr.setRole(role);
      Instant now = Instant.now();
      ucr.setInvitedAt(now);
      if (role == UserRole.OWNER) {
        ucr.setAcceptedAt(now); // owner auto-accepté (créateur de la société)
      }
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
   * InvoicingService/InvoicingService/PayrollService attendent mais que le bootstrap générique ne
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

  // ══12 Clients pro (banques, télécom, institutions, grandes entreprises) ══

  private List<ThirdPartyResponse> createDemoClients(UUID companyId, UUID clientsAccountId) {
    String[][] defs = {
      {"Unibank S.A.", "Banque commerciale", "3010101010UB"},
      {"Sogebank S.A.", "Banque commerciale", "3020202020SB"},
      {"Banque Nationale de Crédit (BNC)", "Banque commerciale", "3030303030BC"},
      {"Digicel Haïti S.A.", "Télécommunications", "3040404040DG"},
      {"Natcom S.A.", "Télécommunications", "3050505050NC"},
      {"Banque de la République d'Haïti (BRH)", "Institution bancaire", "3060606060BH"},
      {"Mairie de Port-au-Prince", "Administration publique", "3070707070PP"},
      {"Ministère des Finances", "Administration publique", "3080808080MF"},
      {"Capital Bank S.A.", "Banque commerciale", "3090909090CB"},
      {"Unibank Holdings", "Holding financier", "3101010101UH"},
      {"Caribbean Air S.A.", "Transport aérien", "3111111111CA"},
      {"Haitel Telecom", "Télécommunications", "3121212122HT"}
    };
    List<ThirdPartyResponse> created = new ArrayList<>(defs.length);
    for (int i = 0; i < defs.length; i++) {
      String[] d = defs[i];
      String email =
          "client"
              + (i + 1)
              + "@"
              + d[0].toLowerCase()
                  .replace(" ", "-")
                  .replace(".", "")
                  .replace("(", "")
                  .replace(")", "")
                  .replace("'", "")
              + ".ht";
      String address = HaitianAddresses.randomAddress(segment()) + ", Port-au-Prince";
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.CLIENT, d[0], clientsAccountId, email, address, d[2]));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Client '{}' déjà existant — skip", d[0]);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création client '{}' : {}", d[0], ex.getMessage());
      }
    }
    return created;
  }

  // ══4 Fournisseurs (abonnements logiciels, fournitures bureau, etc.) ══

  private List<ThirdPartyResponse> createDemoSuppliers(UUID companyId, UUID suppliersAccountId) {
    String[][] defs = {
      {"Microsoft Haïti", "Abonnements Office 365 + Azure", "4010101010MS"},
      {"Sage Caribbean", "Licences Sage Paie + Comptabilité", "4020202020SG"},
      {"Papeterie Nationale S.A.", "Fournitures de bureau", "4030303030PN"},
      {"Caribbean Cloud Services", "Hébergement cloud + sauvegarde", "4040404040CC"}
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

  // ══8 Employés (4 consultants + 1 manager + 1 admin + 2 partners) ══

  private List<EmployeeResponse> createDemoEmployees(UUID companyId, UUID personnelAccountId) {
    Object[][] defs = {
      // {name, position, department, baseSalary, contractType}
      // 4 consultants (salaires 80k-120k HTG — junior/confirmed/senior)
      {
        HaitianNames.randomFullName(),
        "Consultant Junior",
        "Conseil",
        new BigDecimal("80000"),
        ContractType.PERMANENT
      },
      {
        HaitianNames.randomFullName(),
        "Consultant Confirmé",
        "Conseil",
        new BigDecimal("95000"),
        ContractType.PERMANENT
      },
      {
        HaitianNames.randomFullName(),
        "Consultant Senior",
        "Conseil",
        new BigDecimal("110000"),
        ContractType.PERMANENT
      },
      {
        HaitianNames.randomFullName(),
        "Consultant Senior",
        "Conseil",
        new BigDecimal("120000"),
        ContractType.PERMANENT
      },
      // 1 manager
      {
        HaitianNames.randomFullName(),
        "Manager",
        "Conseil",
        new BigDecimal("150000"),
        ContractType.PERMANENT
      },
      // 1 admin
      {
        HaitianNames.randomFullName(),
        "Administrateur",
        "Administration",
        new BigDecimal("90000"),
        ContractType.PERMANENT
      },
      // 2 partners
      {
        HaitianNames.randomFullName(),
        "Associé Directeur",
        "Direction",
        new BigDecimal("200000"),
        ContractType.PERMANENT
      },
      {
        HaitianNames.randomFullName(),
        "Associé Directeur",
        "Direction",
        new BigDecimal("200000"),
        ContractType.PERMANENT
      }
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
                    null, // thirdPartyId null → le service crée le tiers EMPLOYEE auto
                    name, // thirdPartyName
                    personnelAccountId, // collectiveAccountId (421000 Personnel - rémunérations
                    // dues)
                    "MA-" + String.format("%03d", i + 1), // employeeNumber
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

  // ══Compte bancaire (Unibank HTG) ══

  private void createDemoBankAccount(UUID companyId, UUID banqueAccountId) {
    try {
      bankReconciliationService.createBankAccount(
          companyId,
          new CreateBankAccountRequest(
              banqueAccountId, "Unibank — Compte courant Moïse & Associés", "020-765432-01"));
    } catch (ConflictException ex) {
      LOG.debug("V9 — BankAccount déjà existant — skip");
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création BankAccount : {}", ex.getMessage());
    }
  }

  // ══6 Projets TIME_AND_MATERIALS ══

  private List<ProjectResponse> createDemoProjects(
      UUID companyId, List<ThirdPartyResponse> clients) {
    if (clients.isEmpty()) {
      LOG.warn("V9 — Aucun client créé — projets non créés");
      return List.of();
    }
    String[][] defs = {
      {"PROJ-001", "Audit organisationnel Unibank"},
      {"PROJ-002", "Mise en conformité fiscale Sogebank"},
      {"PROJ-003", "Transformation digitale Digicel"},
      {"PROJ-004", "Conseil stratégie BRH"},
      {"PROJ-005", "Optimisation processus BNC"},
      {"PROJ-006", "Audit comptable Mairie PAP"}
    };
    List<ProjectResponse> created = new ArrayList<>(defs.length);
    for (int i = 0; i < defs.length; i++) {
      String[] d = defs[i];
      ThirdPartyResponse client = clients.get(i % clients.size());
      try {
        ProjectResponse p =
            timeBillingService.createProject(
                companyId,
                new CreateProjectRequest(d[0], d[1], client.id(), BillingType.TIME_AND_MATERIALS));
        created.add(p);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Projet {} déjà existant — skip", d[0]);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création projet {} : {}", d[0], ex.getMessage());
      }
    }
    return created;
  }

  // ══8 BillableRates (6 par projet + 1 ressource + 1 défaut) ══

  private void createDemoBillableRates(
      UUID companyId, List<ProjectResponse> projects, UUID ownerId) {
    // Taux 3500-8000 HTG/h selon complexité du projet
    BigDecimal[] projectRates = {
      new BigDecimal("6500"), // PROJ-001 — audit bancaire (senior)
      new BigDecimal("7000"), // PROJ-002 — conformité fiscale (senior)
      new BigDecimal("8000"), // PROJ-003 — transformation digitale (top)
      new BigDecimal("5500"), // PROJ-004 — conseil stratégie (confirmed+)
      new BigDecimal("5000"), // PROJ-005 — optimisation processus (confirmed)
      new BigDecimal("3500") // PROJ-006 — audit comptable (junior+)
    };
    int created = 0;
    // 6 rates par projet (projectId=specific, resourceUserId=ownerId)
    for (int i = 0; i < projects.size() && i < projectRates.length; i++) {
      ProjectResponse p = projects.get(i);
      try {
        timeBillingService.createBillableRate(
            companyId, new CreateBillableRateRequest(p.id(), ownerId, projectRates[i], "HTG"));
        created++;
      } catch (ConflictException ex) {
        LOG.debug("V9 — BillableRate pour projet {} déjà existant — skip", p.code());
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création BillableRate pour projet {} : {}", p.code(), ex.getMessage());
      }
    }
    // 1 rate ressource-level (projectId=null, resourceUserId=ownerId) — défaut consultant
    try {
      timeBillingService.createBillableRate(
          companyId, new CreateBillableRateRequest(null, ownerId, new BigDecimal("5000"), "HTG"));
      created++;
    } catch (ConflictException ex) {
      LOG.debug("V9 — BillableRate ressource-level déjà existant — skip");
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création BillableRate ressource-level : {}", ex.getMessage());
    }
    // 1 rate company-level (projectId=null, resourceUserId=null) — défaut entreprise
    try {
      timeBillingService.createBillableRate(
          companyId, new CreateBillableRateRequest(null, null, new BigDecimal("4500"), "HTG"));
      created++;
    } catch (ConflictException ex) {
      LOG.debug("V9 — BillableRate company-level déjà existant — skip");
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création BillableRate company-level : {}", ex.getMessage());
    }
    LOG.info("V9 — {} BillableRates créés pour companyId={}", created, companyId);
  }

  // ══12 mois d'opérations sur FY2025-2026 ══

  /**
   * Génère 12 mois d'opérations (Oct 2025 → Sep 2026). Chaque mois est isolé dans un try/catch : un
   * échec n'empêche pas les mois suivants de continuer.
   *
   * <p>Opérations mensuelles :
   *
   * <ul>
   * <li>8-15 TimesheetEntry par consultant actif (entryDate aléatoire dans le mois, hours 4-8,
   * billable=true) → createTimesheetEntry + approveEntry (par le manager — règle v7-9)
   * <li>3-5 Invoice par mois (facturation au temps passé — getUnbilled → lignes avec
   * timesheetEntryId → issueInvoice génère écriture VT avec RS 2%)
   * <li>1-2 Invoice (abonnements logiciels, fournitures)
   * <li>2-3 ExpenseReport (transport, repas clients)
   * <li>1 PayrollRun (calculate + approve → écriture PA, 12% OFATMA)
   * </ul>
   */
  private int generateMonthlyOperations(
      UUID companyId,
      UUID ownerId,
      UUID managerId,
      List<ThirdPartyResponse> clients,
      List<ThirdPartyResponse> suppliers,
      List<EmployeeResponse> employees,
      List<ProjectResponse> projects) {
    if (projects.isEmpty() || clients.isEmpty() || employees.isEmpty()) {
      LOG.warn(
          "V9 — Données de base insuffisantes pour générer les opérations mensuelles "
              + "(projects={}, clients={}, employees={}) — skip",
          projects.size(),
          clients.size(),
          employees.size());
      return 0;
    }

    int total = 0;
    LocalDate month = FY2526_START;
    int monthIdx = 0;
    while (!month.isAfter(FY2526_END)) {
      int monthCount = 0;
      try {
        // a. Timesheets : 8-15 entrées/mois (consultant actif = ownerId)
        int nTs = 8 + (monthIdx % 8); // 8, 9, 10, 11, 12, 13, 14, 15, 8, ...
        for (int i = 0; i < nTs; i++) {
          if (createTimesheetEntry(companyId, ownerId, managerId, month, i, projects)) {
            monthCount++;
          }
        }
        // b. 3-5 Invoice/mois (facturation au temps passé)
        int nInv = 3 + (monthIdx % 3); // 3, 4, 5, 3, 4, 5, ...
        for (int i = 0; i < nInv; i++) {
          if (createTimeBillingInvoice(companyId, month, i, projects)) {
            monthCount++;
          }
        }
        // c. 1-2 Invoice/mois (abonnements logiciels, fournitures)
        int nPur = 1 + (monthIdx % 2); // 1, 2, 1, 2, ...
        for (int i = 0; i < nPur; i++) {
          if (createPurchaseInvoice(companyId, month, i, suppliers)) {
            monthCount++;
          }
        }
        // d. 2-3 ExpenseReport/mois (transport, repas clients)
        int nExp = 2 + (monthIdx % 2); // 2, 3, 2, 3, ...
        for (int i = 0; i < nExp; i++) {
          if (createExpenseReport(companyId, month, i, employees)) {
            monthCount++;
          }
        }
        // e. 1 PayrollRun/mois
        if (createPayrollRun(companyId, month)) {
          monthCount++;
        }
        LOG.info("V9 — Moïse & Associés mois {} : {} opérations créées", month, monthCount);
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
   * Crée une entrée de timesheet (4-8h, billable=true) sur une date aléatoire du mois, puis
   * l'approuve via le manager (v7-9 — approbateur distinct du consultant).
   */
  private boolean createTimesheetEntry(
      UUID companyId,
      UUID ownerId,
      UUID managerId,
      LocalDate month,
      int seq,
      List<ProjectResponse> projects) {
    ProjectResponse project = projects.get(seq % projects.size());
    int day = 1 + ((seq * 3 + 7) % 27); // 1-28 (évite fin de mois)
    LocalDate entryDate = month.withDayOfMonth(day);
    BigDecimal hours =
        new BigDecimal(4 + ((seq * 5) % 5)) // 4-8 heures
            .setScale(2, RoundingMode.HALF_UP);
    String description =
        "Mission " + project.code() + " — " + project.label() + " (séance " + (seq + 1) + ")";
    try {
      TimesheetEntryResponse entry =
          timeBillingService.createTimesheetEntry(
              companyId,
              new CreateTimesheetEntryRequest(
                  project.id(), ownerId, entryDate, hours, Boolean.TRUE, description));
      // Approuver via le manager (approverId distinct du resourceUserId — règle v7-9)
      timeBillingService.approveEntry(companyId, entry.id(), managerId);
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — TimesheetEntry déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec TimesheetEntry {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /**
   * Crée une facture de vente au temps passé : récupère le WIP non facturé du projet, prend les
   * premières lignes, et crée une facture avec {@code timesheetEntryId} sur chaque ligne. Applique
   * TVA 10% + TCA 10% + RS 2% (retenue à la source sur prestations locales).
   */
  private boolean createTimeBillingInvoice(
      UUID companyId, LocalDate month, int seq, List<ProjectResponse> projects) {
    ProjectResponse project = projects.get((seq + month.getMonthValue()) % projects.size());
    UnbilledWip wip;
    try {
      wip = timeBillingService.getUnbilled(companyId, project.id());
    } catch (RuntimeException ex) {
      LOG.debug("V9 — getUnbilled a échoué pour projet {} : {}", project.code(), ex.getMessage());
      return false;
    }
    if (wip == null || wip.lines() == null || wip.lines().isEmpty()) {
      LOG.debug(
          "V9 — Pas de WIP non facturé pour projet {} sur {} seq={} — skip facture",
          project.code(),
          month,
          seq);
      return false;
    }
    // Prendre 1-4 lignes (selon seq)
    int nLines = Math.min(1 + (seq % 4), wip.lines().size());
    List<UnbilledWip.UnbilledLine> wipLines = wip.lines().subList(0, nLines);
    List<CreateInvoiceRequest.LineDto> lines = new ArrayList<>(nLines);
    for (UnbilledWip.UnbilledLine wl : wipLines) {
      // Multi-tax TVA 10% + TCA 10% sur la même ligne (Code Fiscal art. 191 + 196)
      List<TaxApplication> taxes =
          List.of(
              new TaxApplication("VAT", null, VAT_RATE, 1),
              new TaxApplication("TCA", null, TCA_RATE, 2));
      String desc =
          "Prestation conseil — "
              + project.code()
              + " ("
              + wl.hours().setScale(2, RoundingMode.HALF_UP)
              + "h × "
              + wl.hourlyRate()
              + " HTG/h)";
      lines.add(
          new CreateInvoiceRequest.LineDto(
              desc,
              wl.hours(), // quantity = hours
              wl.hourlyRate(), // unitPrice = hourly rate
              BigDecimal.ZERO, // discountPercent
              BigDecimal.ZERO, // taxRate (fallback mono-taxe — non utilisé car taxes non null)
              null, // itemId (SERVICE — pas de produit)
              wl.entryId(), // timesheetEntryId — clé pour facturation au temps passé
              taxes));
    }
    LocalDate issueDate = month.withDayOfMonth(Math.min(seq + 10, 28));
    CreateInvoiceRequest req =
        new CreateInvoiceRequest(
            project.clientThirdPartyId(),
            InvoiceType.STANDARD,
            issueDate,
            issueDate.plusDays(30),
            "HTG",
            lines,
            null, // creditNoteForInvoiceId
            null, // withholdingRuleCode — on force le taux directement
            RS_RATE // withholdingRate = 2% (Code Fiscal art. 156-1 — RS sur prestations locales)
            );
    try {
      InvoiceResponse inv = invoicingService.createInvoice(companyId, req);
      invoicingService.issueInvoice(companyId, inv.id());
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Facture temps-passé déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn(
          "V9 — Échec facture temps-passé {} seq={} projet {} : {}",
          month,
          seq,
          project.code(),
          ex.getMessage());
    }
    return false;
  }

  /**
   * Crée une facture d'achat (abonnements logiciels, fournitures bureau, hébergement) — 1-3 lignes,
   * TVA 10% déductible, receive() génère l'écriture AC.
   */
  private boolean createPurchaseInvoice(
      UUID companyId, LocalDate month, int seq, List<ThirdPartyResponse> suppliers) {
    if (suppliers.isEmpty()) {
      return false;
    }
    ThirdPartyResponse supplier = suppliers.get((month.getMonthValue() + seq) % suppliers.size());
    int nLines = 1 + (seq % 3); // 1, 2, 3
    List<CreateInvoiceRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      int qty = 1 + ((seq + i) * 3) % 5; // 1-5 licences/unités
      BigDecimal unitPrice =
          new BigDecimal(2500 + ((seq + i) * 421) % 18000) // 2500-20499 HTG
              .setScale(2, RoundingMode.HALF_UP);
      lines.add(
          new CreateInvoiceRequest.LineDto(
              "Abonnement/Fourniture — " + supplier.name() + " (lot " + i + ")",
              new BigDecimal(qty),
              unitPrice,
              BigDecimal.ZERO, // discountPercent=0
              VAT_RATE, // taxRate
              null // itemId=null
              ));
    }
    LocalDate issueDate = month.withDayOfMonth(Math.min(seq + 8, 27));
    CreateInvoiceRequest req =
        new CreateInvoiceRequest(
            supplier.id(),
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
      LOG.debug("V9 — Facture achat déjà existante pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec facture achat {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /** Crée une note de frais (transport, repas clients, fournitures) — submit + approve (DP). */
  private boolean createExpenseReport(
      UUID companyId, LocalDate month, int seq, List<EmployeeResponse> employees) {
    EmployeeResponse emp = employees.get((month.getMonthValue() + seq) % employees.size());
    int nLines = 1 + (seq % 2); // 1, 2
    String[][] cats = {
      {"TRAVEL", "Transport taxi/tap-tap déplacements clients"},
      {"MEALS", "Repas clients — déjeuners d'affaires"},
      {"SUPPLIES", "Fournitures de bureau — papier, cartouches"},
      {"SUPPLIES", "Crédit téléphonique et data pro"}
    };
    List<CreateExpenseReportRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      String[] cat = cats[(seq + i) % cats.length];
      BigDecimal amount =
          new BigDecimal(1500 + ((seq + i) * 631) % 11000) // 1500-12499 HTG
              .setScale(2, RoundingMode.HALF_UP);
      lines.add(new CreateExpenseReportRequest.LineDto(cat[0], cat[1], amount, null));
    }
    LocalDate expDate = month.withDayOfMonth(Math.min(seq + 12, 26));
    CreateExpenseReportRequest req =
        new CreateExpenseReportRequest(
            emp.thirdPartyId(),
            expDate,
            "HTG",
            "Dépenses mission "
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

  /** Crée une campagne de paie mensuelle : create → calculate (12% OFATMA) → approve (PA). */
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

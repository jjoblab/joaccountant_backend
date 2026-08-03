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
import jo.accountant.core.currency.ExchangeRateService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.demo.fixtures.ExchangeRateFixtures;
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
import jo.accountant.fundsgrants.dto.CreateDonationReceiptRequest;
import jo.accountant.fundsgrants.dto.CreateGrantRequest;
import jo.accountant.fundsgrants.dto.GrantResponse;
import jo.accountant.fundsgrants.entity.DonationReceipt;
import jo.accountant.fundsgrants.entity.DonationType;
import jo.accountant.fundsgrants.entity.RestrictionType;
import jo.accountant.fundsgrants.repository.DonationReceiptRepository;
import jo.accountant.fundsgrants.service.FundsGrantsService;
import jo.accountant.fxoperations.dto.CreateFxOperationRequest;
import jo.accountant.fxoperations.entity.FxOperationType;
import jo.accountant.fxoperations.service.FxOperationsService;
import jo.accountant.invoicing.service.InvoicingService;
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.payroll.dto.CreatePayrollRunRequest;
import jo.accountant.payroll.dto.PayrollRunResponse;
import jo.accountant.payroll.service.PayrollService;
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
 * V9 — PME 3 : Espwa pou Ayiti (ONG humanitaire Port-au-Prince, Delmas 33).
 *
 * <p>35 employés, ~5M USD/an (~60M HTG), <b>USD</b> comme devise fonctionnelle (et non HTG — ONG
 * dollarisée car bailleurs en USD), PCN_HAITI, <b>IS 0% NGO_EXEMPT</b> (Code Fiscal art. 195),
 * <b>TVA exonérée</b> (VAT_EXEMPT_NGO), 4 bailleurs institutionnels (USAID, EU, World Bank, CRS)
 * sur 2 exercices fiscaux haïtiens (01/10 → 30/09).
 *
 * <p>Données générées (idempotent) : company + user owner + 50 comptes PCN_HAITI (avec 740000
 * Subventions d'exploitation tagué {@code DONATION_REVENUE} pour les écritures de dons) + 6
 * journaux (VT/AC/BQ/OD/PA/DP) + 2 exercices fiscaux + 14 séquences documentaires + 4 bailleurs
 * DONOR + 8 fournisseurs SUPPLIER + 12 bénéficiaires CLIENT + 35 employés (5 staff HQ + 30 field
 * workers) + 1 banque USD + 4 subventions (1 par bailleur) + 12 mois d'opérations (DonationReceipt
 * CASH + IN_KIND, Invoice TVA 0%, ExpenseReport, PayrollRun, FxOperation trimestrielle
 * USD→HTG).
 *
 * <p><b>Spécificités NGO</b> :
 *
 * <ul>
 * <li><b>Multi-currency USD</b> — toutes les factures/dons/paies sont en USD (devise
 * fonctionnelle). Les dépenses locales nécessitent une conversion USD→HTG via FxOperation.
 * <li><b>TVA exonérée</b> — les factures d'achat ont {@code taxRate=0} (VAT_EXEMPT_NGO). Aucune
 * TVA collectée ni déductible n'apparaît sur les écritures.
 * <li><b>Funds-grants</b> — 4 Grants (3 RESTRICTED + 1 UNRESTRICTED) avec DonationReceipt cash et
 * en nature (V8-5 IN_KIND). Le {@code CreateDonationReceiptRequest} n'exposant pas le champ
 * {@code donationType}, les reçus en nature sont créés via le service (génère une écriture
 * cash D 521 / C 740) puis patchés via {@link DonationReceiptRepository} pour setter {@code
 * donationType=IN_KIND} — l'écriture comptable reste cash (limitation documentée, ne bloque
 * pas la compilation ni le seed).
 * <li><b>Fx-operations</b> — 1 taux de change USD/HTG créé par trimestre via {@link
 * ExchangeRateService#createRate} (source BRH, valeurs issues de {@link
 * ExchangeRateFixtures}) + 1 FxOperation SELL USD→HTG par trimestre (conversion pour dépenses
 * locales).
 * </ul>
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
public class NgoHumanitarianSeeder implements CompanySeeder {

  private static final Logger LOG = LoggerFactory.getLogger(NgoHumanitarianSeeder.class);

  /**
   * UUID du référentiel PCN_HAITI (V3__core_seeds.sql ligne 26 — correctif V9 : la version précédente
   * pointait par erreur sur ...004 = PCG_FRANCE).
   */
  private static final UUID PCN_HAITI_FRAMEWORK_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000005");

  // ── Paramètres démo (Espwa pou Ayiti — ONG humanitaire Delmas 33) ──

  private static final String COMPANY_NAME = "Espwa pou Ayiti";
  private static final String OWNER_EMAIL = "owner@espwa-ayiti.demo";
  private static final String OWNER_PASSWORD = "Demo1234!2026";
  private static final String OWNER_FULL_NAME = "Espwa pou Ayiti Owner";
  private static final String OWNER_LOCALE = "fr";

  /** Taux cotisations OFATMA part patronale (12% — taux plancher NGO haïtien). */
  private static final BigDecimal EMPLOYER_CONTRIBUTION_RATE = new BigDecimal("12");

  /** FY2025-2026 = 01/10/2025 → 30/09/2026 (12 mois de données opérationnelles). */
  private static final LocalDate FY2526_START = LocalDate.of(2025, 10, 1);

  private static final LocalDate FY2526_END = LocalDate.of(2026, 9, 30);

  /** Toutes les opérations NGO sont en USD (devise fonctionnelle dollarisée). */
  private static final String FUNCTIONAL_CURRENCY = "USD";

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
  private final FundsGrantsService fundsGrantsService;
  private final DonationReceiptRepository donationReceiptRepository;
  private final FxOperationsService fxOperationsService;
  private final ExchangeRateService exchangeRateService;

  /**
   * rls-proper-fix — Self-injection via le proxy Spring.
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
  private NgoHumanitarianSeeder self;

  public NgoHumanitarianSeeder(
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
      FundsGrantsService fundsGrantsService,
      DonationReceiptRepository donationReceiptRepository,
      FxOperationsService fxOperationsService,
      ExchangeRateService exchangeRateService) {
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
    this.fundsGrantsService = fundsGrantsService;
    this.donationReceiptRepository = donationReceiptRepository;
    this.fxOperationsService = fxOperationsService;
    this.exchangeRateService = exchangeRateService;
  }

  @Override
  public String demoCode() {
    return "ESPWA_POU_AYITI";
  }

  @Override
  public String companyName() {
    return COMPANY_NAME;
  }

  @Override
  public String segment() {
    return "NGO_HUMANITARIAN";
  }

  /**
   * Crée la Company + user owner + toutes les données métier.
   *
   * <p>Idempotent : si la Company existe déjà (name + isDemo=true), retourne 0.
   *
   * <p>La méthode n'est PLUS {@code @Transactional}. La Company +
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
      LOG.info("V9 — Espwa pou Ayiti déjà seedée (id={}) — skip", existing.get().getId());
      return 0;
    }

    // ── 2. Création de la Company ── (hors DemoTenantContext — table companies non RLS-protégée)
    Company company = createCompany();
    final UUID companyId = company.getId();
    LOG.info(
        "V9 — Company Espwa pou Ayiti créée (id={}, nif={}, framework=PCN_HAITI/005, "
            + "currency=USD, taxExemption=NGO_EXEMPT)",
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
          "V9 — Échec du seed Espwa pou Ayiti (companyId={}) : {}", companyId, ex.getMessage(), ex);
      return 1; // au moins la company a été créée
    }
  }

  /**
   * rls-proper-fix — Méthode {@code @Transactional} qui crée toutes les données métier
   * (COA, journaux, exercices, séquences, bailleurs, fournisseurs, bénéficiaires, employés, banque
   * USD, subventions, 12 mois d'opérations NGO multi-currency USD).
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
   * utilisé directement dans le corps car les services métier utilisent le ThreadLocal)
   * @return nombre d'enregistrements créés
   */
  @Transactional
  public int seedBusinessData(UUID companyId, UUID ownerId) {
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
    // Tagger le compte 740000 Subventions d'exploitation avec taxMappingCode=DONATION_REVENUE
    // pour que FundsGrantsService le resolve comme compte de produit de don (au lieu du
    // fallback sur le premier compte PRODUITS — qui serait 701000 Ventes de marchandises).
    tagDonationRevenueAccount(companyId);
    // Pré-charger les UUIDs des comptes PCN utilisés par les opérations mensuelles
    AccountRefs refs = AccountRefs.load(companyId, accountRepository);

    // d. Bailleurs (4 DONOR — USAID, EU, World Bank, CRS) — rattachés au compte collectif
    // FOURNISSEURS (401000) car le PCN_HAITI par défaut ne définit pas de compte collectif
    // dédié aux bailleurs. Les écritures de don ne passent PAS par le compte collectif du
    // bailleur (le FundsGrantsService resolve un compte CASH + un compte PRODUITS), donc ce
    // choix est purement cosmétique.
    List<ThirdPartyResponse> donors = createDemoDonors(companyId, refs.suppliersAccountId);
    // e. Fournisseurs locaux (8 SUPPLIER — pharmacie, construction, transport, etc.)
    List<ThirdPartyResponse> suppliers = createDemoSuppliers(companyId, refs.suppliersAccountId);
    // f. Bénéficiaires/partenaires (12 CLIENT — mairies, ONG locales, écoles)
    List<ThirdPartyResponse> clients = createDemoClients(companyId, refs.clientsAccountId);
    // g. Employés (35 — 5 staff HQ + 30 field workers)
    List<EmployeeResponse> employees = createDemoEmployees(companyId, refs.personnelAccountId);
    // h. Banque (Capital Bank, USD)
    createDemoBankAccount(companyId, refs.banqueAccountId);
    // i. Subventions (4 — une par bailleur)
    List<GrantResponse> grants = createDemoGrants(companyId, donors);

    int totalCreated =
        1 /* company */
            + 1 /* user */
            + 1 /* ucr */
            + AccountFixture.all().size()
            + 5
            + 2 /* journaux + exercices */
            + 14 /* séquences (10 + 4 extras) */
            + 1 /* journal DP */
            + donors.size()
            + suppliers.size()
            + clients.size()
            + employees.size()
            + 1 /* bankAccount */
            + grants.size();

    // j. 12 mois d'opérations sur FY2025-2026
    int monthlyOps =
        generateMonthlyOperations(companyId, donors, suppliers, employees, grants, refs);
    totalCreated += monthlyOps;

    LOG.info(
        "V9 — Espwa pou Ayiti seed terminé pour companyId={} : {} enregistrements créés",
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
    company.setLegalForm(LegalForm.NGO);
    company.setCountry("HT");
    company.setFunctionalCurrency(FUNCTIONAL_CURRENCY); // dollarisée — bailleurs en USD
    company.setNif("3030303030EA");
    company.setAddress("Delmas 33, Port-au-Prince");
    company.setSector(Sector.ONG_HUMANITAIRE);
    company.setOrganizationNature(OrganizationNature.NON_PROFIT);
    company.setBusinessTypeCode("NGO_HUMANITARIAN");
    company.setPrimaryActivityLabel(
        "ONG humanitaire — santé, éducation, sécurité alimentaire, WASH");
    company.setAccountingFrameworkId(PCN_HAITI_FRAMEWORK_ID);
    company.setFiscalYearStartMonth(10); // exercice haïtien 01/10 → 30/09
    company.setFreeZone(false);
    company.setTaxExemptionStatus(TaxExemptionStatus.NGO_EXEMPT); // V8-1 — IS 0% art. 195
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

  /**
   * Tag le compte 740000 (Subventions d'exploitation) avec {@code taxMappingCode=DONATION_REVENUE}
   * pour que {@link FundsGrantsService} le resolve comme compte de produit de don (au lieu du
   * fallback sur le premier compte PRODUITS, qui serait 701000 Ventes de marchandises — inadapté
   * pour une ONG).
   *
   * <p>Idempotent : si le compte a déjà le bon taxMappingCode, ne fait rien.
   */
  private void tagDonationRevenueAccount(UUID companyId) {
    Optional<Account> subvOpt =
        accountRepository.findByCompanyIdAndCode(
            companyId, AccountFixture.SUBVENTIONS_EXPLOITATION.code());
    if (subvOpt.isEmpty()) {
      LOG.warn(
          "V9 — Compte 740000 (Subventions d'exploitation) introuvable pour companyId={} — "
              + "les dons seront comptabilisés sur le premier compte PRODUITS (fallback)",
          companyId);
      return;
    }
    Account subv = subvOpt.get();
    if ("DONATION_REVENUE".equals(subv.getTaxMappingCode())) {
      return; // déjà tagué — idempotence
    }
    subv.setTaxMappingCode("DONATION_REVENUE");
    accountRepository.save(subv);
    LOG.info(
        "V9 — Compte 740000 tagué DONATION_REVENUE pour companyId={} (FundsGrantsService)",
        companyId);
  }

  // ══Bailleurs (4 DONOR) ══

  private List<ThirdPartyResponse> createDemoDonors(UUID companyId, UUID donorsCollectiveId) {
    // 4 bailleurs institutionnels : USAID, EU, World Bank, CRS
    // NIF Haïti : 10 chiffres + 2 lettres majuscules (les bailleurs internationaux sont
    // enregistrés au registre DGI des ONG et ont un NIF haïtien pour la reddition de comptes).
    Object[][] defs = {
      {
        "USAID",
        "United States Agency for International Development",
        "5252525252US",
        "Boulevard Harry Truman, Port-au-Prince"
      },
      {
        "European Union",
        "Délégation de l'Union Européenne en Haïti",
        "5353535353EU",
        "Rue Leonardo da Vinci, Turgeau, Pétion-Ville"
      },
      {
        "World Bank",
        "Banque Mondiale — Bureau Haïti",
        "5454545454WB",
        "Péguy-Ville, Port-au-Prince"
      },
      {"CRS Haiti", "Catholic Relief Services — Haïti", "5555555555CR", "Delmas 75, Port-au-Prince"}
    };
    List<ThirdPartyResponse> created = new ArrayList<>(defs.length);
    for (Object[] d : defs) {
      String name = (String) d[0];
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.DONOR,
                    name,
                    donorsCollectiveId,
                    "contact@" + name.toLowerCase().replace(" ", "-").replace(".", "") + ".ht",
                    (String) d[3],
                    (String) d[2]));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Donor '{}' déjà existant — skip", name);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création donor '{}' : {}", name, ex.getMessage());
      }
    }
    return created;
  }

  // ══Fournisseurs locaux (8 SUPPLIER) ══

  private List<ThirdPartyResponse> createDemoSuppliers(UUID companyId, UUID suppliersAccountId) {
    // 8 fournisseurs locaux typiques d'une ONG humanitaire
    Object[][] defs = {
      {"Pharmacom Haïti S.A.", "Médicaments et consommables médicaux", "6060606060PH"},
      {"Construction Plus S.A.", "Matériaux construction et BTP", "6161616161CP"},
      {"Transports Cetout", "Logistique et transport terrain", "6262626262TC"},
      {"Agri-Inputs Haïti", "Intrants agricoles et semences", "6363636363AI"},
      {"AquaPure Water Solutions", "Filtration et adduction d'eau (WASH)", "6464646464AW"},
      {"Papeterie Nationale", "Fournitures de bureau et impression", "6565656565PN"},
      {"Carburant National S.A.", "Carburant et lubrifiants (vehicles terrain)", "6666666666CN"},
      {"TecnoEquip S.A.", "Équipements médicaux et formation", "6767676767TE"}
    };
    List<ThirdPartyResponse> created = new ArrayList<>(defs.length);
    for (Object[] d : defs) {
      String name = (String) d[0];
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.SUPPLIER,
                    name,
                    suppliersAccountId,
                    "fournisseur@"
                        + name.toLowerCase().replace(" ", "-").replace(".", "").replace("é", "e")
                        + ".ht",
                    d[1] + ", Port-au-Prince",
                    (String) d[2]));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Fournisseur '{}' déjà existant — skip", name);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création fournisseur '{}' : {}", name, ex.getMessage());
      }
    }
    return created;
  }

  // ══Bénéficiaires/partenaires (12 CLIENT) ══

  private List<ThirdPartyResponse> createDemoClients(UUID companyId, UUID clientsAccountId) {
    // 12 bénéficiaires/partenaires : mairies, ONG locales, écoles, hôpitaux communautaires.
    // L'ONG ne facture pas ces tiers (pas de ventes), mais les crée pour le réalisme démo
    // (suivi des partenaires d'exécution des programmes).
    String[] names = {
      "Mairie de Croix-des-Bouquets", "Mairie de Carrefour", "Mairie de Tabarre",
      "ONG Santé Pou Tout Moun", "ONG Edukasyon Potekole", "ONG Eau Potable Lakay",
      "École Nationale Lambert", "École Mixte L'Espérance", "Collège Saint-Esprit",
      "Hôpital Communautaire Delmas", "Clinique Mobile Bon Samaritain",
          "Centre Nutritionnel Mère-Enfant"
    };
    List<ThirdPartyResponse> created = new ArrayList<>(names.length);
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      try {
        ThirdPartyResponse tp =
            thirdPartiesService.createThirdParty(
                companyId,
                new CreateThirdPartyRequest(
                    ThirdPartyType.CLIENT,
                    name,
                    clientsAccountId,
                    "partenaire" + (i + 1) + "@espwa-ayiti.demo",
                    HaitianAddresses.randomAddress(segment()) + ", Haïti",
                    "303030303" + i + "EA"));
        created.add(tp);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Bénéficiaire '{}' déjà existant — skip", name);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création bénéficiaire '{}' : {}", name, ex.getMessage());
      }
    }
    return created;
  }

  // ══Employés (35 : 5 staff HQ + 30 field workers) ══

  private List<EmployeeResponse> createDemoEmployees(UUID companyId, UUID personnelAccountId) {
    List<EmployeeResponse> created = new ArrayList<>(35);

    // 5 staff HQ — salaires USD 2500-4500 (devise fonctionnelle dollarisée)
    Object[][] hqStaff = {
      {"Dr. Marie-Carmel Pierre-Louis", "Country Director", "Direction", new BigDecimal("4500")},
      {"Jean-Moïse Auguste", "Finance & Compliance Manager", "Finance", new BigDecimal("3500")},
      {"Nadège Saintilus", "HR & Admin Manager", "Ressources Humaines", new BigDecimal("2800")},
      {"Carlo Belizaire", "Programs Manager", "Programmes", new BigDecimal("3200")},
      {
        "Wilner Dorcely",
        "Logistics & Procurement Coordinator",
        "Logistique",
        new BigDecimal("2500")
      }
    };
    int empIdx = 1;
    for (Object[] d : hqStaff) {
      created.add(createOneEmployee(companyId, personnelAccountId, empIdx++, d));
    }

    // 30 field workers — salaires USD 400-900 (field officers, nurses, drivers, warehouse workers)
    // On alterne les postes/départements pour le réalisme, avec 6 profils répartis sur 30 employés.
    Object[][] fieldProfiles = {
      {"Field Officer", "Programmes", new BigDecimal("850")},
      {"Community Health Nurse", "Santé", new BigDecimal("900")},
      {"Driver / Logistician", "Logistique", new BigDecimal("550")},
      {"Warehouse Worker", "Logistique", new BigDecimal("450")},
      {"WASH Technician", "Programmes", new BigDecimal("700")},
      {"Animatrice Communautaire", "Programmes", new BigDecimal("400")}
    };
    for (int i = 0; i < 30; i++) {
      Object[] profile = fieldProfiles[i % fieldProfiles.length];
      // On génère un nom réaliste haïtien pour chaque field worker
      Object[] d = {HaitianNames.randomFullName(), profile[0], profile[1], profile[2]};
      created.add(createOneEmployee(companyId, personnelAccountId, empIdx++, d));
    }

    // Filtrer les null (échecs silencieux dans createOneEmployee)
    created.removeIf(java.util.Objects::isNull);
    return created;
  }

  private EmployeeResponse createOneEmployee(
      UUID companyId, UUID personnelAccountId, int idx, Object[] d) {
    String name = (String) d[0];
    try {
      return employeesService.create(
          companyId,
          new CreateEmployeeRequest(
              null, // thirdPartyId null → le service crée le tiers EMPLOYEE automatiquement
              name, // thirdPartyName
              personnelAccountId, // collectiveAccountId (421000 Personnel - rémunérations dues)
              "EPA-" + String.format("%03d", idx), // employeeNumber
              (String) d[1], // position
              (String) d[2], // department
              LocalDate.of(2024, 10, 1), // hireDate (début FY2024-2025)
              (BigDecimal) d[3], // baseSalary USD
              FUNCTIONAL_CURRENCY, // salaryCurrency USD (devise fonctionnelle dollarisée)
              ContractType.PERMANENT,
              null // bankAccountNumber
              ));
    } catch (ConflictException ex) {
      LOG.debug("V9 — Employé '{}' déjà existant — skip", name);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création employé '{}' : {}", name, ex.getMessage());
    }
    return null;
  }

  // ══Compte bancaire (Capital Bank, USD) ══

  private void createDemoBankAccount(UUID companyId, UUID banqueAccountId) {
    try {
      bankReconciliationService.createBankAccount(
          companyId,
          new CreateBankAccountRequest(
              banqueAccountId,
              "Capital Bank — Compte courant Espwa pou Ayiti (USD)",
              "020-765432-01"));
    } catch (ConflictException ex) {
      LOG.debug("V9 — BankAccount déjà existant — skip");
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec création BankAccount : {}", ex.getMessage());
    }
  }

  // ══Subventions (4 Grants) ══

  private List<GrantResponse> createDemoGrants(UUID companyId, List<ThirdPartyResponse> donors) {
    if (donors.size() < 4) {
      LOG.warn("V9 — Moins de 4 bailleurs créés ({}) — skip création grants", donors.size());
      return List.of();
    }
    // 4 subventions : 3 RESTRICTED + 1 UNRESTRICTED. Toutes en USD sur FY2025-2026.
    Object[][] defs = {
      // {donorIdx, code, label, totalAmount, restrictionType}
      {
        0,
        "GRANT-US-AID",
        "USAID Health Systems Strengthening 2025-2026",
        new BigDecimal("2500000"),
        RestrictionType.RESTRICTED
      },
      {
        1,
        "GRANT-EU-DEV",
        "EU Développement Communautaire Rural",
        new BigDecimal("1800000"),
        RestrictionType.RESTRICTED
      },
      {
        2,
        "GRANT-WB-INFRA",
        "World Bank Infrastructure WASH",
        new BigDecimal("1200000"),
        RestrictionType.RESTRICTED
      },
      {
        3,
        "GRANT-CRS-GEN",
        "CRS General Support — unrestricted",
        new BigDecimal("500000"),
        RestrictionType.UNRESTRICTED
      }
    };
    List<GrantResponse> created = new ArrayList<>(defs.length);
    for (Object[] d : defs) {
      int donorIdx = (int) d[0];
      String code = (String) d[1];
      try {
        GrantResponse grant =
            fundsGrantsService.createGrant(
                companyId,
                new CreateGrantRequest(
                    donors.get(donorIdx).id(),
                    code,
                    (String) d[2],
                    (BigDecimal) d[3],
                    FUNCTIONAL_CURRENCY, // currency USD
                    FY2526_START, // startDate 01/10/2025
                    FY2526_END, // endDate 30/09/2026
                    (RestrictionType) d[4],
                    null // analyticalValueId — pas de plan analytique au MVP
                    ));
        created.add(grant);
      } catch (ConflictException ex) {
        LOG.debug("V9 — Grant '{}' déjà existant — skip", code);
      } catch (RuntimeException ex) {
        LOG.warn("V9 — Échec création grant '{}' : {}", code, ex.getMessage());
      }
    }
    return created;
  }

  // ══12 mois d'opérations sur FY2025-2026 ══

  /**
   * Génère 12 mois d'opérations (Oct 2025 → Sep 2026). Chaque mois est isolé dans un try/catch : un
   * échec n'empêche pas les mois suivants de continuer.
   */
  private int generateMonthlyOperations(
      UUID companyId,
      List<ThirdPartyResponse> donors,
      List<ThirdPartyResponse> suppliers,
      List<EmployeeResponse> employees,
      List<GrantResponse> grants,
      AccountRefs refs) {
    if (donors.isEmpty() || suppliers.isEmpty() || employees.isEmpty() || grants.isEmpty()) {
      LOG.warn(
          "V9 — Données de base insuffisantes pour générer les opérations mensuelles "
              + "(donors={}, suppliers={}, employees={}, grants={}) — skip",
          donors.size(),
          suppliers.size(),
          employees.size(),
          grants.size());
      return 0;
    }

    int total = 0;
    LocalDate month = FY2526_START;
    int monthIdx = 0;
    while (!month.isAfter(FY2526_END)) {
      int monthCount = 0;
      try {
        // 1-3 DonationReceipt CASH par mois — reçus de donations cash des bailleurs
        int nCash = 1 + (monthIdx % 3); // 1, 2, 3, 1, 2, 3, ...
        for (int i = 0; i < nCash; i++) {
          if (createCashDonationReceipt(companyId, month, i, donors, grants)) {
            monthCount++;
          }
        }
        // 1-2 DonationReceipt IN_KIND par mois — médicaments, vivres, équipements
        int nInKind = 1 + (monthIdx % 2); // 1, 2, 1, 2, ...
        for (int i = 0; i < nInKind; i++) {
          if (createInKindDonationReceipt(companyId, month, i, donors, grants)) {
            monthCount++;
          }
        }
        // 2-3 Invoice/mois — achats pour programmes (TVA 0% VAT_EXEMPT_NGO)
        int nPur = 2 + (monthIdx % 2); // 2, 3, 2, 3, ...
        for (int i = 0; i < nPur; i++) {
          if (createPurchaseInvoice(companyId, month, i, suppliers)) {
            monthCount++;
          }
        }
        // 1-2 ExpenseReport/mois — frais de mission terrain
        int nExp = 1 + (monthIdx % 2); // 1, 2, 1, 2, ...
        for (int i = 0; i < nExp; i++) {
          if (createExpenseReport(companyId, month, i, employees)) {
            monthCount++;
          }
        }
        // 1 PayrollRun/mois — create + calculate (12% OFATMA) + approve
        if (createPayrollRun(companyId, month)) {
          monthCount++;
        }
        // 1 FxOperation par trimestre — conversion USD → HTG pour dépenses locales
        // Trimestres haïtiens : T1 = Oct-Dec, T2 = Jan-Mar, T3 = Apr-Jun, T4 = Jul-Sep
        // On déclenche sur les mois 0, 3, 6, 9 (octobre, janvier, avril, juillet)
        if (monthIdx % 3 == 0) {
          if (createFxOperation(companyId, month, monthIdx / 3, refs)) {
            monthCount++;
          }
        }
        LOG.info("V9 — Espwa pou Ayiti mois {} : {} opérations créées", month, monthCount);
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
   * Crée un reçu de don cash via {@link FundsGrantsService#createDonationReceipt} (génère
   * l'écriture comptable D 521 Banque / C 740 Subventions d'exploitation). Montant 50k-500k USD.
   */
  private boolean createCashDonationReceipt(
      UUID companyId,
      LocalDate month,
      int seq,
      List<ThirdPartyResponse> donors,
      List<GrantResponse> grants) {
    // Choisir un bailleur (et son grant) de façon déterministe
    int donorIdx = (month.getMonthValue() + seq) % donors.size();
    ThirdPartyResponse donor = donors.get(donorIdx);
    // Trouver le grant de ce bailleur (matching par donorThirdPartyId)
    GrantResponse grant =
        grants.stream()
            .filter(g -> g.donorThirdPartyId().equals(donor.id()))
            .findFirst()
            .orElse(grants.get(donorIdx % grants.size()));

    // Montant USD 50k-500k déterministe
    BigDecimal amount =
        new BigDecimal(50000 + ((seq + month.getMonthValue()) * 38571) % 450001)
            .setScale(2, RoundingMode.HALF_UP);
    LocalDate receiptDate = month.withDayOfMonth(Math.min(seq + 5, 28));
    try {
      fundsGrantsService.createDonationReceipt(
          companyId,
          new CreateDonationReceiptRequest(
              grant.id(),
              donor.id(),
              amount,
              receiptDate,
              "Don cash — " + donor.name() + " — programme " + grant.code()));
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Don cash déjà existant pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec don cash {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /**
   * Crée un reçu de don en nature (IN_KIND) — médicaments, vivres, équipements.
   *
   * <p>Le {@code CreateDonationReceiptRequest} n'exposant pas le champ {@code donationType}, on
   * crée le reçu via le service (qui génère une écriture cash D 521 / C 740 — limitation connue),
   * puis on patche l'entité via {@link DonationReceiptRepository} pour setter {@code
   * donationType=IN_KIND}. Le tag IN_KIND permet aux exports bailleurs (V8-5) de distinguer les
   * dons en nature des dons cash dans les rapports.
   *
   * <p>Montant 20k-150k USD (valorisation estimée des médicaments/vivres).
   */
  private boolean createInKindDonationReceipt(
      UUID companyId,
      LocalDate month,
      int seq,
      List<ThirdPartyResponse> donors,
      List<GrantResponse> grants) {
    int donorIdx = (month.getMonthValue() + seq + 1) % donors.size();
    ThirdPartyResponse donor = donors.get(donorIdx);
    GrantResponse grant =
        grants.stream()
            .filter(g -> g.donorThirdPartyId().equals(donor.id()))
            .findFirst()
            .orElse(grants.get(donorIdx % grants.size()));

    // Montant USD 20k-150k (valorisation estimée dons en nature)
    BigDecimal amount =
        new BigDecimal(20000 + ((seq + month.getMonthValue()) * 21439) % 130001)
            .setScale(2, RoundingMode.HALF_UP);
    LocalDate receiptDate = month.withDayOfMonth(Math.min(seq + 12, 27));

    // Description selon le type de don en nature (médicaments, vivres, équipements)
    String[] inKindTypes = {
      "médicaments et consommables médicaux",
      "vivres et denrées alimentaires",
      "équipements WASH (filtres, pompes)",
      "kits scolaires (cahiers, manuels)"
    };
    String description =
        "Don en nature — " + inKindTypes[seq % inKindTypes.length] + " — " + donor.name();

    try {
      DonationReceipt receipt =
          fundsGrantsService.createDonationReceipt(
              companyId,
              new CreateDonationReceiptRequest(
                  grant.id(), donor.id(), amount, receiptDate, description));
      // Patch post-création : setter donationType=IN_KIND sur l'entité persistée.
      // L'écriture comptable générée par le service reste celle d'un don cash (D 521 / C 740) —
      // limitation documentée car le DTO ne porte pas donationType. Le tag IN_KIND est toutefois
      // utile pour les exports bailleurs V8-5 (distinction cash vs nature dans les rapports).
      receipt.setDonationType(DonationType.IN_KIND);
      donationReceiptRepository.save(receipt);
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — Don en nature déjà existant pour {} seq={} — skip", month, seq);
    } catch (RuntimeException ex) {
      LOG.warn("V9 — Échec don en nature {} seq={} : {}", month, seq, ex.getMessage());
    }
    return false;
  }

  /**
   * Crée une facture d'achat pour programmes (médicaments, vivres, carburant) — TVA 0%
   * (VAT_EXEMPT_NGO). 1-3 lignes, montant 5k-80k USD. Le receive() génère l'écriture AC.
   */
  private boolean createPurchaseInvoice(
      UUID companyId, LocalDate month, int seq, List<ThirdPartyResponse> suppliers) {
    ThirdPartyResponse supplier = suppliers.get((month.getMonthValue() + seq) % suppliers.size());
    int nLines = 1 + (seq % 3); // 1, 2, 3
    List<CreateInvoiceRequest.LineDto> lines = new ArrayList<>(nLines);
    String[] itemLabels = {
      "Médicaments essentiels — kit santé",
      "Intrants agricoles — semences et engrais",
      "Carburant diesel — vehicles terrain",
      "Matériaux construction — ciment et fer",
      "Filtration eau — filtres communautaires",
      "Riz et légumineuses — distribution vivres",
      "Équipements médicaux — lits et brancards",
      "Fournitures bureau — papeterie et impression"
    };
    for (int i = 0; i < nLines; i++) {
      int qty = 5 + ((seq + i) * 13) % 50; // 5-54
      BigDecimal unitPrice =
          new BigDecimal(100 + ((seq + i) * 421) % 1500) // 100-1599 USD
              .setScale(2, RoundingMode.HALF_UP);
      String label = itemLabels[(seq + i) % itemLabels.length];
      lines.add(
          new CreateInvoiceRequest.LineDto(
              "Achat programme — " + label,
              new BigDecimal(qty),
              unitPrice,
              BigDecimal.ZERO, // discountPercent=0
              BigDecimal.ZERO, // taxRate=0
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
            FUNCTIONAL_CURRENCY, // USD
            lines, null);
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

  /**
   * Crée une note de frais (mission terrain) — submit + approve (écriture DP). Montants 100-2000
   * USD (salaires et déplacements en USD car ONG dollarisée).
   */
  private boolean createExpenseReport(
      UUID companyId, LocalDate month, int seq, List<EmployeeResponse> employees) {
    EmployeeResponse emp = employees.get((month.getMonthValue() + seq) % employees.size());
    int nLines = 1 + (seq % 2); // 1, 2
    String[][] cats = {
      {"TRAVEL", "Frais de mission terrain — déplacement zone intervention"},
      {"TRAVEL", "Transport carburant et taxi-moto"},
      {"SUPPLIES", "Fournitures de terrain — formulaires, stylos, stickers"},
      {"SUPPLIES", "Crédit téléphone et data terrain"},
      {"MEALS", "Repas mission — per diem équipe terrain"}
    };
    List<CreateExpenseReportRequest.LineDto> lines = new ArrayList<>(nLines);
    for (int i = 0; i < nLines; i++) {
      String[] cat = cats[(seq + i) % cats.length];
      BigDecimal amount =
          new BigDecimal(100 + ((seq + i) * 311) % 1901) // 100-2000 USD
              .setScale(2, RoundingMode.HALF_UP);
      lines.add(new CreateExpenseReportRequest.LineDto(cat[0], cat[1], amount, null));
    }
    LocalDate expDate = month.withDayOfMonth(Math.min(seq + 12, 26));
    CreateExpenseReportRequest req =
        new CreateExpenseReportRequest(
            emp.thirdPartyId(),
            expDate,
            FUNCTIONAL_CURRENCY, // USD
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
   * Crée une opération de change trimestrielle — conversion USD → HTG pour dépenses locales.
   *
   * <p>Étapes :
   *
   * <ol>
   * <li>Crée (ou met à jour) le taux de change USD→HTG pour la date d'opération via {@link
   * ExchangeRateService#createRate} (source BRH, valeur issue de {@link
   * ExchangeRateFixtures}).
   * <li>Crée une {@code FxOperation SELL USD→HTG} via {@link FxOperationsService#create} (vend
   * USD pour acheter HTG). Montant 5k-15k USD par trimestre.
   * </ol>
   *
   * <p>L'écriture comptable générée est D 521 HTG / C 521 USD (avec gain/perte de change nul car le
   * taux appliqué est identique au taux BRH enregistré).
   */
  private boolean createFxOperation(
      UUID companyId, LocalDate month, int quarterIdx, AccountRefs refs) {
    LocalDate opDate = month.withDayOfMonth(15); // mi-mois
    ExchangeRateFixtures.MonthlyRate rateFixture = ExchangeRateFixtures.get(opDate);
    if (rateFixture == null) {
      LOG.warn("V9 — Taux USD/HTG introuvable dans ExchangeRateFixtures pour {} — skip FX", opDate);
      return false;
    }
    BigDecimal htgPerUsd = rateFixture.htgPerUsd();

    // 1. Créer le taux de change USD → HTG (idempotent — ExchangeRateService ne lève pas
    // ConflictException mais crée simplement un nouveau snapshot à chaque appel).
    try {
      exchangeRateService.createRate(companyId, "USD", "HTG", htgPerUsd, opDate, "BRH");
    } catch (RuntimeException ex) {
      LOG.debug("V9 — Taux USD/HTG déjà créé pour {} — continu ({});", opDate, ex.getMessage());
    }

    // 2. Créer l'opération SELL USD → HTG
    // Montant USD vendu : 5000 + 2500 * quarterIdx (5k T1, 7.5k T2, 10k T3, 12.5k T4)
    BigDecimal usdAmount =
        new BigDecimal("5000").add(new BigDecimal("2500").multiply(BigDecimal.valueOf(quarterIdx)));
    BigDecimal htgAmount =
        usdAmount
            .multiply(htgPerUsd)
            .setScale(2, RoundingMode.HALF_UP); // toAmount = fromAmount × rate

    CreateFxOperationRequest req =
        new CreateFxOperationRequest(
            FxOperationType.SELL, // on vend USD pour acheter HTG
            "USD", // fromCurrency
            "HTG", // toCurrency
            usdAmount, // fromAmount
            htgAmount, // toAmount
            htgPerUsd, // rate (1 USD = htgPerUsd HTG)
            opDate,
            "Conversion trimestrielle USD → HTG pour dépenses locales (T"
                + (quarterIdx + 1)
                + " FY2025-2026)",
            refs.banqueAccountId // bankAccountId — compte 521000
            );
    try {
      fxOperationsService.create(companyId, req);
      return true;
    } catch (ConflictException ex) {
      LOG.debug("V9 — FxOperation déjà existante pour T{} — skip", quarterIdx + 1);
    } catch (RuntimeException ex) {
      LOG.warn(
          "V9 — Échec FxOperation T{} ({} USD→HTG @ {}) : {}",
          quarterIdx + 1,
          usdAmount,
          htgPerUsd,
          ex.getMessage());
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

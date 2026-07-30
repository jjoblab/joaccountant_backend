package jo.accountant.payroll.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.payroll.dto.CreatePayrollRunRequest;
import jo.accountant.payroll.dto.PayrollRunResponse;
import jo.accountant.payroll.dto.PayslipResponse;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunStatus;
import jo.accountant.payroll.entity.Payslip;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.payroll.repository.PayslipRepository;
import jo.accountant.tax.entity.WithholdingRule;
import jo.accountant.tax.repository.WithholdingRuleRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de paie (restructuration 2026-07-24 — module :payroll).
 *
 * <p>Cycle de vie : DRAFT (créé pour une période, un Payslip par employé ACTIVE) →
 * {@code calculate()} (calcule brut→net par employé via :tax WithholdingRule applicables
 * aux EMPLOYEE) → CALCULATED → {@code approve()} (génère l'écriture consolidée —
 * Débit Charges de personnel / Crédit Salaires à payer / Crédit Organismes sociaux /
 * Crédit État) → APPROVED → {@code pay()} (paiement effectif — marquage manuel au MVP,
 * pas de génération de fichier de virement) → PAID → {@code close()} → CLOSED.
 *
 * <p><b>Calcul brut→net</b> : pour chaque employé, on applique les `WithholdingRule`
 * dont `applicableThirdPartyTypes` contient `"EMPLOYEE"`. Le calcul est simple au MVP :
 * `deduction = grossSalary × rate / 100`. Le net = `grossSalary - sum(deductions)`.
 * Les charges patronales sont un taux global configurable sur la campagne
 * (`employerContributionRate`) — pas de détail par cotisation (URSS, OFATMA, etc.) au MVP.
 *
 * <p><b>Approbation délègue à `JOURNAL_ENTRY_POST`</b> (§2.4 du prompt — choix de
 * cohérence avec §2.2). La transition APPROVED → génération d'écriture se fait en une
 * seule étape côté service.
 *
 * <p><b>Résolution des comptes référentiel-agnostique</b> (calquée sur audit B4) :
 * <ul>
 *   <li><b>Charges de personnel</b> (Débit) : `CHARGES + taxMappingCode="PERSONNEL_EXPENSE"`
 *       → `CHARGES` actif quelconque → fallback SYSCOHADA `"661000"/"661"`.</li>
 *   <li><b>Salaires à payer</b> (Crédit) : `PASSIF + taxMappingCode="SALARIES_PAYABLE"`
 *       → fallback SYSCOHADA `"422000"/"422"`.</li>
 *   <li><b>Organismes sociaux à payer</b> (Crédit) : `PASSIF + taxMappingCode="SOCIAL_SECURITY_PAYABLE"`
 *       → fallback SYSCOHADA `"433000"/"433"`.</li>
 *   <li><b>État — retenues fiscales</b> (Crédit) : `PASSIF + taxMappingCode="VAT_COLLECTED"`
 *       (réutilisé par convention) → fallback SYSCOHADA `"443000"/"443"`.
 *       Si le total des retenues fiscales est 0, cette ligne est omise.</li>
 * </ul>
 *
 * <p><b>Code journal `PA` (paie)</b> — doit exister (sinon `422 JOURNAL_PA_NOT_FOUND`).
 */
@Service
public class PayrollService {

    private static final Logger LOG = LoggerFactory.getLogger(PayrollService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PayrollRunRepository runRepository;
    private final PayslipRepository payslipRepository;
    private final EmployeeRepository employeeRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final WithholdingRuleRepository withholdingRuleRepository;
    private final jo.accountant.tax.repository.ContributionRuleRepository contributionRuleRepository;  // Audit v4.7 §4.1 #3
    private final AccountRepository accountRepository;
    private final JournalRepository journalRepository;
    private final DocumentNumberingService documentNumberingService;
    private final AccountingEngineService accountingEngineService;
    private final DocumentGenerationService documentGenerationService;
    private final ObjectMapper objectMapper;
    private final PayrollCalculator payrollCalculator;  // Audit v4.7 §4.1 #3
    // Lot B R-20 — repository Company pour lire monthlyLegalHours (173.33 France / 208 Haïti).
    private final jo.accountant.company.repository.CompanyRepository companyRepository;
    // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
    private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;
    // V8-7 — Runner asynchrone pour 13e mois (Spring Batch thirteenthMonthJob est aussi dispo côté :app)
    private final ThirteenthMonthAsyncRunner thirteenthMonthAsyncRunner;

    public PayrollService(PayrollRunRepository runRepository,
                           PayslipRepository payslipRepository,
                           EmployeeRepository employeeRepository,
                           ThirdPartyRepository thirdPartyRepository,
                           WithholdingRuleRepository withholdingRuleRepository,
                           jo.accountant.tax.repository.ContributionRuleRepository contributionRuleRepository,
                           AccountRepository accountRepository,
                           JournalRepository journalRepository,
                           DocumentNumberingService documentNumberingService,
                           AccountingEngineService accountingEngineService,
                           DocumentGenerationService documentGenerationService,
                           ObjectMapper objectMapper,
                           PayrollCalculator payrollCalculator,
                           jo.accountant.company.repository.CompanyRepository companyRepository,
                           jo.accountant.chartofaccounts.service.AccountResolver accountResolver,
                           ThirteenthMonthAsyncRunner thirteenthMonthAsyncRunner) {
        this.runRepository = runRepository;
        this.payslipRepository = payslipRepository;
        this.employeeRepository = employeeRepository;
        this.thirdPartyRepository = thirdPartyRepository;
        this.withholdingRuleRepository = withholdingRuleRepository;
        this.contributionRuleRepository = contributionRuleRepository;
        this.accountRepository = accountRepository;
        this.journalRepository = journalRepository;
        this.documentNumberingService = documentNumberingService;
        this.accountingEngineService = accountingEngineService;
        this.documentGenerationService = documentGenerationService;
        this.objectMapper = objectMapper;
        this.payrollCalculator = payrollCalculator;
        this.companyRepository = companyRepository;
        this.accountResolver = accountResolver;
        this.thirteenthMonthAsyncRunner = thirteenthMonthAsyncRunner;
    }

    // --- Création ---

    @Transactional
    public PayrollRunResponse create(UUID companyId, CreatePayrollRunRequest req) {
        // Une seule campagne par période (mois/année) par entreprise.
        if (runRepository.findByCompanyIdAndPeriodYearAndPeriodMonth(
                companyId, req.periodYear(), req.periodMonth()).isPresent()) {
            throw new ConflictException("PAYROLL_RUN_ALREADY_EXISTS",
                "Une campagne de paie existe déjà pour " + req.periodMonth() + "/" + req.periodYear());
        }

        PayrollRun run = new PayrollRun();
        run.setCompanyId(companyId);
        run.setPeriodMonth(req.periodMonth());
        run.setPeriodYear(req.periodYear());
        run.setStatus(PayrollRunStatus.DRAFT);
        run.setTotalGross(BigDecimal.ZERO);
        run.setTotalNet(BigDecimal.ZERO);
        run.setTotalEmployerContributions(BigDecimal.ZERO);
        PayrollRun saved = runRepository.save(run);
        LOG.info("Campagne de paie créée : id={} période={}/{}", saved.getId(),
            saved.getPeriodMonth(), saved.getPeriodYear());
        return loadRunResponse(companyId, saved.getId());
    }

    // --- Calcul (DRAFT → CALCULATED) ---

    @Transactional
    public PayrollRunResponse calculate(UUID companyId, UUID runId) {
        PayrollRun run = loadRun(companyId, runId);
        if (run.getStatus() != PayrollRunStatus.DRAFT) {
            throw new ConflictException("PAYROLL_RUN_NOT_DRAFT",
                "Seules les campagnes DRAFT peuvent être calculées. Statut : " + run.getStatus());
        }

        // Charger les règles de retenue applicables aux EMPLOYEE
        List<WithholdingRule> employeeRules = loadActiveEmployeeRules(companyId);

        // Lister les employés ACTIVE
        List<Employee> activeEmployees = employeeRepository
            .findByCompanyIdAndStatusOrderByIdAsc(companyId, EmployeeStatus.ACTIVE);
        if (activeEmployees.isEmpty()) {
            throw new ValidationException("NO_ACTIVE_EMPLOYEES",
                "Aucun employé ACTIVE dans l'entreprise — la campagne de paie serait vide.");
        }

        // Préalable : nettoyer d'éventuels payslips d'un calcul précédent (si on est passé
        // CALCULATED → DRAFT via un reset — pas implémenté au MVP, mais on nettoie quand même
        // par sécurité).
        List<Payslip> existing = payslipRepository.findByRunIdOrderByCreatedAt(run.getId());
        if (!existing.isEmpty()) {
            payslipRepository.deleteAll(existing);
        }

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalEmployerContributions = BigDecimal.ZERO;

        // Audit v4.7 §4.1 Finding #3 — FIX CRITIQUE : utiliser ContributionRule (moteur par tranches)
        // si l'entreprise en a configuré, sinon fallback sur WithholdingRule (ancien calcul simpliste).
        List<jo.accountant.tax.entity.ContributionRule> contributionRules =
            contributionRuleRepository.findByCompanyIdAndActiveTrue(companyId);
        boolean useNewEngine = !contributionRules.isEmpty();
        if (useNewEngine) {
            LOG.info("PayrollService : utilisation du nouveau moteur PayrollCalculator ({} règles ContributionRule)",
                contributionRules.size());
        } else {
            LOG.info("PayrollService : fallback sur l'ancien moteur WithholdingRule (aucune ContributionRule configurée)");
        }

        for (Employee emp : activeEmployees) {
            BigDecimal gross = emp.getBaseSalary();

            if (useNewEngine) {
                // ── Nouveau moteur : PayrollCalculator par tranches (PMSS, CSG abattue, Tranche A/B) ──
                // Finding #18 — surcharge calculant le brut à partir de la fiche employé (prorata + HS).
                // Lot B R-20 — passer monthlyLegalHours de la Company (173.33 France / 208 Haïti).
                PayrollCalculator.PayrollCalculationResult result =
                    payrollCalculator.calculate(companyId, emp.getId(), emp,
                        resolveMonthlyLegalHours(companyId), contributionRules);
                BigDecimal computedGross = result.grossSalary();

                // Sérialiser les cotisations employé + employeur en JSON pour le payslip
                List<Map<String, Object>> deductions = result.employeeContributions().stream()
                    .map(c -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("code", c.code());
                        m.put("label", c.label());
                        m.put("rate", c.rate());
                        m.put("base", c.base());
                        m.put("baseType", c.baseType());
                        m.put("amount", c.amount());
                        m.put("party", c.party());
                        return m;
                    }).collect(java.util.stream.Collectors.toList());
                List<Map<String, Object>> employerContributionList = result.employerContributions().stream()
                    .map(c -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("code", c.code());
                        m.put("label", c.label());
                        m.put("rate", c.rate());
                        m.put("base", c.base());
                        m.put("baseType", c.baseType());
                        m.put("amount", c.amount());
                        m.put("party", c.party());
                        return m;
                    }).collect(java.util.stream.Collectors.toList());

                Payslip payslip = new Payslip();
                payslip.setCompanyId(companyId);
                payslip.setRunId(run.getId());
                payslip.setEmployeeId(emp.getId());
                // Finding #18 — utiliser le brut calculé (prorata + HS) plutôt que le baseSalary brut.
                payslip.setGrossSalary(computedGross);
                payslip.setDeductions(toJson(deductions));
                payslip.setEmployerContributions(toJson(employerContributionList));
                payslip.setNetPay(result.netSalary());
                payslipRepository.save(payslip);

                totalGross = totalGross.add(computedGross);
                totalNet = totalNet.add(result.netSalary());
                totalEmployerContributions = totalEmployerContributions.add(result.totalEmployerContributions());
            } else {
                // ── Ancien moteur : WithholdingRule (rétro-compat pour entreprises sans ContributionRule) ──
                List<Map<String, Object>> deductions = new ArrayList<>();
                BigDecimal totalDeductions = BigDecimal.ZERO;

                for (WithholdingRule rule : employeeRules) {
                    BigDecimal amount = gross.multiply(rule.getRate()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                    Map<String, Object> ded = new HashMap<>();
                    ded.put("code", rule.getCode());
                    ded.put("label", rule.getLabel());
                    ded.put("rate", rule.getRate());
                    ded.put("amount", amount);
                    deductions.add(ded);
                    totalDeductions = totalDeductions.add(amount);
                }

                BigDecimal employerContributionsAmount = BigDecimal.ZERO;
                BigDecimal net = gross.subtract(totalDeductions);

                Payslip payslip = new Payslip();
                payslip.setCompanyId(companyId);
                payslip.setRunId(run.getId());
                payslip.setEmployeeId(emp.getId());
                payslip.setGrossSalary(gross);
                payslip.setDeductions(toJson(deductions));
                payslip.setEmployerContributions(toJson(List.of()));
                payslip.setNetPay(net);
                payslipRepository.save(payslip);

                totalGross = totalGross.add(gross);
                totalNet = totalNet.add(net);
                totalEmployerContributions = totalEmployerContributions.add(employerContributionsAmount);
            }
        }

        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        run.setTotalEmployerContributions(totalEmployerContributions);
        run.setStatus(PayrollRunStatus.CALCULATED);
        runRepository.save(run);

        LOG.info("Campagne calculée : id={} employés={} brut={} net={}",
            run.getId(), activeEmployees.size(), totalGross, totalNet);
        return loadRunResponse(companyId, run.getId());
    }

    /**
     * Surcharge qui accepte un taux de charges patronales explicite. C'est la variante
     * recommandée — le taux n'est pas persisté sur la run (au MVP) mais passé à chaque
     * appel de calculate.
     */
    @Transactional
    public PayrollRunResponse calculate(UUID companyId, UUID runId, BigDecimal employerContributionRate) {
        PayrollRun run = loadRun(companyId, runId);
        if (run.getStatus() != PayrollRunStatus.DRAFT) {
            throw new ConflictException("PAYROLL_RUN_NOT_DRAFT",
                "Seules les campagnes DRAFT peuvent être calculées. Statut : " + run.getStatus());
        }

        List<WithholdingRule> employeeRules = loadActiveEmployeeRules(companyId);
        List<Employee> activeEmployees = employeeRepository
            .findByCompanyIdAndStatusOrderByIdAsc(companyId, EmployeeStatus.ACTIVE);
        if (activeEmployees.isEmpty()) {
            throw new ValidationException("NO_ACTIVE_EMPLOYEES",
                "Aucun employé ACTIVE dans l'entreprise — la campagne de paie serait vide.");
        }

        List<Payslip> existing = payslipRepository.findByRunIdOrderByCreatedAt(run.getId());
        if (!existing.isEmpty()) {
            payslipRepository.deleteAll(existing);
        }

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalEmployerContributions = BigDecimal.ZERO;

        // Audit v4.7 §4.1 Finding #3 — FIX CRITIQUE : utiliser ContributionRule (moteur par tranches)
        // si l'entreprise en a configuré, sinon fallback sur WithholdingRule + taux patronal paramétré.
        List<jo.accountant.tax.entity.ContributionRule> contributionRules =
            contributionRuleRepository.findByCompanyIdAndActiveTrue(companyId);
        boolean useNewEngine = !contributionRules.isEmpty();
        if (useNewEngine) {
            LOG.info("PayrollService (surcharge avec taux patronal) : utilisation du nouveau moteur PayrollCalculator "
                + "({} règles ContributionRule — le taux patronal paramétré {} est ignoré)",
                contributionRules.size(), employerContributionRate);
        }

        for (Employee emp : activeEmployees) {
            BigDecimal gross = emp.getBaseSalary();

            if (useNewEngine) {
                // Finding #18 — surcharge calculant le brut à partir de la fiche employé (prorata + HS).
                // Lot B R-20 — passer monthlyLegalHours de la Company (173.33 France / 208 Haïti).
                PayrollCalculator.PayrollCalculationResult result =
                    payrollCalculator.calculate(companyId, emp.getId(), emp,
                        resolveMonthlyLegalHours(companyId), contributionRules);
                BigDecimal computedGross = result.grossSalary();

                List<Map<String, Object>> deductions = result.employeeContributions().stream()
                    .map(c -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("code", c.code());
                        m.put("label", c.label());
                        m.put("rate", c.rate());
                        m.put("base", c.base());
                        m.put("amount", c.amount());
                        m.put("party", c.party());
                        return m;
                    }).collect(java.util.stream.Collectors.toList());
                List<Map<String, Object>> employerContributionList = result.employerContributions().stream()
                    .map(c -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("code", c.code());
                        m.put("label", c.label());
                        m.put("rate", c.rate());
                        m.put("base", c.base());
                        m.put("amount", c.amount());
                        m.put("party", c.party());
                        return m;
                    }).collect(java.util.stream.Collectors.toList());

                Payslip payslip = new Payslip();
                payslip.setCompanyId(companyId);
                payslip.setRunId(run.getId());
                payslip.setEmployeeId(emp.getId());
                // Finding #18 — utiliser le brut calculé (prorata + HS) plutôt que le baseSalary brut.
                payslip.setGrossSalary(computedGross);
                payslip.setDeductions(toJson(deductions));
                payslip.setEmployerContributions(toJson(employerContributionList));
                payslip.setNetPay(result.netSalary());
                payslipRepository.save(payslip);

                totalGross = totalGross.add(computedGross);
                totalNet = totalNet.add(result.netSalary());
                totalEmployerContributions = totalEmployerContributions.add(result.totalEmployerContributions());
            } else {
                // Ancien moteur : WithholdingRule + taux patronal paramétré (rétro-compat)
                List<Map<String, Object>> deductions = new ArrayList<>();
                BigDecimal totalDeductions = BigDecimal.ZERO;

                for (WithholdingRule rule : employeeRules) {
                    BigDecimal amount = gross.multiply(rule.getRate()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                    Map<String, Object> ded = new HashMap<>();
                    ded.put("code", rule.getCode());
                    ded.put("label", rule.getLabel());
                    ded.put("rate", rule.getRate());
                    ded.put("amount", amount);
                    deductions.add(ded);
                    totalDeductions = totalDeductions.add(amount);
                }

                BigDecimal employerAmount = gross.multiply(employerContributionRate)
                    .divide(HUNDRED, 4, RoundingMode.HALF_UP);
                Map<String, Object> erContrib = new HashMap<>();
                erContrib.put("code", "EMPLOYER_CONTRIBUTIONS");
                erContrib.put("label", "Charges patronales (" + employerContributionRate + "%)");
                erContrib.put("rate", employerContributionRate);
                erContrib.put("amount", employerAmount);

                BigDecimal net = gross.subtract(totalDeductions);

                Payslip payslip = new Payslip();
                payslip.setCompanyId(companyId);
                payslip.setRunId(run.getId());
                payslip.setEmployeeId(emp.getId());
                payslip.setGrossSalary(gross);
                payslip.setDeductions(toJson(deductions));
                payslip.setEmployerContributions(toJson(List.of(erContrib)));
                payslip.setNetPay(net);
                payslipRepository.save(payslip);

                totalGross = totalGross.add(gross);
                totalNet = totalNet.add(net);
                totalEmployerContributions = totalEmployerContributions.add(employerAmount);
            }
        }

        run.setTotalGross(totalGross);
        run.setTotalNet(totalNet);
        run.setTotalEmployerContributions(totalEmployerContributions);
        run.setStatus(PayrollRunStatus.CALCULATED);
        runRepository.save(run);

        LOG.info("Campagne calculée (avec taux patronal {}%) : id={} employés={} brut={} net={} patronal={}",
            employerContributionRate, run.getId(), activeEmployees.size(), totalGross, totalNet,
            totalEmployerContributions);
        return loadRunResponse(companyId, run.getId());
    }

    // --- Approbation (CALCULATED → APPROVED, génère l'écriture) ---

    @Transactional
    public PayrollRunResponse approve(UUID companyId, UUID runId) {
        PayrollRun run = loadRun(companyId, runId);
        if (run.getStatus() != PayrollRunStatus.CALCULATED) {
            throw new ConflictException("PAYROLL_RUN_NOT_CALCULATED",
                "Seules les campagnes CALCULATED peuvent être approuvées. Statut : " + run.getStatus());
        }

        generatePayrollEntry(companyId, run);
        run.setStatus(PayrollRunStatus.APPROVED);
        runRepository.save(run);

        LOG.info("Campagne approuvée : id={} entry={}", run.getId(), run.getJournalEntryId());
        return loadRunResponse(companyId, run.getId());
    }

    /**
     * Génère l'écriture comptable consolidée de paie.
     *
     * <p>Débit : Charges de personnel [brut + charges patronales].
     * Crédit : Salaires à payer [net] — par employé (thirdPartyId).
     * Crédit : Organismes sociaux à payer [charges patronales].
     * Crédit : État — retenues fiscales [somme des retenues des payslips].
     */
    private void generatePayrollEntry(UUID companyId, PayrollRun run) {
        List<Payslip> payslips = payslipRepository.findByRunIdOrderByCreatedAt(run.getId());
        if (payslips.isEmpty()) {
            throw new ValidationException("PAYROLL_RUN_EMPTY",
                "La campagne " + run.getId() + " n'a aucun payslip. Calculer d'abord.");
        }

        // Compte de charges de personnel — résolution référentiel-agnostique via AccountResolver (audit #3)
        Account personnelAccount = accountResolver.resolveOrThrow(
            companyId, ReportingClass.CHARGES, "PERSONNEL_EXPENSE",
            "PERSONNEL_ACCOUNT_NOT_FOUND",
            "Aucun compte de charges de personnel trouvé. Configurer un compte CHARGES " +
            "(idéalement marqué taxMappingCode=\"PERSONNEL_EXPENSE\") dans le plan comptable.",
            "661000", "661");

        // Compte de salaires à payer — résolution référentiel-agnostique via AccountResolver (audit #3)
        Account salariesPayableAccount = accountResolver.resolveOrThrow(
            companyId, ReportingClass.PASSIF, "SALARIES_PAYABLE",
            "SALARIES_PAYABLE_ACCOUNT_NOT_FOUND",
            "Aucun compte de salaires à payer trouvé. Configurer un compte PASSIF " +
            "marqué taxMappingCode=\"SALARIES_PAYABLE\" dans le plan comptable.",
            "422000", "422");

        Account socialSecurityAccount = null;
        if (run.getTotalEmployerContributions().compareTo(BigDecimal.ZERO) > 0) {
            // Compte d'organismes sociaux — résolution référentiel-agnostique via AccountResolver (audit #3)
            socialSecurityAccount = accountResolver.resolveOrThrow(
                companyId, ReportingClass.PASSIF, "SOCIAL_SECURITY_PAYABLE",
                "SOCIAL_SECURITY_ACCOUNT_NOT_FOUND",
                "Aucun compte d'organismes sociaux à payer trouvé. Configurer un compte " +
                "PASSIF marqué taxMappingCode=\"SOCIAL_SECURITY_PAYABLE\".",
                "433000", "433");
        }

        // État — retenues fiscales (audit v4.7 §4.2 Finding MOYENNE — FIX : ne plus réutiliser
        // VAT_COLLECTED qui mélange natures TVA + retenues salariales. Désormais on cherche un
        // compte dédié taxMappingCode="PAYROLL_TAX_PAYABLE" (442 en PCG = "Etat, impôts et taxes
        // à payer"), fallback 442000 puis 442, puis 443000/443 pour rétro-compat SYSCOHADA).
        BigDecimal totalTaxDeductions = BigDecimal.ZERO;
        for (Payslip ps : payslips) {
            List<Map<String, Object>> deductions = fromJson(ps.getDeductions());
            for (Map<String, Object> ded : deductions) {
                BigDecimal amount = new BigDecimal(ded.get("amount").toString());
                totalTaxDeductions = totalTaxDeductions.add(amount);
            }
        }

        Account stateAccount = null;
        if (totalTaxDeductions.compareTo(BigDecimal.ZERO) > 0) {
            // Compte d'État pour retenues fiscales — résolution référentiel-agnostique via AccountResolver (audit #3)
            stateAccount = accountResolver.resolveOrThrow(
                companyId, ReportingClass.PASSIF, "PAYROLL_TAX_PAYABLE",
                "STATE_TAX_ACCOUNT_NOT_FOUND",
                "Aucun compte d'État pour retenues fiscales trouvé. Configurer un compte " +
                "PASSIF marqué taxMappingCode=\"PAYROLL_TAX_PAYABLE\" (442 en PCG = État, " +
                "impôts à payer). Ne PAS utiliser VAT_COLLECTED (443) qui mélange TVA et " +
                "retenues salariales — audit v4.7 §4.2.",
                "442000", "442", "443000", "443");
        }

        // V8.2 Phase 3 — getOrCreateJournal retourne le journal existant ou le crée avec
        // le code/label par défaut du type (jamais d'exception pour les types standards).
        String journalCode = accountingEngineService.getOrCreateJournal(companyId,
            jo.accountant.accountingengine.entity.JournalType.PAIE).getCode();

        // Date d'écriture = dernier jour du mois de la période
        LocalDate entryDate = LocalDate.of(run.getPeriodYear(), run.getPeriodMonth(), 1)
            .plusMonths(1).minusDays(1);

        List<LineDto> lines = new ArrayList<>();
        BigDecimal totalDebit = run.getTotalGross().add(run.getTotalEmployerContributions());
        // Débit Charges de personnel (total brut + charges patronales)
        lines.add(new LineDto(personnelAccount.getCode(), null,
            totalDebit, null,
            "Paie " + run.getPeriodMonth() + "/" + run.getPeriodYear() + " — Brut + charges patronales",
            List.of()));

        // Crédit Salaires à payer — par employé (avec thirdPartyId pour lettrage futur)
        for (Payslip ps : payslips) {
            Employee emp = employeeRepository.findById(ps.getEmployeeId())
                .orElseThrow(() -> new ValidationException("EMPLOYEE_NOT_FOUND",
                    "Employé introuvable : " + ps.getEmployeeId()));
            // Audit v4.7 §6.2 — defense-in-depth
            if (!emp.getCompanyId().equals(companyId)) {
                throw new NotFoundException("Employee", ps.getEmployeeId().toString());
            }
            // Audit v4.7 §6.2 — defense-in-depth : filtrer par companyId
            ThirdParty tp = thirdPartyRepository.findById(emp.getThirdPartyId())
                .filter(t -> t.getCompanyId().equals(companyId))
                .orElse(null);
            UUID tpId = tp != null ? tp.getId() : null;
            lines.add(new LineDto(salariesPayableAccount.getCode(), tpId,
                null, ps.getNetPay(),
                "Salaire net — " + (tp != null ? tp.getName() : emp.getEmployeeNumber()),
                List.of()));
        }

        // Crédit Organismes sociaux (charges patronales)
        if (socialSecurityAccount != null) {
            lines.add(new LineDto(socialSecurityAccount.getCode(), null,
                null, run.getTotalEmployerContributions(),
                "Charges patronales — " + run.getPeriodMonth() + "/" + run.getPeriodYear(),
                List.of()));
        }

        // Crédit État (retenues fiscales)
        if (stateAccount != null) {
            lines.add(new LineDto(stateAccount.getCode(), null,
                null, totalTaxDeductions,
                "Retenues fiscales salariales — " + run.getPeriodMonth() + "/" + run.getPeriodYear(),
                List.of()));
        }

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, entryDate,
            "Paie " + run.getPeriodMonth() + "/" + run.getPeriodYear(),
            lines, JournalEntrySourceModule.PAYROLL);

        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, "payroll-" + run.getId(), entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        run.setJournalEntryId(posted.id());

        // Attribuer les numéros de bulletin via :document-numbering
        for (Payslip ps : payslips) {
            IssuedNumber issued = documentNumberingService.nextNumber(
                companyId, DocumentType.PAYSLIP, "PA",
                entryDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            ps.setPayslipNumber(issued.number());
            payslipRepository.save(ps);
        }
    }

    // --- Paiement (APPROVED → PAID) ---

    @Transactional
    public PayrollRunResponse pay(UUID companyId, UUID runId) {
        PayrollRun run = loadRun(companyId, runId);
        if (run.getStatus() != PayrollRunStatus.APPROVED) {
            throw new ConflictException("PAYROLL_RUN_NOT_APPROVED",
                "Seules les campagnes APPROVED peuvent être payées. Statut : " + run.getStatus());
        }
        run.setStatus(PayrollRunStatus.PAID);
        runRepository.save(run);
        LOG.info("Campagne marquée PAID : id={}", run.getId());
        return loadRunResponse(companyId, run.getId());
    }

    // --- Clôture (PAID → CLOSED) ---

    @Transactional
    public PayrollRunResponse close(UUID companyId, UUID runId) {
        PayrollRun run = loadRun(companyId, runId);
        if (run.getStatus() != PayrollRunStatus.PAID) {
            throw new ConflictException("PAYROLL_RUN_NOT_PAID",
                "Seules les campagnes PAID peuvent être clôturées. Statut : " + run.getStatus());
        }
        run.setStatus(PayrollRunStatus.CLOSED);
        runRepository.save(run);
        LOG.info("Campagne clôturée : id={}", run.getId());
        return loadRunResponse(companyId, run.getId());
    }

    // --- Lecture ---

    /**
     * Liste les campagnes de paie d'une entreprise, triées par {@code periodYear} DESC puis
     * {@code periodMonth} DESC.
     *
     * <p>Restructuration 2026-07-25 (suite 4) : la paie n'est pas rattachée à un exercice fiscal
     * (les campagnes sont identifiées par année+mois, indépendamment des exercices). Pour éviter
     * de retourner la totalité de l'historique sur une entreprise avec plusieurs années de paie,
     * l'endpoint applique un défaut de <strong>12 campagnes maximum</strong> lorsque
     * {@code limit} est {@code null}. Le client peut explicitement demander plus (ou moins) via
     * le paramètre {@code limit} ; une valeur {@code <= 0} est interprétée comme « défaut 12 »
     * (pour réinitialiser le défaut explicitement, passer simplement {@code null}).
     *
     * @param companyId identifiant du tenant
     * @param limit nombre maximum de campagnes à retourner (12 par défaut si {@code null} ou
     *              {@code <= 0})
     */
    @Transactional(readOnly = true)
    public List<PayrollRunResponse> listRuns(UUID companyId, Integer limit) {
        List<PayrollRun> all = runRepository
            .findByCompanyIdOrderByPeriodYearDescPeriodMonthDesc(companyId);
        int cap = (limit != null && limit > 0) ? limit : 12;
        if (all.size() > cap) {
            all = all.subList(0, cap);
        }
        return all.stream()
            .map(r -> loadRunResponse(companyId, r.getId()))
            .toList();
    }

    /**
     * @deprecated Utiliser {@link #listRuns(UUID, Integer)} à la place. Conservé pour
     * rétro-compatibilité — délègue avec un défaut de 12 campagnes (restructuration 2026-07-25
     * suite 4).
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<PayrollRunResponse> listRuns(UUID companyId) {
        return listRuns(companyId, 12);
    }

    @Transactional(readOnly = true)
    public PayrollRunResponse getRun(UUID companyId, UUID runId) {
        return loadRunResponse(companyId, runId);
    }

    @Transactional(readOnly = true)
    public List<PayslipResponse> listPayslips(UUID companyId, UUID runId) {
        // Valider que la run appartient à l'entreprise
        loadRun(companyId, runId);
        return payslipRepository.findByRunIdOrderByCreatedAt(runId).stream()
            .map(ps -> loadPayslipResponse(companyId, ps))
            .toList();
    }

    @Transactional
    public byte[] getPayslipPdf(UUID companyId, UUID payslipId) {
        Payslip ps = payslipRepository.findById(payslipId)
            .orElseThrow(() -> new NotFoundException("Payslip", payslipId));
        if (!ps.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Payslip", payslipId);
        }
        Employee emp = employeeRepository.findById(ps.getEmployeeId())
            .orElseThrow(() -> new NotFoundException("Employee", ps.getEmployeeId()));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!emp.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Employee", ps.getEmployeeId());
        }
        // Audit v4.7 §6.2 — defense-in-depth : filtrer par companyId
        ThirdParty tp = thirdPartyRepository.findById(emp.getThirdPartyId())
            .filter(t -> t.getCompanyId().equals(companyId))
            .orElse(null);

        Map<String, Object> variables = new HashMap<>();
        variables.put("payslipNumber", ps.getPayslipNumber() != null ? ps.getPayslipNumber() : "");
        variables.put("employeeName", tp != null ? tp.getName() : emp.getEmployeeNumber());
        variables.put("employeeNumber", emp.getEmployeeNumber());
        variables.put("period", ps.getCreatedAt() != null
            ? ps.getCreatedAt().toString().substring(0, 7) : "");
        variables.put("grossSalary", ps.getGrossSalary().toString());
        variables.put("netPay", ps.getNetPay().toString());
        variables.put("deductions", fromJson(ps.getDeductions()));
        variables.put("employerContributions", fromJson(ps.getEmployerContributions()));

        documentGenerationService.generateDocument(
            companyId,
            jo.accountant.documentgeneration.entity.DocumentType.PAYSLIP,
            ps.getId(),
            variables);
        return documentGenerationService.getDocumentContent(companyId, ps.getId());
    }

    // --- Helpers ---

    private PayrollRunResponse loadRunResponse(UUID companyId, UUID runId) {
        PayrollRun run = loadRun(companyId, runId);
        int payslipCount = payslipRepository.findByRunIdOrderByCreatedAt(run.getId()).size();
        return new PayrollRunResponse(
            run.getId(), run.getCompanyId(), run.getPeriodMonth(), run.getPeriodYear(),
            run.getStatus(), run.getTotalGross(), run.getTotalNet(),
            run.getTotalEmployerContributions(), run.getJournalEntryId(),
            payslipCount, run.getCreatedAt(), run.getUpdatedAt(), run.getRunType());
    }

    private PayslipResponse loadPayslipResponse(UUID companyId, Payslip ps) {
        // Audit v4.7 §6.2 — defense-in-depth : filtrer Employee et ThirdParty par companyId
        Employee emp = employeeRepository.findById(ps.getEmployeeId())
            .filter(e -> e.getCompanyId().equals(companyId))
            .orElse(null);
        ThirdParty tp = (emp != null)
            ? thirdPartyRepository.findById(emp.getThirdPartyId())
                .filter(t -> t.getCompanyId().equals(companyId))
                .orElse(null)
            : null;
        String empName = tp != null ? tp.getName() : "";
        String empNumber = emp != null ? emp.getEmployeeNumber() : "";
        List<PayslipResponse.DeductionLine> deductions = fromJson(ps.getDeductions()).stream()
            .map(d -> new PayslipResponse.DeductionLine(
                (String) d.get("code"), (String) d.get("label"),
                new BigDecimal(d.get("rate").toString()),
                new BigDecimal(d.get("amount").toString())))
            .toList();
        List<PayslipResponse.DeductionLine> employerContribs = fromJson(ps.getEmployerContributions()).stream()
            .map(d -> new PayslipResponse.DeductionLine(
                (String) d.get("code"), (String) d.get("label"),
                new BigDecimal(d.get("rate").toString()),
                new BigDecimal(d.get("amount").toString())))
            .toList();
        return new PayslipResponse(
            ps.getId(), ps.getCompanyId(), ps.getRunId(), ps.getEmployeeId(),
            empName, empNumber, ps.getGrossSalary(), deductions, employerContribs,
            ps.getNetPay(), ps.getPayslipNumber(),
            ps.getCreatedAt(), ps.getUpdatedAt());
    }

    private PayrollRun loadRun(UUID companyId, UUID runId) {
        PayrollRun run = runRepository.findById(runId)
            .orElseThrow(() -> new NotFoundException("PayrollRun", runId));
        if (!run.getCompanyId().equals(companyId)) {
            throw new NotFoundException("PayrollRun", runId);
        }
        return run;
    }

    @SuppressWarnings("unchecked")
    private List<WithholdingRule> loadActiveEmployeeRules(UUID companyId) {
        // Charger toutes les règles actives de l'entreprise, filtrer côté Java sur
        // applicableThirdPartyTypes contenant "EMPLOYEE". Le JSONB est stocké en string,
        // on parse pour la vérification.
        List<WithholdingRule> allRules = withholdingRuleRepository
            .findByCompanyIdAndActiveTrue(companyId);
        List<WithholdingRule> employeeRules = new ArrayList<>();
        for (WithholdingRule rule : allRules) {
            try {
                List<String> types = objectMapper.readValue(
                    rule.getApplicableThirdPartyTypes() != null
                        ? rule.getApplicableThirdPartyTypes() : "[]",
                    List.class);
                if (types.contains("EMPLOYEE")) {
                    employeeRules.add(rule);
                }
            } catch (Exception e) {
                LOG.warn("Impossible de parser applicableThirdPartyTypes pour rule={}",
                    rule.getCode(), e);
            }
        }
        return employeeRules;
    }

    private String toJson(List<Map<String, Object>> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize", e);
        }
    }

    /**
     * Lot B R-20 — Résout la durée légale mensuelle depuis la Company.
     *
     * <p>Retourne {@code null} si la Company n'existe pas ou si {@code monthlyLegalHours} n'est
     * pas configuré — le {@link PayrollCalculator} appliquera alors son fallback interne
     * (173.33h = France 35h, comportement historique).
     *
     * <p>Pour une Company en Haïti (country='HT'), la migration V58 backfill-ée à 208h
     * (48h/sem × 52/12) — sauf si l'utilisateur a explicitement surchargé la valeur.
     */
    private java.math.BigDecimal resolveMonthlyLegalHours(UUID companyId) {
        if (companyRepository == null || companyId == null) {
            return null;
        }
        return companyRepository.findById(companyId)
            .map(jo.accountant.company.entity.Company::getMonthlyLegalHours)
            .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    // =========================================================================
    // V75 — v7-4 : 13e mois (Code du Travail Haïti art. 153)
    // =========================================================================

    /**
     * V75 — v7-4 : Lance un PayrollRun de type THIRTEENTH_MONTH pour décembre de l'année donnée.
     *
     * <p>V8-7 — Le calcul est désormais asynchrone via {@link ThirteenthMonthAsyncRunner} (ou
     * via le Job Spring Batch {@code thirteenthMonthJob} si Spring Batch est disponible côté :app).
     * L'endpoint HTTP retourne immédiatement le PayrollRun en statut CALCULATED (ou DRAFT si
     * l'async échoue à démarrer), sans timeout > 30s pour 1200 employés (PME4 Caribbean Textiles).
     *
     * <p>V8-8 — Le 13e mois est désormais soumis aux cotisations sociales (CNSS/OFATMA/AST) en
     * plus de l'ITS. Conforme à la pratique DGI/CNSS Haïti (Code Travail art. 153 ambigu).
     *
     * <p>Le postage de l'écriture comptable n'est PAS fait dans cette méthode — l'utilisateur
     * doit approuver via le endpoint existant pour déclencher le postage, conforme au cycle
     * de vie standard DRAFT→CALCULATED→APPROVED.
     *
     * <p>Si un run THIRTEENTH_MONTH existe déjà pour la même année, conflit 409.
     *
     * @param companyId  tenant
     * @param year       année fiscale (le 13e mois est versé en décembre)
     * @param launchedBy ID utilisateur lanceur (pour audit)
     * @return la campagne créée (calcul asynchrone en arrière-plan)
     */
    @Transactional
    public PayrollRunResponse launchThirteenthMonthRun(UUID companyId, int year, UUID launchedBy) {
        // Vérifier qu'aucune campagne THIRTEENTH_MONTH n'existe déjà pour cette année
        if (runRepository.findByCompanyIdAndPeriodYearAndPeriodMonthAndRunType(
                companyId, year, 12, jo.accountant.payroll.entity.PayrollRunType.THIRTEENTH_MONTH)
                .isPresent()) {
            throw new ConflictException("THIRTEENTH_MONTH_RUN_ALREADY_EXISTS",
                "Une campagne 13e mois existe déjà pour l'année " + year);
        }

        // 1. Créer le PayrollRun (statut DRAFT)
        PayrollRun run = new PayrollRun();
        run.setCompanyId(companyId);
        run.setRunType(jo.accountant.payroll.entity.PayrollRunType.THIRTEENTH_MONTH);
        run.setPeriodYear(year);
        run.setPeriodMonth(12);  // décembre
        run.setStatus(PayrollRunStatus.DRAFT);
        run.setTotalGross(BigDecimal.ZERO);
        run.setTotalNet(BigDecimal.ZERO);
        run.setTotalEmployerContributions(BigDecimal.ZERO);
        PayrollRun saved = runRepository.save(run);

        // Vérifier qu'il y a au moins un employé éligible (sinon on ne lance pas l'async)
        List<Employee> activeEmployees = employeeRepository
            .findByCompanyIdAndStatusOrderByIdAsc(companyId, EmployeeStatus.ACTIVE);
        if (activeEmployees.isEmpty()) {
            throw new ValidationException("NO_ACTIVE_EMPLOYEES",
                "Aucun employé ACTIVE dans l'entreprise — le 13e mois serait vide.");
        }

        // 2. V8-7 — Lancer le calcul asynchrone (fallback @Async ; le Job Spring Batch
        //    thirteenthMonthJob est aussi disponible côté :app pour la production)
        LOG.info("V8-7 — Lancement async 13e mois companyId={} year={} runId={}",
            companyId, year, saved.getId());
        try {
            thirteenthMonthAsyncRunner.runAsync(companyId, saved.getId(), year);
        } catch (Exception e) {
            LOG.error("V8-7 — Échec démarrage async 13e mois (runId={}) : {}", saved.getId(), e.getMessage(), e);
            // Ne pas propager — le run reste DRAFT, l'utilisateur peut réessayer
        }

        LOG.info("V8-7 — 13e mois {} année {} : run {} créé, calcul async en arrière-plan",
            companyId, year, saved.getId());
        return loadRunResponse(companyId, saved.getId());
    }
}

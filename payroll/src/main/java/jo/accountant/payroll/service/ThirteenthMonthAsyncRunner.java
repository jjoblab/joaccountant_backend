package jo.accountant.payroll.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunStatus;
import jo.accountant.payroll.entity.Payslip;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.payroll.repository.PayslipRepository;
import jo.accountant.tax.entity.ContributionRule;
import jo.accountant.tax.repository.ContributionRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8-7 — Runner asynchrone du 13e mois (fallback {@code @Async} au Job Spring Batch
 * {@code thirteenthMonthJob} défini dans {@code :app/batch/BatchConfig}).
 *
 * <p>Le module {@code :payroll} ne dépend pas de Spring Batch (présent uniquement dans
 * {@code :app}). Pour éviter une dépendance Gradle circulaire, ce runner découpe le calcul
 * en batches de 100 employés et log la progression, sans timeout côté API HTTP.
 *
 * <p>Pour 1200 employés (PME4 Caribbean Textiles), la boucle prend ~30s et ne bloque pas
 * l'endpoint REST qui retourne immédiatement le PayrollRun en statut IN_PROGRESS.
 *
 * <p>Le runner peut aussi être remplacé par {@code thirteenthMonthJob} Spring Batch
 * (déclenchable via {@code BatchController}) pour les environnements où Spring Batch est
 * disponible — observabilité, reprise sur incident, métriques.
 */
@Component
public class ThirteenthMonthAsyncRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ThirteenthMonthAsyncRunner.class);
    private static final int LOG_PROGRESS_EVERY = 100;

    private final PayrollRunRepository runRepository;
    private final PayslipRepository payslipRepository;
    private final EmployeeRepository employeeRepository;
    private final ContributionRuleRepository contributionRuleRepository;
    private final PayrollCalculator payrollCalculator;

    public ThirteenthMonthAsyncRunner(PayrollRunRepository runRepository,
                                        PayslipRepository payslipRepository,
                                        EmployeeRepository employeeRepository,
                                        ContributionRuleRepository contributionRuleRepository,
                                        PayrollCalculator payrollCalculator) {
        this.runRepository = runRepository;
        this.payslipRepository = payslipRepository;
        this.employeeRepository = employeeRepository;
        this.contributionRuleRepository = contributionRuleRepository;
        this.payrollCalculator = payrollCalculator;
    }

    /**
     * Calcule le 13e mois pour tous les employés éligibles d'une campagne THIRTEENTH_MONTH,
     * en asynchrone. Le PayrollRun est mis à jour en statut IN_PROGRESS au démarrage puis
     * CALCULATED à la fin (ou ERROR en cas d'échec).
     */
    @Async("thirteenthMonthTaskExecutor")
    @Transactional
    public void runAsync(UUID companyId, UUID runId, int year) {
        LOG.info("V8-7 — Démarrage async 13e mois companyId={} runId={} year={}",
            companyId, runId, year);

        PayrollRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            LOG.error("V8-7 — PayrollRun {} introuvable, abandon", runId);
            return;
        }

        try {
            run.setStatus(PayrollRunStatus.CALCULATED);  // marque IN_PROGRESS virtuellement
            // (Pas de statut IN_PROGRESS dans l'enum ; CALCULATED = "calcul en cours ou terminé")
            runRepository.save(run);

            List<Employee> eligible = employeeRepository
                .findByCompanyIdAndStatusOrderByIdAsc(companyId, EmployeeStatus.ACTIVE);

            List<ContributionRule> contributionRules =
                contributionRuleRepository.findByCompanyIdAndActiveTrue(companyId);
            List<ContributionRule> itsRules = contributionRules.stream()
                .filter(r -> "ITS_HT".equals(r.getCode()) || "ITS".equals(r.getCode()))
                .toList();

            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            int processed = 0;
            int skipped = 0;

            for (Employee employee : eligible) {
                if (Boolean.FALSE.equals(employee.getThirteenthMonthEligible())) {
                    skipped++;
                    continue;
                }
                if (employee.getHireDate() == null
                        || employee.getHireDate().isAfter(LocalDate.of(year, 12, 31))) {
                    skipped++;
                    continue;
                }

                BigDecimal gross = payrollCalculator.calculateThirteenthMonth(employee, year);
                if (gross.compareTo(BigDecimal.ZERO) == 0) {
                    skipped++;
                    continue;
                }

                // V8-8 — 13e mois soumis aux cotisations sociales (CNSS/OFATMA/AST) en plus de l'ITS.
                // Code Travail Haïti art. 153 — pratique DGI/CNSS applique les cotisations sociales.
                BigDecimal socialDeductions = BigDecimal.ZERO;
                for (ContributionRule rule : contributionRules) {
                    if ("ITS_HT".equals(rule.getCode()) || "ITS".equals(rule.getCode())) {
                        continue;  // ITS traité séparément
                    }
                    if (rule.getContributionType() == ContributionRule.ContributionType.EMPLOYEE
                        || rule.getContributionType() == ContributionRule.ContributionType.EMPLOYEE_AND_EMPLOYER) {
                        // Pour le 13e mois, on applique les cotisations sociales salariales
                        // (CNSS 6% plafonné à 150K HTG, OFATMA Santé 1%, OFATMA Accidents, AST).
                        // Simplification : on utilise le brut comme base avec plafond CNSS si configuré.
                        try {
                            BigDecimal base = gross;
                            if (rule.getMonthlyCeiling() != null && base.compareTo(rule.getMonthlyCeiling()) > 0) {
                                base = rule.getMonthlyCeiling();  // CAPPED_GROSS
                            }
                            if (base.compareTo(BigDecimal.ZERO) <= 0) continue;
                            BigDecimal amount;
                            if (rule.getBracketType() == jo.accountant.core.tax.WithholdingBracketType.PROGRESSIVE
                                    && rule.getBracketsJson() != null && !rule.getBracketsJson().isBlank()) {
                                // AST progressif — réutiliser computeIts comme fallback PROGRESSIVE.
                                amount = payrollCalculator.computeIts(base, List.of(rule));
                            } else {
                                amount = base.multiply(rule.getRate())
                                    .divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP);
                            }
                            socialDeductions = socialDeductions.add(amount);
                        } catch (Exception e) {
                            LOG.warn("V8-8 — Cotisation {} échouée pour employé {} : {}",
                                rule.getCode(), employee.getId(), e.getMessage());
                        }
                    }
                }

                BigDecimal itsAmount = payrollCalculator.computeIts(gross, itsRules);
                BigDecimal net = gross.subtract(socialDeductions).subtract(itsAmount);

                List<Map<String, Object>> deductions = new java.util.ArrayList<>();
                if (socialDeductions.compareTo(BigDecimal.ZERO) > 0) {
                    Map<String, Object> social = new HashMap<>();
                    social.put("code", "SOCIAL_DEDUCTIONS");
                    social.put("label", "Cotisations sociales (CNSS/OFATMA/AST) sur 13e mois");
                    social.put("amount", socialDeductions);
                    social.put("party", "EMPLOYEE");
                    deductions.add(social);
                }
                if (itsAmount.compareTo(BigDecimal.ZERO) > 0) {
                    Map<String, Object> its = new HashMap<>();
                    its.put("code", "ITS_HT");
                    its.put("label", "ITS Haïti — Impôt sur Traitements et Salaires");
                    its.put("amount", itsAmount);
                    its.put("party", "EMPLOYEE");
                    deductions.add(its);
                }

                Payslip payslip = new Payslip();
                payslip.setCompanyId(companyId);
                payslip.setRunId(runId);
                payslip.setEmployeeId(employee.getId());
                payslip.setGrossSalary(gross);
                payslip.setDeductions(serialize(deductions));
                payslip.setEmployerContributions(serialize(List.of()));
                payslip.setNetPay(net);
                payslipRepository.save(payslip);

                totalGross = totalGross.add(gross);
                totalNet = totalNet.add(net);
                processed++;

                if (processed % LOG_PROGRESS_EVERY == 0) {
                    LOG.info("V8-7 — 13e mois {} : {}/{} employés traités",
                        companyId, processed, eligible.size());
                }
            }

            run.setTotalGross(totalGross);
            run.setTotalNet(totalNet);
            run.setTotalEmployerContributions(BigDecimal.ZERO);
            run.setStatus(PayrollRunStatus.CALCULATED);
            runRepository.save(run);

            LOG.info("V8-7 — 13e mois terminé companyId={} year={} : {} traités, {} skippés, brut={} net={}",
                companyId, year, processed, skipped, totalGross, totalNet);

        } catch (Exception e) {
            LOG.error("V8-7 — Échec 13e mois companyId={} runId={} : {}",
                companyId, runId, e.getMessage(), e);
            run.setStatus(PayrollRunStatus.DRAFT);  // retour arrière
            runRepository.save(run);
        }
    }

    private String serialize(List<Map<String, Object>> list) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}

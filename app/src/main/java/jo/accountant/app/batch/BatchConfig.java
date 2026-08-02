package jo.accountant.app.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.financialstatements.dto.SnapshotResponse;
import jo.accountant.financialstatements.service.FinancialStatementsService;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunStatus;
import jo.accountant.payroll.entity.Payslip;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.payroll.repository.PayslipRepository;
import jo.accountant.payroll.service.PayrollCalculator;
import jo.accountant.tax.entity.ContributionRule;
import jo.accountant.tax.repository.ContributionRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Configuration Spring Batch pour les Jobs batch de paie et de clôture d'exercice.
 *
 * <p>Deux Jobs sont enregistrés via {@link EnableBatchProcessing} :
 * <ul>
 * <li><b>{@code payrollJob}</b> — Step chunk-oriented (taille 50) qui lit tous les employés
 * {@code ACTIVE} d'une entreprise, calcule le brut→net via {@link PayrollCalculator}
 * (sur la base des {@link ContributionRule} actives), et génère les {@link Payslip}.
 * Retry 3× sur {@link IOException} (fault-tolerant step). Met à jour les totaux du
 * {@link PayrollRun} (totalGross, totalNet, totalEmployerContributions, status=CALCULATED)
 * à la fin du Step via un {@link StepExecutionListener}.</li>
 * <li><b>{@code fiscalYearClosingJob}</b> — Step tasklet qui exécute
 * {@link AccountingEngineService#closeFiscalYear(UUID, UUID)} puis
 * {@link FinancialStatementsService#createClosingSnapshots(UUID, UUID)} pour figer
 * le bilan et le compte de résultat de l'exercice clôturé. Retry 1× (la clôture est
 * idempotente — un second appel ne fait rien si l'exercice est déjà CLOSED).</li>
 * </ul>
 *
 * <p><b>Non auto-démarrés</b> : la propriété {@code spring.batch.job.enabled=false} dans
 * {@code application.yml} désactive le lancement automatique des Jobs au démarrage. Les Jobs
 * sont lancés manuellement via {@link BatchController} (ou par cron ShedLock en production).
 *
 * <p><b>TenantContext</b> : un {@link JobExecutionListener} alimente le {@link TenantContext}
 * (companyId + userId technique) à partir des {@code JobParameters} avant l'exécution du Job,
 * et le nettoie après. Côté contrôleur, le {@link TenantContext} est également positionné
 * avant l'appel {@code jobLauncher.run(...)} pour couvrir le cas synchrone (JobLauncher par
 * défaut).
 
 *
 * @author jo@Dev


*/
@Configuration
@EnableBatchProcessing
public class BatchConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BatchConfig.class);
    private static final int PAYROLL_CHUNK_SIZE = 50;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final ContributionRuleRepository contributionRuleRepository;
    private final PayrollCalculator payrollCalculator;
    private final PayslipRepository payslipRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final AccountingEngineService accountingEngineService;
    private final FinancialStatementsService financialStatementsService;
    private final FiscalPeriodRepository fiscalPeriodRepository;

    public BatchConfig(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        EntityManagerFactory entityManagerFactory,
                        ContributionRuleRepository contributionRuleRepository,
                        PayrollCalculator payrollCalculator,
                        PayslipRepository payslipRepository,
                        PayrollRunRepository payrollRunRepository,
                        AccountingEngineService accountingEngineService,
                        FinancialStatementsService financialStatementsService,
                        FiscalPeriodRepository fiscalPeriodRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.entityManagerFactory = entityManagerFactory;
        this.contributionRuleRepository = contributionRuleRepository;
        this.payrollCalculator = payrollCalculator;
        this.payslipRepository = payslipRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.accountingEngineService = accountingEngineService;
        this.financialStatementsService = financialStatementsService;
        this.fiscalPeriodRepository = fiscalPeriodRepository;
    }

    // ===================================================================
    // Job 1 — payrollJob : Step chunk-oriented (taille 50) + retry 3×
    // ===================================================================

    /**
     * Job de paie — calcule les bulletins (Payslip) pour tous les employés ACTIVE
     * d'une entreprise sur une campagne ({@code runId}).
     *
     * <p>Paramètres attendus :
     * <ul>
     * <li>{@code companyId} (UUID) — tenant</li>
     * <li>{@code runId} (UUID) — ID de la campagne (PayrollRun) à calculer</li>
     * </ul>
     */
    @Bean
    public Job payrollJob(Step payrollStep) {
        return new JobBuilder("payrollJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .listener(tenantContextListener())
            .start(payrollStep)
            .build();
    }

    /**
     * Step chunk-oriented (taille 50) qui lit les employés ACTIVE de l'entreprise,
     * calcule le brut→net via {@link PayrollCalculator} et génère les {@link Payslip}.
     *
     * <p>Fault-tolerant : retry 3× sur {@link IOException} (le processor peut lever
     * IOException lors de la sérialisation JSON des deductions).
     */
    @Bean
    public Step payrollStep(ItemReader<Employee> payrollReader,
                             ItemProcessor<Employee, Payslip> payrollProcessor,
                             ItemWriter<Payslip> payrollWriter) {
        return new StepBuilder("payrollStep", jobRepository)
            .<Employee, Payslip>chunk(PAYROLL_CHUNK_SIZE, transactionManager)
            .reader(payrollReader)
            .processor(payrollProcessor)
            .writer(payrollWriter)
            .faultTolerant()
            .retry(IOException.class)
            .retryLimit(3)
            .listener(payrollRunTotalsListener())
            .build();
    }

    /**
     * Reader JPA paginé — lit les employés ACTIVE de l'entreprise par pages de 50.
     *
     * <p>{@code @StepScope} : un reader frais est créé pour chaque exécution du Step
     * (obligatoire pour injecter les {@code JobParameters} via SpEL).
     */
    @Bean
    @StepScope
    public JpaPagingItemReader<Employee> payrollReader(
            @Value("#{jobParameters['companyId']}") UUID companyId) {
        Map<String, Object> params = new HashMap<>();
        params.put("companyId", companyId);
        params.put("status", EmployeeStatus.ACTIVE);
        return new JpaPagingItemReaderBuilder<Employee>()
            .name("payrollReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString(
                "SELECT e FROM Employee e " +
                "WHERE e.companyId = :companyId AND e.status = :status " +
                "ORDER BY e.id")
            .parameterValues(params)
            .pageSize(PAYROLL_CHUNK_SIZE)
            .build();
    }

    /**
     * Processor — pour chaque employé, calcule le brut→net via {@link PayrollCalculator}
     * sur la base des {@link ContributionRule} actives (chargées une fois au premier
     * appel — lazy init thread-safe via volatile + double-check).
     *
     * <p>Retourne un {@link Payslip} prêt à être persisté par le writer.
     */
    @Bean
    @StepScope
    public ItemProcessor<Employee, Payslip> payrollProcessor(
            @Value("#{jobParameters['companyId']}") UUID companyId,
            @Value("#{jobParameters['runId']}") UUID runId) {
        return new PayrollChunkProcessor(companyId, runId, contributionRuleRepository,
            payrollCalculator, new ObjectMapper());
    }

    /**
     * Writer — persiste les {@link Payslip} en base. Utilise le repository JPA.
     */
    @Bean
    public ItemWriter<Payslip> payrollWriter() {
        return new PayslipItemWriter(payslipRepository);
    }

    /**
     * Step listener — après l'exécution du Step, recalcule les totaux (totalGross,
     * totalNet, totalEmployerContributions) à partir des payslips persistés et met à
     * jour le {@link PayrollRun} (status CALCULATED).
     */
    @Bean
    public PayrollRunTotalsListener payrollRunTotalsListener() {
        return new PayrollRunTotalsListener(payrollRunRepository, payslipRepository);
    }

    // ===================================================================
    // Job 1b — thirteenthMonthJob : Step chunk-oriented (taille 50) + retry 3×
    // v8-7 — 13e mois (Code du Travail Haïti art. 153) asynchrone via Spring Batch.
    // ===================================================================

    /**
     * Job de 13e mois — calcule les bulletins (Payslip) pour tous les employés
     * {@code ACTIVE} éligibles au 13e mois ({@code thirteenthMonthEligible=true} ET
     * {@code hireDate <= 31/12/year}) d'une entreprise sur une campagne
     * ({@code runId} de type {@code THIRTEENTH_MONTH}).
     *
     * <p>v8-7 — Pour 1200 employés (PME4 Caribbean Textiles), la boucle synchrone
     * historique de {@code PayrollService.launchThirteenthMonthRun} timeout > 30s.
     * Ce Job Spring Batch découpe le calcul en chunks de 50 employés, persiste les
     * payslips au fur et à mesure (au lieu de tout garder en mémoire), et permet
     * une reprise sur incident (restart Spring Batch).
     *
     * <p>Paramètres attendus :
     * <ul>
     * <li>{@code companyId} (UUID) — tenant</li>
     * <li>{@code runId} (UUID) — ID de la campagne THIRTEENTH_MONTH à calculer</li>
     * <li>{@code year} (Integer) — année fiscale (le 13e mois est versé en décembre)</li>
     * </ul>
     *
     * <p><b>Note</b> : ce Job est défini dans {@code :app/batch} (où Spring Batch est
     * disponible). Le module {@code :payroll} ne dépend pas de Spring Batch —
     * {@code PayrollService.launchThirteenthMonthRun} utilise donc un fallback
     * {@code @Async} (voir {@code ThirteenthMonthAsyncRunner} dans {@code :payroll}).
     * Le Job est néanmoins lançable manuellement via {@code BatchController} pour
     * les besoins d'observabilité / cron / reprise sur incident.
     */
    @Bean
    public Job thirteenthMonthJob(Step thirteenthMonthStep) {
        return new JobBuilder("thirteenthMonthJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .listener(tenantContextListener())
            .start(thirteenthMonthStep)
            .build();
    }

    /**
     * Step chunk-oriented (taille 50) qui lit les employés éligibles au 13e mois,
     * calcule le brut (prorata temporis) + ITS via {@link PayrollCalculator} et génère
     * les {@link Payslip}. Fault-tolerant : retry 3× sur {@link IOException}.
     */
    @Bean
    public Step thirteenthMonthStep(ItemReader<Employee> thirteenthMonthReader,
                                      ItemProcessor<Employee, Payslip> thirteenthMonthProcessor,
                                      ItemWriter<Payslip> thirteenthMonthWriter) {
        return new StepBuilder("thirteenthMonthStep", jobRepository)
            .<Employee, Payslip>chunk(PAYROLL_CHUNK_SIZE, transactionManager)
            .reader(thirteenthMonthReader)
            .processor(thirteenthMonthProcessor)
            .writer(thirteenthMonthWriter)
            .faultTolerant()
            .retry(IOException.class)
            .retryLimit(3)
            .listener(thirteenthMonthRunTotalsListener())
            .build();
    }

    /**
     * Reader JPA paginé — lit les employés éligibles au 13e mois par pages de 50.
     * Filtre {@code companyId = X AND status = ACTIVE AND thirteenthMonthEligible = true
     * AND hireDate <= 31/12/year}.
     */
    @Bean
    @StepScope
    public JpaPagingItemReader<Employee> thirteenthMonthReader(
            @Value("#{jobParameters['companyId']}") UUID companyId,
            @Value("#{jobParameters['year']}") Integer year) {
        Map<String, Object> params = new HashMap<>();
        params.put("companyId", companyId);
        params.put("status", EmployeeStatus.ACTIVE);
        // 31/12/{year} — cutoff d'ancienneté (employés embauchés après ne sont pas éligibles)
        params.put("hireDateCutoff", java.time.LocalDate.of(year, 12, 31));
        return new JpaPagingItemReaderBuilder<Employee>()
            .name("thirteenthMonthReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString(
                "SELECT e FROM Employee e " +
                "WHERE e.companyId = :companyId " +
                "AND e.status = :status " +
                "AND e.thirteenthMonthEligible = true " +
                "AND e.hireDate <= :hireDateCutoff " +
                "ORDER BY e.id")
            .parameterValues(params)
            .pageSize(PAYROLL_CHUNK_SIZE)
            .build();
    }

    /**
     * Processor — pour chaque employé éligible, calcule le 13e mois brut
     * ({@link PayrollCalculator#calculateThirteenthMonth}) + ITS
     * ({@link PayrollCalculator#computeIts}) et construit un {@link Payslip} non
     * persisté (la persistance est faite par le writer).
     */
    @Bean
    @StepScope
    public ItemProcessor<Employee, Payslip> thirteenthMonthProcessor(
            @Value("#{jobParameters['companyId']}") UUID companyId,
            @Value("#{jobParameters['runId']}") UUID runId,
            @Value("#{jobParameters['year']}") Integer year) {
        return new ThirteenthMonthChunkProcessor(companyId, runId, year,
            contributionRuleRepository, payrollCalculator, new ObjectMapper());
    }

    /**
     * Writer — réutilise {@link PayslipItemWriter} (générique, persiste les payslips
     * via {@code payslipRepository.saveAll}). Pas de logique spécifique au 13e mois.
     */
    @Bean
    public ItemWriter<Payslip> thirteenthMonthWriter() {
        return new PayslipItemWriter(payslipRepository);
    }

    /**
     * Step listener — après l'exécution du Step, recalcule les totaux (totalGross,
     * totalNet) à partir des payslips persistés et met à jour le {@link PayrollRun}
     * (status CALCULATED). {@code totalEmployerContributions} reste à 0 (pas de
     * charges patronales sur le 13e mois — Code Fiscal Haïti art. 156).
     */
    @Bean
    public ThirteenthMonthRunTotalsListener thirteenthMonthRunTotalsListener() {
        return new ThirteenthMonthRunTotalsListener(payrollRunRepository, payslipRepository);
    }

    // ===================================================================
    // Job 2 — fiscalYearClosingJob : Step tasklet + retry 1×
    // ===================================================================

    /**
     * Job de clôture d'exercice — exécute {@link AccountingEngineService#closeFiscalYear}
     * puis {@link FinancialStatementsService#createClosingSnapshots} pour figer le bilan
     * et le compte de résultat de la dernière période de l'exercice.
     *
     * <p>Paramètres attendus :
     * <ul>
     * <li>{@code companyId} (UUID) — tenant</li>
     * <li>{@code fiscalYearId} (UUID) — ID de l'exercice à clôturer</li>
     * </ul>
     */
    @Bean
    public Job fiscalYearClosingJob(Step fiscalYearClosingStep) {
        return new JobBuilder("fiscalYearClosingJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .listener(tenantContextListener())
            .start(fiscalYearClosingStep)
            .build();
    }

    /**
     * Step tasklet — délègue à {@link FiscalYearClosingTasklet} qui enchaîne
     * {@code closeFiscalYear} + {@code createClosingSnapshots}. Fault-tolerant :
     * retry 1× (la clôture est idempotente — un second appel ne fait rien si l'exercice
     * est déjà CLOSED).
     */
    @Bean
    public Step fiscalYearClosingStep(Tasklet fiscalYearClosingTasklet) {
        // Note : Spring Batch 5 ne supporte .faultTolerant() que sur les chunk-oriented steps
        // (SimpleStepBuilder), pas sur les tasklet steps (TaskletStepBuilder). Le retry 1× est
        // donc implémenté à l'intérieur du Tasklet (FiscalYearClosingTasklet.execute) via une
        // boucle try/catch — voir la javadoc du Tasklet.
        return new StepBuilder("fiscalYearClosingStep", jobRepository)
            .tasklet(fiscalYearClosingTasklet, transactionManager)
            .build();
    }

    /**
     * Tasklet de clôture — reçoit {@code companyId} et {@code fiscalYearId} via
     * {@code JobParameters} (SpEL). Implémente retry 1× en interne (Spring Batch 5 ne
     * supporte .faultTolerant() que sur les chunk-oriented steps).
     */
    @Bean
    @StepScope
    public Tasklet fiscalYearClosingTasklet(
            @Value("#{jobParameters['companyId']}") UUID companyId,
            @Value("#{jobParameters['fiscalYearId']}") UUID fiscalYearId) {
        return new FiscalYearClosingTasklet(companyId, fiscalYearId,
            accountingEngineService, financialStatementsService, fiscalPeriodRepository);
    }

    // ===================================================================
    // Listener tenant — alimente TenantContext depuis JobParameters
    // ===================================================================

    /**
     * Listener qui positionne le {@link TenantContext} (companyId + userId technique
     * batch) avant l'exécution du Job et le nettoie après. Indispensable car les
     * filtres Hibernate ({@code @FilterDef} sur {@code TenantAwareEntity}) consultent
     * le ThreadLocal pour filtrer les données par entreprise.
     */
    @Bean
    public JobExecutionListener tenantContextListener() {
        return new TenantContextJobListener();
    }

    // ====================================================================
    // Inner classes — processor, writer, listeners, tasklet
    // ====================================================================

    /**
     * Processor chunk qui calcule le brut→net via {@link PayrollCalculator} pour chaque
     * employé. Charge les {@link ContributionRule} actives au premier appel (lazy init)
     * et les réutilise pour tous les employés du Step.
     */
    static class PayrollChunkProcessor implements ItemProcessor<Employee, Payslip> {

        private final UUID companyId;
        private final UUID runId;
        private final ContributionRuleRepository contributionRuleRepository;
        private final PayrollCalculator payrollCalculator;
        private final ObjectMapper objectMapper;
        private volatile List<ContributionRule> rules;

        PayrollChunkProcessor(UUID companyId, UUID runId,
                              ContributionRuleRepository contributionRuleRepository,
                              PayrollCalculator payrollCalculator,
                              ObjectMapper objectMapper) {
            this.companyId = companyId;
            this.runId = runId;
            this.contributionRuleRepository = contributionRuleRepository;
            this.payrollCalculator = payrollCalculator;
            this.objectMapper = objectMapper;
        }

        @Override
        public Payslip process(Employee emp) throws IOException {
            // Lazy init — chargé une fois pour tous les items du Step.
            if (rules == null) {
                List<ContributionRule> loaded = contributionRuleRepository
                    .findByCompanyIdAndActiveTrue(companyId);
                if (loaded.isEmpty()) {
                    throw new IOException(
                        "Aucune ContributionRule active pour la company " + companyId
                        + " — le moteur PayrollCalculator ne peut pas calculer la paie. "
                        + "Configurer au moins une règle de cotisation (module :tax).");
                }
                rules = loaded;
            }

            PayrollCalculator.PayrollCalculationResult result =
                payrollCalculator.calculate(companyId, emp.getId(), emp, rules);

            Payslip payslip = new Payslip();
            payslip.setCompanyId(companyId);
            payslip.setRunId(runId);
            payslip.setEmployeeId(emp.getId());
            payslip.setGrossSalary(result.grossSalary());
            payslip.setDeductions(toJson(result.employeeContributions()));
            payslip.setEmployerContributions(toJson(result.employerContributions()));
            payslip.setNetPay(result.netSalary());
            return payslip;
        }

        private String toJson(List<PayrollCalculator.ContributionLine> lines) throws IOException {
            try {
                List<Map<String, Object>> mapped = new ArrayList<>();
                for (PayrollCalculator.ContributionLine c : lines) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("code", c.code());
                    m.put("label", c.label());
                    m.put("rate", c.rate());
                    m.put("base", c.base());
                    m.put("baseType", c.baseType());
                    m.put("amount", c.amount());
                    m.put("party", c.party());
                    mapped.add(m);
                }
                return objectMapper.writeValueAsString(mapped);
            } catch (JsonProcessingException e) {
                throw new IOException("Erreur de sérialisation JSON des cotisations", e);
            }
        }
    }

    /**
     * Writer qui persiste les {@link Payslip} via le repository JPA.
     */
    static class PayslipItemWriter implements ItemWriter<Payslip> {
        private final PayslipRepository payslipRepository;

        PayslipItemWriter(PayslipRepository payslipRepository) {
            this.payslipRepository = payslipRepository;
        }

        @Override
        public void write(Chunk<? extends Payslip> items) {
            payslipRepository.saveAll(items);
            LOG.info("PayslipWriter : {} bulletin(s) persisté(s)", items.size());
        }
    }

    /**
     * Listener qui, à la fin du Step payroll, recalcule les totaux (totalGross, totalNet,
     * totalEmployerContributions) à partir des {@link Payslip} persistés et met à jour le
     * {@link PayrollRun} (status CALCULATED).
     */
    static class PayrollRunTotalsListener implements StepExecutionListener {

        private final PayrollRunRepository payrollRunRepository;
        private final PayslipRepository payslipRepository;
        private final ObjectMapper objectMapper = new ObjectMapper();

        PayrollRunTotalsListener(PayrollRunRepository payrollRunRepository,
                                  PayslipRepository payslipRepository) {
            this.payrollRunRepository = payrollRunRepository;
            this.payslipRepository = payslipRepository;
        }

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            JobParameters params = stepExecution.getJobParameters();
            UUID runId = (UUID) params.getParameters().get("runId").getValue();
            UUID companyId = (UUID) params.getParameters().get("companyId").getValue();

            PayrollRun run = payrollRunRepository.findById(runId).orElseThrow(() ->
                new IllegalStateException("PayrollRun introuvable : " + runId));

            List<Payslip> payslips = payslipRepository.findByRunIdOrderByCreatedAt(runId);
            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            BigDecimal totalEmployerContributions = BigDecimal.ZERO;
            for (Payslip p : payslips) {
                totalGross = totalGross.add(p.getGrossSalary());
                totalNet = totalNet.add(p.getNetPay());
                totalEmployerContributions = totalEmployerContributions.add(extractEmployerTotal(p));
            }
            run.setTotalGross(totalGross);
            run.setTotalNet(totalNet);
            run.setTotalEmployerContributions(totalEmployerContributions);
            run.setStatus(PayrollRunStatus.CALCULATED);
            payrollRunRepository.save(run);
            LOG.info("PayrollRun {} (company={}) mis à jour : {} bulletin(s) brut={} net={} patronal={}",
                runId, companyId, payslips.size(), totalGross, totalNet, totalEmployerContributions);
            return stepExecution.getExitStatus();
        }

        /** Extrait la somme des montants patronaux depuis le JSONB employerContributions. */
        private BigDecimal extractEmployerTotal(Payslip p) {
            String json = p.getEmployerContributions();
            if (json == null || json.isBlank() || "[]".equals(json.trim())) {
                return BigDecimal.ZERO;
            }
            try {
                List<?> list = objectMapper.readValue(json, List.class);
                BigDecimal sum = BigDecimal.ZERO;
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("amount") instanceof Number n) {
                        sum = sum.add(new BigDecimal(n.toString()));
                    }
                }
                return sum;
            } catch (Exception e) {
                LOG.warn("Impossible de parser employerContributions du payslip {} : {}",
                    p.getId(), e.getMessage());
                return BigDecimal.ZERO;
            }
        }
    }

    /**
     * v8-7 — Processor chunk pour le 13e mois. Pour chaque employé éligible :
     * <ol>
     * <li>Calcule le 13e mois brut via {@link PayrollCalculator#calculateThirteenthMonth}
     * (prorata temporis si moins de 12 mois d'ancienneté, plafond 1 mois sinon).</li>
     * <li>Calcule l'ITS (Impôt sur Traitements et Salaires — Code Fiscal Haïti art. 156)
     * via {@link PayrollCalculator#computeIts} sur les règles ITS_HT actives.</li>
     * <li>Construit le {@link Payslip} (deductions = ITS uniquement, pas de cotisations
     * sociales CNSS/OFATMA/AST sur le 13e mois) et le retourne pour persistance.</li>
     * </ol>
     *
     * <p>Les règles ITS sont chargées une fois au premier appel (lazy init thread-safe
     * via volatile + double-check), comme {@link PayrollChunkProcessor}.
     */
    static class ThirteenthMonthChunkProcessor implements ItemProcessor<Employee, Payslip> {

        private final UUID companyId;
        private final UUID runId;
        private final int year;
        private final ContributionRuleRepository contributionRuleRepository;
        private final PayrollCalculator payrollCalculator;
        private final ObjectMapper objectMapper;
        private volatile List<ContributionRule> itsRules;

        ThirteenthMonthChunkProcessor(UUID companyId, UUID runId, int year,
                                       ContributionRuleRepository contributionRuleRepository,
                                       PayrollCalculator payrollCalculator,
                                       ObjectMapper objectMapper) {
            this.companyId = companyId;
            this.runId = runId;
            this.year = year;
            this.contributionRuleRepository = contributionRuleRepository;
            this.payrollCalculator = payrollCalculator;
            this.objectMapper = objectMapper;
        }

        @Override
        public Payslip process(Employee emp) throws IOException {
            // Lazy init — chargé une fois pour tous les items du Step.
            if (itsRules == null) {
                List<ContributionRule> loaded = contributionRuleRepository
                    .findByCompanyIdAndActiveTrue(companyId);
                List<ContributionRule> its = loaded.stream()
                    .filter(r -> "ITS_HT".equals(r.getCode()) || "ITS".equals(r.getCode()))
                    .toList();
                itsRules = its;
                if (loaded.isEmpty()) {
                    throw new IOException(
                        "Aucune ContributionRule active pour la company " + companyId
                        + " — le moteur PayrollCalculator ne peut pas calculer l'ITS du 13e mois. "
                        + "Configurer au moins une règle ITS_HT (module :tax).");
                }
            }

            // 13e mois brut (prorata temporis)
            BigDecimal gross = payrollCalculator.calculateThirteenthMonth(emp, year);
            if (gross == null || gross.compareTo(BigDecimal.ZERO) == 0) {
                // Employé non éligible (déjà filtré par le reader, mais calculateThirteenthMonth
                // peut retourner 0 si hireDate est null — on skip).
                return null;
            }

            // ITS sur le 13e mois (barème progressif mensuel)
            BigDecimal itsAmount = payrollCalculator.computeIts(gross, itsRules);
            BigDecimal net = gross.subtract(itsAmount);

            // Sérialiser les déductions (ITS uniquement — pas de cotisations sociales sur le 13e mois)
            List<Map<String, Object>> deductions = new ArrayList<>();
            if (itsAmount.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> ded = new HashMap<>();
                ded.put("code", "ITS_HT");
                ded.put("label", "ITS Haïti — Impôt sur Traitements et Salaires");
                ded.put("rate", itsRules.isEmpty() ? BigDecimal.ZERO : itsRules.get(0).getRate());
                ded.put("base", gross);
                ded.put("amount", itsAmount);
                ded.put("party", "EMPLOYEE");
                deductions.add(ded);
            }

            Payslip payslip = new Payslip();
            payslip.setCompanyId(companyId);
            payslip.setRunId(runId);
            payslip.setEmployeeId(emp.getId());
            payslip.setGrossSalary(gross);
            payslip.setDeductions(toJson(deductions));
            payslip.setEmployerContributions(toJson(List.of())); // pas de charges patronales
            payslip.setNetPay(net);
            return payslip;
        }

        private String toJson(List<Map<String, Object>> lines) throws IOException {
            try {
                return objectMapper.writeValueAsString(lines);
            } catch (JsonProcessingException e) {
                throw new IOException("Erreur de sérialisation JSON des déductions ITS", e);
            }
        }
    }

    /**
     * v8-7 — Listener qui, à la fin du Step thirteenthMonthStep, recalcule les totaux
     * (totalGross, totalNet) à partir des {@link Payslip} persistés et met à jour le
     * {@link PayrollRun} (status CALCULATED). {@code totalEmployerContributions} reste
     * à 0 (pas de charges patronales sur le 13e mois).
     */
    static class ThirteenthMonthRunTotalsListener implements StepExecutionListener {

        private final PayrollRunRepository payrollRunRepository;
        private final PayslipRepository payslipRepository;

        ThirteenthMonthRunTotalsListener(PayrollRunRepository payrollRunRepository,
                                          PayslipRepository payslipRepository) {
            this.payrollRunRepository = payrollRunRepository;
            this.payslipRepository = payslipRepository;
        }

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            JobParameters params = stepExecution.getJobParameters();
            UUID runId = (UUID) params.getParameters().get("runId").getValue();
            UUID companyId = (UUID) params.getParameters().get("companyId").getValue();

            PayrollRun run = payrollRunRepository.findById(runId).orElseThrow(() ->
                new IllegalStateException("PayrollRun THIRTEENTH_MONTH introuvable : " + runId));

            List<Payslip> payslips = payslipRepository.findByRunIdOrderByCreatedAt(runId);
            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            for (Payslip p : payslips) {
                totalGross = totalGross.add(p.getGrossSalary());
                totalNet = totalNet.add(p.getNetPay());
            }
            run.setTotalGross(totalGross);
            run.setTotalNet(totalNet);
            run.setTotalEmployerContributions(BigDecimal.ZERO); // pas de charges patronales 13e mois
            run.setStatus(PayrollRunStatus.CALCULATED);
            payrollRunRepository.save(run);
            LOG.info("v8-7 — PayrollRun THIRTEENTH_MONTH {} (company={}) mis à jour : " +
                    "{} bulletin(s) brut={} net={} patronal=0",
                runId, companyId, payslips.size(), totalGross, totalNet);
            return stepExecution.getExitStatus();
        }
    }

    /**
     * Tasklet de clôture d'exercice — enchaîne :
     * <ol>
     * <li>{@link AccountingEngineService#closeFiscalYear} : génère l'écriture de clôture
     * (solde des comptes de produits/charges contre le compte de résultat) +
     * l'écriture d'ouverture N+1 (report des soldes de bilan).</li>
     * <li>{@link FinancialStatementsService#createClosingSnapshots} : fige le bilan et
     * le compte de résultat de la dernière période de l'exercice.</li>
     * </ol>
     *
     * <p><b>Retry 1×</b> : Spring Batch 5 ne supporte {@code .faultTolerant()} que sur les
     * chunk-oriented steps (SimpleStepBuilder), pas sur les tasklet steps. Le retry est donc
     * implémenté ici via une boucle try/catch (2 tentatives max = 1 essai + 1 retry). La
     * clôture étant idempotente (closeFiscalYear refuse si FY déjà CLOSED avec
     * NO_RESULT_TO_CLOSE — on catche ce cas pour ne pas échouer), un second appel est sûr.
     */
    static class FiscalYearClosingTasklet implements Tasklet {

        /** Nombre total de tentatives (1 essai initial + 1 retry = 2). */
        private static final int MAX_ATTEMPTS = 2;

        private final UUID companyId;
        private final UUID fiscalYearId;
        private final AccountingEngineService accountingEngineService;
        private final FinancialStatementsService financialStatementsService;
        private final FiscalPeriodRepository fiscalPeriodRepository;

        FiscalYearClosingTasklet(UUID companyId, UUID fiscalYearId,
                                  AccountingEngineService accountingEngineService,
                                  FinancialStatementsService financialStatementsService,
                                  FiscalPeriodRepository fiscalPeriodRepository) {
            this.companyId = companyId;
            this.fiscalYearId = fiscalYearId;
            this.accountingEngineService = accountingEngineService;
            this.financialStatementsService = financialStatementsService;
            this.fiscalPeriodRepository = fiscalPeriodRepository;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    return doExecute(contribution);
                } catch (Exception e) {
                    lastError = e;
                    LOG.warn("Tentative {}/{} échouée pour clôture exercice {} (company={}) : {}",
                        attempt, MAX_ATTEMPTS, fiscalYearId, companyId, e.getMessage());
                    if (attempt < MAX_ATTEMPTS) {
                        // Court délai avant retry pour éviter de retomber sur un verrou DB.
                        try {
                            Thread.sleep(200L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interruption pendant retry clôture", ie);
                        }
                    }
                }
            }
            throw new RuntimeException(
                "Clôture exercice " + fiscalYearId + " (company " + companyId
                + ") échouée après " + MAX_ATTEMPTS + " tentatives", lastError);
        }

        /** Exécution réelle — appelée par execute() avec retry. */
        private RepeatStatus doExecute(StepContribution contribution) {
            // (1) Clôture de l'exercice — génère écriture de clôture + écriture d'ouverture N+1.
            JournalEntryResponse closingEntry = accountingEngineService
                .closeFiscalYear(companyId, fiscalYearId);
            LOG.info("Clôture exercice {} (company={}) : écriture {} postée",
                fiscalYearId, companyId, closingEntry.id());

            // (2) Snapshots figés (bilan + CR) sur la dernière période de l'exercice.
            List<FiscalPeriod> periods = fiscalPeriodRepository
                .findByFiscalYearIdOrderByStartDateAsc(fiscalYearId);
            if (periods.isEmpty()) {
                throw new IllegalStateException(
                    "Aucune période fiscale trouvée pour l'exercice " + fiscalYearId);
            }
            FiscalPeriod lastPeriod = periods.get(periods.size() - 1);
            List<SnapshotResponse> snapshots = financialStatementsService
                .createClosingSnapshots(companyId, lastPeriod.getId());
            LOG.info("Snapshots de clôture créés : {} (bilan + CR) pour company={} period={}",
                snapshots.size(), companyId, lastPeriod.getId());

            contribution.incrementWriteCount(snapshots.size() + 1);
            return RepeatStatus.FINISHED;
        }
    }

    /**
     * Listener qui alimente le {@link TenantContext} (companyId + userId technique
     * batch) à partir des {@code JobParameters} avant l'exécution du Job, et le nettoie
     * après (afterJob). Le ThreadLocal est ainsi disponible pour les filtres Hibernate
     * pendant toute la durée du Job.
     */
    static class TenantContextJobListener implements JobExecutionListener {
        @Override
        public void beforeJob(JobExecution jobExecution) {
            UUID companyId = (UUID) jobExecution.getJobParameters().getParameters()
                .get("companyId").getValue();
            TenantContext.setCompanyId(companyId);
            // userId technique pour created_by/updated_by (les repositories utilisent
            // @CreatedBy via SpringSecurityAuditorAware — en batch, on positionne un UUID
            // technique pour éviter un NPE).
            TenantContext.setUserId(UUID.randomUUID());
            LOG.info("Batch Job {} démarré pour company={}",
                jobExecution.getJobInstance().getJobName(), companyId);
        }

        @Override
        public void afterJob(JobExecution jobExecution) {
            LOG.info("Batch Job {} terminé : status={}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus());
            TenantContext.clear();
        }
    }
}

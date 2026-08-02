package jo.accountant.app.batch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints d'administration des Jobs Spring Batch (paie + clôture d'exercice).
 *
 * <p>Les Jobs ne sont PAS auto-démarrés au boot ({@code spring.batch.job.enabled=false})
 * — ils sont lancés manuellement via ces endpoints (ou par cron ShedLock en production).
 *
 * <p>Deux endpoints :
 * <ul>
 *   <li>{@code POST /api/v1/companies/{companyId}/admin/batch/payroll?runId=} —
 *       lance {@code payrollJob} sur la campagne {@code runId}.</li>
 *   <li>{@code POST /api/v1/companies/{companyId}/admin/batch/closing?fiscalYearId=} —
 *       lance {@code fiscalYearClosingJob} sur l'exercice {@code fiscalYearId}.</li>
 * </ul>
 *
 * <p>Le {@link TenantContext} est positionné avant l'appel {@code jobLauncher.run(...)}
 * (JobLauncher synchrone par défaut — le Job s'exécute dans le même thread). Le listener
 * {@code TenantContextJobListener} présent dans {@link BatchConfig} re-positionne le
 * TenantContext au début du Job par sécurité (au cas où l'on bascule sur un JobLauncher
 * asynchrone à l'avenir).
 *
 * <p>Rôle requis : ADMIN (les opérations batch de paie et de clôture sont critiques).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/admin/batch")
@Tag(name = "Batch Admin", description = "Jobs Spring Batch (paie + clôture annuelle) — V63")
public class BatchController {

    private static final Logger LOG = LoggerFactory.getLogger(BatchController.class);

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final Job payrollJob;
    private final Job fiscalYearClosingJob;
    private final RoleChecker roleChecker;

    public BatchController(JobLauncher jobLauncher,
                            JobExplorer jobExplorer,
                            Job payrollJob,
                            Job fiscalYearClosingJob,
                            RoleChecker roleChecker) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.payrollJob = payrollJob;
        this.fiscalYearClosingJob = fiscalYearClosingJob;
        this.roleChecker = roleChecker;
    }

    /**
     * Lance le Job de paie {@code payrollJob} sur la campagne {@code runId}.
     *
     * <p>Le Job lit tous les employés ACTIVE de l'entreprise, calcule le brut→net via
     * {@code PayrollCalculator} (chunk de 50) et génère les payslips. À la fin, le
     * {@code PayrollRun} est mis à jour (totaux + status CALCULATED).
     *
     * @param companyId identifiant de l'entreprise (tenant)
     * @param runId     identifiant de la campagne (PayrollRun) à calculer
     * @return 202 Accepted avec le JobExecutionId (le Job s'exécute synchrone — au retour,
     *         le Job est déjà terminé)
     */
    @Operation(summary = "Lancer le Job batch de paie (payrollJob)",
        description = "Calcule les payslips pour tous les employés ACTIVE via PayrollCalculator "
            + "(chunk de 50, retry 3× sur IOException). Le Job est synchrone — au retour, "
            + "les payslips sont générés et le PayrollRun est en status CALCULATED.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Job lancé / terminé (synchrone) — BatchJobResponse retourné",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = BatchJobResponse.class),
                examples = @ExampleObject(name = "PayrollJob réussi", value = """
                    {
                      "jobExecutionId": 42,
                      "jobName": "payrollJob",
                      "status": "COMPLETED",
                      "exitCode": "COMPLETED",
                      "createdAt": "2026-07-28T10:30:00"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis) ou invitation en attente",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "PayrollRun introuvable pour ce companyId",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Job déjà en cours pour ce runId",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Échec du calcul (sortie FAILED — voir logs pour détails)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/payroll")
    public ResponseEntity<BatchJobResponse> launchPayroll(
        @PathVariable UUID companyId,
        @RequestParam UUID runId,
        @CurrentUser UUID userId) throws Exception {

        roleChecker.ensureRole(companyId, "ADMIN");
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(userId);
        try {
            JobParameters params = new JobParametersBuilder()
                .addString("companyId", companyId.toString())
                .addString("runId", runId.toString())
                .addDate("launchedAt", new Date())  // unicité — évite JobInstanceAlreadyComplete
                .toJobParameters();

            LOG.info("Lancement payrollJob : company={} run={} user={}", companyId, runId, userId);
            JobExecution exec = jobLauncher.run(payrollJob, params);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new BatchJobResponse(
                    exec.getId(),
                    exec.getJobInstance().getJobName(),
                    exec.getStatus().name(),
                    exec.getExitStatus().getExitCode(),
                    exec.getCreateTime()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Lance le Job de clôture d'exercice {@code fiscalYearClosingJob} sur l'exercice
     * {@code fiscalYearId}.
     *
     * <p>Le Job exécute {@code AccountingEngineService.closeFiscalYear} (écriture de
     * clôture + écriture d'ouverture N+1) puis {@code FinancialStatementsService
     * .createClosingSnapshots} (fige le bilan + le compte de résultat). Retry 1× en cas
     * d'erreur (la clôture est idempotente).
     *
     * @param companyId     identifiant de l'entreprise (tenant)
     * @param fiscalYearId  identifiant de l'exercice à clôturer
     * @return 202 Accepted avec le JobExecutionId
     */
    @Operation(summary = "Lancer le Job batch de clôture d'exercice (fiscalYearClosingJob)",
        description = "Exécute closeFiscalYear (écriture de clôture + ouverture N+1) puis "
            + "createClosingSnapshots (bilan + CR figés). Retry 1× — la clôture est idempotente.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Job lancé / terminé (synchrone)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = BatchJobResponse.class),
                examples = @ExampleObject(name = "ClosingJob réussi", value = """
                    {
                      "jobExecutionId": 58,
                      "jobName": "fiscalYearClosingJob",
                      "status": "COMPLETED",
                      "exitCode": "COMPLETED",
                      "createdAt": "2026-07-28T23:59:00"
                    }
                    """))),
        @ApiResponse(responseCode = "200", description = "Clôture déjà effectuée pour cet exercice (idempotence)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = BatchJobResponse.class),
                examples = @ExampleObject(name = "Closing déjà fait", value = """
                    {
                      "jobExecutionId": null,
                      "jobName": "fiscalYearClosingJob",
                      "status": "COMPLETED",
                      "exitCode": "ALREADY_COMPLETE",
                      "createdAt": "2026-07-28T23:59:30"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Exercice fiscal introuvable ou n'appartient pas à ce companyId",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Exercice déjà clôturé (status=CLOSED) — ré-ouverture interdite",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Échec de la clôture (sortie FAILED — vérifier balance débit=crédit)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/closing")
    public ResponseEntity<BatchJobResponse> launchClosing(
        @PathVariable UUID companyId,
        @RequestParam UUID fiscalYearId,
        @CurrentUser UUID userId) throws Exception {

        roleChecker.ensureRole(companyId, "ADMIN");
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(userId);
        try {
            JobParameters params = new JobParametersBuilder()
                .addString("companyId", companyId.toString())
                .addString("fiscalYearId", fiscalYearId.toString())
                .addDate("launchedAt", new Date())  // unicité
                .toJobParameters();

            LOG.info("Lancement fiscalYearClosingJob : company={} fy={} user={}",
                companyId, fiscalYearId, userId);
            JobExecution exec;
            try {
                exec = jobLauncher.run(fiscalYearClosingJob, params);
            } catch (JobInstanceAlreadyCompleteException ex) {
                // Idempotence — si l'exercice a déjà été clôturé par un lancement précédent
                // avec les mêmes paramètres (cas rare, RunIdIncrementer + launchedAt évite
                // normalement la collision), on retourne une réponse "déjà complété".
                LOG.warn("fiscalYearClosingJob déjà exécuté pour ces paramètres — retour COMPLETED");
                return ResponseEntity.status(HttpStatus.OK)
                    .body(new BatchJobResponse(
                        null,
                        fiscalYearClosingJob.getName(),
                        "COMPLETED",
                        "ALREADY_COMPLETE",
                        LocalDateTime.now()));
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new BatchJobResponse(
                    exec.getId(),
                    exec.getJobInstance().getJobName(),
                    exec.getStatus().name(),
                    exec.getExitStatus().getExitCode(),
                    exec.getCreateTime()));
        } finally {
            TenantContext.clear();
        }
    }

    /** DTO de réponse pour les endpoints batch. */
    public record BatchJobResponse(
        Long jobExecutionId,
        String jobName,
        String status,
        String exitCode,
        LocalDateTime createdAt
    ) {}
}

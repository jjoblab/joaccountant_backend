package jo.accountant.chartofaccounts.listener;

import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.entity.Company;
import jo.accountant.company.event.CompanyWizardCompletedEvent;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * V8.2 Phase 4 (audit Z.ai 2026-07-31) — Listener event-driven pour l'auto-initialisation
 * du plan comptable après finalisation du wizard.
 *
 * <p>Écoute {@link CompanyWizardCompletedEvent} en phase {@link TransactionPhase#AFTER_COMMIT}
 * — l'initialisation ne se fait que si la transaction de {@code completeWizard} a réussi.
 *
 * <p><b>Idempotence</b> : si le plan comptable a déjà été initialisé (par l'activation
 * atomique dans {@code AccountingProvisioningPortImpl.provision}), {@link ConflictException}
 * ({@code CHART_OF_ACCOUNTS_ALREADY_INITIALIZED}) est catchée silencieusement. Ce listener
 * est donc un <b>filet de sécurité</b> + un point d'extension pour de futurs modules.
 *
 * <p><b>Architecture hybride</b> : en V8.2 Phase 2, l'activation atomique est faite
 * <em>directement</em> dans {@code completeWizard} via {@code AccountingProvisioningPort} (synchrone,
 * dans la même transaction). Ce listener (Phase 4) est une <em>alternative</em> event-driven qui
 * permettrait, à terme, de supprimer l'appel direct et de découpler complètement
 * {@code :company} des modules comptables. Pour l'instant, les deux approches coexistent —
 * le listener est no-op dans le flux normal (l'activation directe a déjà fait le travail).
 *
 * <p><b>Pour de futurs modules</b> : un nouveau module (ex: PAYROLL pour pré-seed des
 * cotisations CNSS/OFATMA) peut écouter {@code CompanyWizardCompletedEvent} de la même
 * manière, sans modifier {@code CompanyService}. C'est le pattern d'extension recommandé.
 *
 * <p><b>Async</b> : l'initialisation est exécutée en async (executor {@code audit-async-executor})
 * pour ne pas bloquer la réponse HTTP de {@code POST /wizard/complete}. Le caller reçoit
 * {@code CompanyWizardResult} immédiatement, l'initialisation différée se fait en arrière-plan.
 */
@Component
public class ChartOfAccountsAutoInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(ChartOfAccountsAutoInitializer.class);

    private final ChartOfAccountsService chartOfAccountsService;
    private final CompanyRepository companyRepository;

    public ChartOfAccountsAutoInitializer(ChartOfAccountsService chartOfAccountsService,
                                          CompanyRepository companyRepository) {
        this.chartOfAccountsService = chartOfAccountsService;
        this.companyRepository = companyRepository;
    }

    @Async("audit-async-executor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyWizardCompleted(CompanyWizardCompletedEvent event) {
        try {
            Company company = companyRepository.findById(event.companyId())
                .orElseThrow(() -> new NotFoundException("Company", event.companyId()));

            if (company.getAccountingFrameworkId() == null) {
                LOG.warn("CompanyWizardCompleted sans accountingFrameworkId pour company {} — skip COA init",
                    event.companyId());
                return;
            }

            ChartOfAccountsService.InitializeResult result = chartOfAccountsService.initialize(
                company.getId(),
                company.getAccountingFrameworkId(),
                null,  // template null — requis seulement pour IFRS (FREE numbering)
                company.getBusinessTypeCode());
            LOG.info("ChartOfAccountsAutoInitializer : {} comptes créés pour company {}",
                result.accountsCreated(), company.getId());

        } catch (ConflictException ex) {
            // CHART_OF_ACCOUNTS_ALREADY_INITIALIZED — idempotent, expected dans le flux normal
            // V8.2 (l'activation atomique a déjà fait le travail)
            LOG.debug("ChartOfAccountsAutoInitializer : plan déjà initialisé pour company {} (idempotent)",
                event.companyId());
        } catch (Exception ex) {
            // Ne pas faire échouer l'async — log only
            LOG.error("ChartOfAccountsAutoInitializer : erreur pour company {} — {}",
                event.companyId(), ex.getMessage(), ex);
        }
    }
}

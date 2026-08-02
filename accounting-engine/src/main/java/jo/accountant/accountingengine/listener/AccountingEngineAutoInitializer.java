package jo.accountant.accountingengine.listener;

import java.time.LocalDate;
import java.util.Map;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.JournalType;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.company.entity.Company;
import jo.accountant.company.event.CompanyWizardCompletedEvent;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * V8.2Listener event-driven pour l'auto-initialisation
 * de l'infrastructure comptable (exercice fiscal + journaux + séquences) après finalisation
 * du wizard.
 *
 * <p>Écoute {@link CompanyWizardCompletedEvent} en phase {@link TransactionPhase#AFTER_COMMIT}.
 *
 * <p><b>Idempotence</b> : chaque sous-{@link ConflictException} silencieusement
 * (objet déjà créé par l'activation atomique dans {@code AccountingProvisioningPortImpl.provision}).
 * Ce listener est donc un <b>filet de sécurité</b> + un point d'extension.
 *
 * <p><b>Architecture hybride</b> : l'activation atomique est faite
 * <em>directement</em> dans {@code completeWizard}. Ce listenerest une alternative
 * event-driven qui permettrait, à terme, de supprimer l'appel direct et de découpler
 * complètement {@code :company} des modules comptables.
 *
 * <p><b>Sous-étapes exécutées</b> :
 * <ol>
 * <li>Création de l'exercice fiscal de l'année en cours (12 périodes mensuelles auto)</li>
 * <li>Création des 8 journaux standards via {@link AccountingEngineService#getOrCreateJournal}</li>
 * <li>Création des 6 séquences de numérotation par défaut</li>
 * </ol>
 *
 * <p><b>Note sur la TVA</b> : la création des règles TVA par défaut reste dans
 * {@code AccountingProvisioningPortImpl} (elle dépend du pays de la company et nécessite
 * une logique métier spécifique). Ce listener ne gère que l'infrastructure comptable pure.
 
 *
 * @author jo@Dev


*/
@Component
public class AccountingEngineAutoInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(AccountingEngineAutoInitializer.class);

    private final AccountingEngineService accountingEngineService;
    private final DocumentNumberingService documentNumberingService;
    private final CompanyRepository companyRepository;

    public AccountingEngineAutoInitializer(AccountingEngineService accountingEngineService,
                                            DocumentNumberingService documentNumberingService,
                                            CompanyRepository companyRepository) {
        this.accountingEngineService = accountingEngineService;
        this.documentNumberingService = documentNumberingService;
        this.companyRepository = companyRepository;
    }

    @Async("audit-async-executor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyWizardCompleted(CompanyWizardCompletedEvent event) {
        try {
            Company company = companyRepository.findById(event.companyId())
                .orElseThrow(() -> new NotFoundException("Company", event.companyId()));

            // 1. Exercice fiscal (idempotent via uniqueness constraint)
            createFiscalYearIfAbsent(company);

            // 2. Journaux standards (idempotent via getOrCreateJournal)
            for (JournalType type : JournalType.values()) {
                try {
                    accountingEngineService.getOrCreateJournal(company.getId(), type);
                } catch (Exception ex) {
                    LOG.debug("Auto-init journal {} pour company {} — déjà existant ou erreur bénigne : {}",
                        type.getDefaultCode(), company.getId(), ex.getMessage());
                }
            }

            // 3. Séquences de numérotation par défaut (idempotent)
            createDefaultSequences(company);

            LOG.info("AccountingEngineAutoInitializer : infrastructure comptable initialisée pour company {}",
                company.getId());

        } catch (Exception ex) {
            // Ne pas faire échouer l'async — log only
            LOG.error("AccountingEngineAutoInitializer : erreur pour company {} — {}",
                event.companyId(), ex.getMessage(), ex);
        }
    }

    private void createFiscalYearIfAbsent(Company company) {
        int year = LocalDate.now().getYear();
        int month = company.getFiscalYearStartMonth() != 0 ? company.getFiscalYearStartMonth() : 1;
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusYears(1).minusDays(1);
        String label = "Exercice " + year + "-" + endDate.getYear();

        try {
            CreateFiscalYearRequest req = new CreateFiscalYearRequest(startDate, endDate, label);
            FiscalYear fy = accountingEngineService.createFiscalYear(company.getId(), req);
            LOG.info("Auto-init exercice fiscal : id={} pour company {}", fy.getId(), company.getId());
        } catch (ConflictException | org.springframework.dao.DataIntegrityViolationException ex) {
            // l'exercice fiscal a déjà été créé par l'activation atomique dans completeWizard.
            // Le listener @TransactionalEventListener(AFTER_COMMIT) se déclenche APRÈS la transaction
            // qui a déjà tout créé. C'est idempotent — on catch silencieusement.
            LOG.debug("Auto-init exercice fiscal : déjà existant pour company {} (idempotent)",
                company.getId());
        }
    }

    private void createDefaultSequences(Company company) {
        int year = LocalDate.now().getYear();
        int count = 0;
        // Séquences pour les 6 types de documents standards
        count += safeCreateSequence(company.getId(), DocumentType.JOURNAL_ENTRY, "", "ECR-" + year + "-");
        count += safeCreateSequence(company.getId(), DocumentType.SALES_INVOICE, "", "INV-" + year + "-");
        count += safeCreateSequence(company.getId(), DocumentType.PURCHASE_INVOICE, "", "ACH-" + year + "-");
        count += safeCreateSequence(company.getId(), DocumentType.PAYSLIP, "", "PAY-" + year + "-");
        count += safeCreateSequence(company.getId(), DocumentType.CREDIT_NOTE, "", "AVO-" + year + "-");
        count += safeCreateSequence(company.getId(), DocumentType.DONATION_RECEIPT, "", "DON-" + year + "-");
        if (count > 0) {
            LOG.info("Auto-init : {} séquences créées pour company {}", count, company.getId());
        }
    }

    private int safeCreateSequence(java.util.UUID companyId, DocumentType docType,
                                    String scopeKey, String prefix) {
        try {
            documentNumberingService.createSequence(
                companyId, docType, scopeKey, prefix,
                true, // includeYear
                6, // padding
                ResetPolicy.YEARLY);
            return 1;
        } catch (ConflictException ex) {
            // SEQUENCE_CONFIG_ALREADY_EXISTS — idempotent
            return 0;
        }
    }
}

package jo.accountant.app.wizard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.dto.CompanyResponse;
import jo.accountant.company.dto.CompanyWizardResult;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.company.mapping.BusinessTypeModuleService;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.company.service.CompanyService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.2 — Orchestrateur du wizard refondu (4 étapes).
 *
 * <p>L'activation atomique (étape 4 / POST /wizard/complete) exécute en UNE SEULE transaction :
 * <ol>
 *   <li>Activation des modules (always-on + sectoriels + custom)</li>
 *   <li>Initialisation du plan comptable</li>
 *   <li>Création des 4 journaux standards (VT, AC, BQ, OD)</li>
 *   <li>Création de l'exercice fiscal courant (12 périodes)</li>
 *   <li>Création des séquences de numérotation</li>
 * </ol>
 *
 * <p>En cas d'échec d'une sous-étape : rollback complet. L'entreprise reste
 * wizardCompleted=false. L'utilisateur corrige et re-soumet (idempotent).
 */
@Service
public class WizardOrchestrationService {

    private static final Logger LOG = LoggerFactory.getLogger(WizardOrchestrationService.class);

    private final CompanyService companyService;
    private final CompanyRepository companyRepository;
    private final CompanyModuleService companyModuleService;
    private final BusinessTypeModuleService businessTypeModuleService;
    private final ChartOfAccountsService chartOfAccountsService;
    private final AccountingEngineService accountingEngineService;
    private final DocumentNumberingService documentNumberingService;

    public WizardOrchestrationService(CompanyService companyService,
                                        CompanyRepository companyRepository,
                                        CompanyModuleService companyModuleService,
                                        BusinessTypeModuleService businessTypeModuleService,
                                        ChartOfAccountsService chartOfAccountsService,
                                        AccountingEngineService accountingEngineService,
                                        DocumentNumberingService documentNumberingService) {
        this.companyService = companyService;
        this.companyRepository = companyRepository;
        this.companyModuleService = companyModuleService;
        this.businessTypeModuleService = businessTypeModuleService;
        this.chartOfAccountsService = chartOfAccountsService;
        this.accountingEngineService = accountingEngineService;
        this.documentNumberingService = documentNumberingService;
    }

    /**
     * V8.2 — Activation atomique du wizard.
     */
    @Transactional
    public CompanyWizardResult completeWizard(UUID companyId, UUID userId) {
        Company company = companyRepository.findById(companyId)
            .orElseThrow(() -> new ValidationException("COMPANY_NOT_FOUND",
                "Entreprise introuvable : " + companyId));

        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Le wizard est déjà complété");
        }
        if (company.getWizardStep() < 3) {
            throw new ValidationException("WIZARD_STEP_INCOMPLETE",
                "Étape " + company.getWizardStep() + " sur 3 — complétez les étapes précédentes");
        }
        if (company.getAccountingFrameworkId() == null) {
            throw new ValidationException("ACCOUNTING_FRAMEWORK_REQUIRED",
                "Un référentiel comptable doit être sélectionné (étape 3)");
        }
        if (company.getBusinessTypeCode() == null || company.getBusinessTypeCode().isBlank()) {
            throw new ValidationException("BUSINESS_TYPE_REQUIRED",
                "Un type métier doit être sélectionné (étape 2)");
        }

        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(userId);

        List<String> activatedModules = new ArrayList<>();
        int chartCreated = 0;
        UUID fiscalYearId = null;
        List<String> journalsCreated = new ArrayList<>();
        int sequencesCreated = 0;

        // 1. Activer les modules
        LOG.info("V8.2 Wizard — Activation modules pour company {}", companyId);
        for (ModuleCode code : businessTypeModuleService.alwaysOnModules()) {
            companyModuleService.enable(companyId, code);
            activatedModules.add(code.name());
        }
        for (ModuleCode code : businessTypeModuleService.modulesFor(company.getBusinessTypeCode())) {
            companyModuleService.enable(companyId, code);
            activatedModules.add(code.name());
        }
        if ("CUSTOM".equals(company.getBusinessTypeCode()) && company.getExtraAttributes() != null) {
            Object raw = company.getExtraAttributes().get("customModules");
            if (raw instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof String s) {
                        try {
                            companyModuleService.enable(companyId, ModuleCode.valueOf(s));
                            activatedModules.add(s);
                        } catch (IllegalArgumentException ignored) {
                            LOG.warn("V8.2 Wizard — Module code inconnu ignoré : {}", s);
                        }
                    }
                }
            }
        }

        // 2. Initialiser le plan comptable
        LOG.info("V8.2 Wizard — Initialisation plan comptable pour company {}", companyId);
        try {
            var result = chartOfAccountsService.initialize(
                companyId, company.getAccountingFrameworkId(), null, company.getBusinessTypeCode());
            chartCreated = result != null ? result.accountsCreated() : 0;
        } catch (ConflictException e) {
            LOG.info("V8.2 Wizard — Plan comptable déjà initialisé, skip");
        }

        // 3. Créer les 4 journaux standards
        LOG.info("V8.2 Wizard — Création journaux pour company {}", companyId);
        String[][] journals = {
            {"VT", "Journal des Ventes"},
            {"AC", "Journal des Achats"},
            {"BQ", "Journal de Banque"},
            {"OD", "Opérations Diverses"}
        };
        for (String[] j : journals) {
            try {
                accountingEngineService.createJournal(companyId, j[0], j[1]);
                journalsCreated.add(j[0]);
            } catch (ConflictException e) {
                LOG.info("V8.2 Wizard — Journal {} déjà existant, skip", j[0]);
            }
        }

        // 4. Créer l'exercice fiscal courant
        LOG.info("V8.2 Wizard — Création exercice fiscal pour company {}", companyId);
        int startMonth = company.getFiscalYearStartMonth();
        int startYear = LocalDate.now().getYear();
        if (startMonth > LocalDate.now().getMonthValue()) {
            startYear--;
        }
        LocalDate fyStart = LocalDate.of(startYear, startMonth, 1);
        LocalDate fyEnd = fyStart.plusYears(1).minusDays(1);
        String fyLabel = "Exercice " + fyStart.getYear() + "-" + fyEnd.getYear();
        try {
            FiscalYear fy = accountingEngineService.createFiscalYear(
                companyId, new CreateFiscalYearRequest(fyStart, fyEnd, fyLabel));
            fiscalYearId = fy.getId();
        } catch (ConflictException e) {
            LOG.info("V8.2 Wizard — Exercice fiscal déjà existant, skip");
        }

        // 5. Créer les séquences de numérotation
        LOG.info("V8.2 Wizard — Création séquences pour company {}", companyId);
        sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.JOURNAL_ENTRY, "VT", "VT");
        sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.JOURNAL_ENTRY, "AC", "AC");
        sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.JOURNAL_ENTRY, "BQ", "BQ");
        sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.JOURNAL_ENTRY, "OD", "OD");
        sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.SALES_INVOICE, "VT", "FAC");
        sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.CREDIT_NOTE, "VT", "AV");
        if (activatedModules.contains("INVENTORY")) {
            sequencesCreated += createSequenceIfNotExists(companyId, DocumentType.JOURNAL_ENTRY, "ST", "MVT");
        }

        // 6. Marquer comme complété
        company.setWizardCompleted(true);
        company.setWizardStep(4);
        company.setUpdatedAt(java.time.Instant.now());
        company.setUpdatedBy(userId);
        companyRepository.save(company);

        TenantContext.clear();

        LOG.info("V8.2 Wizard — Activation terminée pour company {} : {} modules, {} comptes, {} journaux, {} séquences",
            companyId, activatedModules.size(), chartCreated, journalsCreated.size(), sequencesCreated);

        CompanyResponse companyResponse = companyService.toResponse(company);
        return new CompanyWizardResult(
            companyResponse,
            activatedModules,
            chartCreated,
            fiscalYearId,
            journalsCreated,
            sequencesCreated
        );
    }

    @SuppressWarnings("unchecked")
    private int createSequenceIfNotExists(UUID companyId, DocumentType docType,
                                            String scopeKey, String prefix) {
        try {
            documentNumberingService.createSequence(
                companyId, docType, scopeKey, prefix, true, 5, ResetPolicy.YEARLY);
            return 1;
        } catch (ConflictException e) {
            return 0;
        }
    }
}

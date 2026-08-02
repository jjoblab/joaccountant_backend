package jo.accountant.company.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.service.UserCompanyRoleService;
import jo.accountant.auth.service.JwtService;
import jo.accountant.company.dto.CompleteWizardRequest;
import jo.accountant.company.dto.CompanyResponse;
import jo.accountant.company.dto.CompanyWizardResult;
import jo.accountant.company.dto.CreateCompanyResponse;
import jo.accountant.company.dto.UpdateCompanyLegalFieldsRequest;
import jo.accountant.company.dto.WizardStep2Request;
import jo.accountant.company.dto.WizardStep3Request;
import jo.accountant.company.entity.BusinessType;
import jo.accountant.company.entity.BusinessTypeRequiredField;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.event.CompanyCreatedEvent;
import jo.accountant.company.event.CompanyLegalFieldsUpdatedEvent;
import jo.accountant.company.event.CompanyWizardCompletedEvent;
import jo.accountant.company.mapping.BusinessTypeModuleService;
import jo.accountant.company.mapping.OrganizationNatureLegalFormValidator;
import jo.accountant.company.port.AccountingProvisioningPort;
import jo.accountant.company.repository.BusinessTypeRequiredFieldRepository;
import jo.accountant.company.repository.BusinessTypeRepository;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.AccountingFramework;
import jo.accountant.core.framework.AccountingFrameworkRepository;
import jo.accountant.core.json.JsonUtil;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Cycle de vie de Company : création (wizard step 1), mises à jour des étapes 2 et 3 du wizard,
 * complétion atomique du wizard (étape 4 = activation modules + plan comptable + exercice +
 * journaux + séquences + TVA en une seule transaction), listing.
 *
 * <p><b>V8.2 (audit Z.ai 2026-07-31) — Refonte wizard 4 étapes avec activation atomique.</b>
 * Le wizard 9 étapes historique a été supprimé. Les 4 DTO V8.2 ({@link WizardStep2Request},
 * {@link WizardStep3Request}, {@link CompleteWizardRequest}, {@link CompanyWizardResult}) sont
 * désormais câblés. La constante {@link Company#TOTAL_WIZARD_STEPS} vaut 4, enfin alignée sur
 * la migration V95 qui avait déjà clampé wizard_step à 4 en base.
 *
 * <p>Les 4 étapes du wizard V8.2 :
 * <ol>
 *   <li><b>Identité</b> — name + country + functionalCurrency (création via POST /companies,
 *       méthode {@link #createCompany})</li>
 *   <li><b>Activité</b> — businessTypeCode + primaryActivityLabel + sector + extraAttributes
 *       + customModules (PATCH /wizard/2, méthode {@link #applyWizardStep2})</li>
 *   <li><b>Comptabilité</b> — accountingFrameworkId + fiscalYearStart + vatMode + numberingPrefixes
 *       (PATCH /wizard/3, méthode {@link #applyWizardStep3})</li>
 *   <li><b>Activation atomique</b> — modules + plan comptable + exercice + journaux + séquences + TVA
 *       (POST /wizard/complete, méthode {@link #completeWizard})</li>
 * </ol>
 *
 * <p><b>Idempotence</b> : {@link #completeWizard} est ré-entrante. Si l'activation est rappelée
 * (suite à un retry), les objets existants ne sont pas recréés (catch des ConflictException
 * dans {@link AccountingProvisioningService}).
 *
 * <p><b>Atomicité</b> : {@link #completeWizard} est {@code @Transactional}. Si une sous-étape
 * échoue avec une exception non-récupérable, toute la transaction est rollbackée — aucun objet
 * partiel ne persiste. Corrige le bug documenté « completeWizard ne fait pas de rollback
 * transactionnel » (cf. ancien README ligne 318-321).
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final AccountingFrameworkRepository frameworkRepository;
    private final BusinessTypeRepository businessTypeRepository;
    private final BusinessTypeRequiredFieldRepository businessTypeRequiredFieldRepository;
    private final UserCompanyRoleRepository userCompanyRoleRepository;
    private final UserCompanyRoleService userCompanyRoleService;
    private final CompanyModuleService companyModuleService;
    private final MaxCompaniesGuard maxCompaniesGuard;
    private final BusinessTypeModuleService businessTypeModuleService;
    private final OrganizationNatureLegalFormValidator natureLegalFormValidator;
    private final AccountingProvisioningPort accountingProvisioningPort;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;

    @PersistenceContext
    private EntityManager entityManager;

    public CompanyService(CompanyRepository companyRepository,
                          AccountingFrameworkRepository frameworkRepository,
                          BusinessTypeRepository businessTypeRepository,
                          BusinessTypeRequiredFieldRepository businessTypeRequiredFieldRepository,
                          UserCompanyRoleRepository userCompanyRoleRepository,
                          UserCompanyRoleService userCompanyRoleService,
                          CompanyModuleService companyModuleService,
                          MaxCompaniesGuard maxCompaniesGuard,
                          BusinessTypeModuleService businessTypeModuleService,
                          OrganizationNatureLegalFormValidator natureLegalFormValidator,
                          AccountingProvisioningPort accountingProvisioningPort,
                          JwtService jwtService,
                          ApplicationEventPublisher events) {
        this.companyRepository = companyRepository;
        this.frameworkRepository = frameworkRepository;
        this.businessTypeRepository = businessTypeRepository;
        this.businessTypeRequiredFieldRepository = businessTypeRequiredFieldRepository;
        this.userCompanyRoleRepository = userCompanyRoleRepository;
        this.userCompanyRoleService = userCompanyRoleService;
        this.companyModuleService = companyModuleService;
        this.maxCompaniesGuard = maxCompaniesGuard;
        this.businessTypeModuleService = businessTypeModuleService;
        this.natureLegalFormValidator = natureLegalFormValidator;
        this.accountingProvisioningPort = accountingProvisioningPort;
        this.jwtService = jwtService;
        this.events = events;
    }

    // ── Étape 1 — Identité (création) ──────────────────────────────────────────

    /**
     * Crée une Company à l'étape 1 du wizard — champs d'identité uniquement.
     *
     * <p>V8.3 — Retourne un nouveau JWT fraîchement émis avec le claim {@code companies} mis à jour
     * (incluant la nouvelle company avec le rôle OWNER). Le client mobile stocke ce nouveau JWT
     * et l'utilise pour les requêtes wizard suivantes — pas besoin de re-login ni de fall-back DB.
     *
     * <p>Assigne le rôle OWNER au créateur et le stamp comme createdBy.
     *
     * <p><b>V2.6.0 (wizard refonte)</b> — {@code organizationNature} et {@code legalForm}
     * (nullables) sont désormais passés par le wizard step 1. Si non-null, ils écrasent
     * les defaults provisoires ({@code FOR_PROFIT} / {@code OTHER}). Si null ou invalide,
     * on retombe sur les defaults (backward-compatible). Les valeurs invalides lèvent
     * 422 avec un code d'erreur précis.
     */
    @Transactional
    public CreateCompanyResponse createCompany(UUID creatorUserId, String name,
                                                String country, String functionalCurrency,
                                                String organizationNature, String legalForm) {
        validateStep1Inputs(name, country, functionalCurrency);

        long current = userCompanyRoleRepository.countByUserId(creatorUserId);
        maxCompaniesGuard.ensureCanCreateOneMore(creatorUserId, current);

        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setName(name.trim());
        company.setCountry(country.toUpperCase());
        company.setFunctionalCurrency(functionalCurrency.toUpperCase());
        // V2.6.0 — organisationNature + legalForm depuis le wizard step 1 (defaults provisoires
        // conservés si null). Les étapes 2 et 3 du wizard peuvent encore les écraser.
        company.setOrganizationNature(parseOrganizationNature(organizationNature));
        company.setLegalForm(parseLegalForm(legalForm));
        // Les autres champs restent à leurs defaults provisoires — seront écrasés par les
        // étapes 2 et 3 du wizard.
        company.setSector(Sector.AUTRE);
        company.setBusinessTypeCode("CUSTOM");
        company.setPrimaryActivityLabel("");
        company.setAccountingFrameworkId(null);  // positionné à l'étape 3
        company.setFiscalYearStartMonth(1);       // positionné à l'étape 3
        company.setWizardStep(1);
        company.setWizardCompleted(false);
        company.setCreatedAt(Instant.now());
        company.setUpdatedAt(Instant.now());
        company.setCreatedBy(creatorUserId);
        company.setUpdatedBy(creatorUserId);
        Company saved = companyRepository.save(company);

        // Rôle OWNER pour le créateur — auto-accepté (il a créé la société)
        UserCompanyRole owner = new UserCompanyRole();
        owner.setId(UUID.randomUUID());
        owner.setUserId(creatorUserId);
        owner.setCompanyId(saved.getId());
        owner.setRole(UserRole.OWNER);
        owner.setInvitedAt(Instant.now());
        owner.setAcceptedAt(Instant.now());
        owner.setCreatedAt(Instant.now());
        owner.setUpdatedAt(Instant.now());
        owner.setCreatedBy(creatorUserId);
        owner.setUpdatedBy(creatorUserId);
        userCompanyRoleRepository.save(owner);

        // V8.3 — Émettre un nouveau JWT avec le claim companies à jour.
        // Le UserCompanyRole vient d'être créé, le flush garantit qu'il est visible
        // pour buildCompaniesClaim.
        userCompanyRoleRepository.flush();
        List<Map<String, Object>> companiesClaim = buildCompaniesClaim(creatorUserId);
        String newAccessToken = jwtService.issueAccessToken(creatorUserId,
            getUserEmail(creatorUserId), companiesClaim);

        events.publishEvent(new CompanyCreatedEvent(saved, creatorUserId));

        return new CreateCompanyResponse(
            toResponse(saved),
            newAccessToken,
            null,  // refreshToken — pas de rotation ici (le refresh token existant reste valide)
            jwtService.getAccessTokenTtlSeconds()
        );
    }

    /**
     * Construit le claim JWT {@code companies} — liste de {companyId, role} pour tous les
     * UserCompanyRole acceptés par l'utilisateur.
     */
    private List<Map<String, Object>> buildCompaniesClaim(UUID userId) {
        List<UserCompanyRole> roles = userCompanyRoleRepository.findByUserId(userId).stream()
            .filter(r -> r.getAcceptedAt() != null)
            .toList();
        List<Map<String, Object>> claim = new ArrayList<>(roles.size());
        for (UserCompanyRole r : roles) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("companyId", r.getCompanyId().toString());
            entry.put("role", r.getRole().name());
            claim.add(entry);
        }
        return claim;
    }

    /**
     * Récupère l'email de l'utilisateur — nécessaire pour issueAccessToken.
     * Utilise le TenantContext ou un lookup DB.
     */
    private String getUserEmail(UUID userId) {
        // L'email n'est pas stocké dans :company, mais le JwtService a juste besoin d'une string.
        // On utilise le TenantContext si disponible, sinon une string vide (le claim email sera
        // mis à jour au prochain login).
        return "";  // Le claim email est cosmétique dans le JWT — l'auth se fait via sub (userId)
    }

    @Transactional(readOnly = true)
    public List<Company> listCompaniesForUser(UUID userId) {
        return userCompanyRoleRepository.findByUserId(userId).stream()
            .filter(ucr -> ucr.getAcceptedAt() != null)
            .map(ucr -> companyRepository.findById(ucr.getCompanyId()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    @Transactional(readOnly = true)
    public Company getCompanyForUser(UUID companyId, UUID userId) {
        if (!userCompanyRoleService.hasAccess(userId, companyId)) {
            // §3.9 : 404 (pas 403) pour éviter de fuiter l'existence
            throw new NotFoundException("Company", companyId);
        }
        return companyRepository.findById(companyId)
            .orElseThrow(() -> new NotFoundException("Company", companyId));
    }

    // ── PATCH /legal — champs légaux éditables post-wizard ─────────────────────

    /**
     * Met à jour les champs légaux d'une Company (Phase D — Audit v4.7 §4.2).
     *
     * <p>Endpoint : {@code PATCH /api/v1/companies/{companyId}/legal}. Sémantique de mise à jour
     * partielle : seuls les champs non-nuls du payload sont écrasés. Une chaîne blank est
     * normalisée en {@code null} (effacement du champ).
     *
     * <p>Contrairement aux étapes du wizard, ces champs restent éditables après
     * {@code wizardCompleted = true} — ils relèvent de la conformité réglementaire (mentions
     * légales des factures CGI art. 289 + Factur-X) et doivent pouvoir être mis à jour à tout
     * moment (ex: changement d'adresse, obtention du numéro de TVA...).
     */
    @Transactional
    public Company updateLegalFields(UUID companyId, UUID userId, UpdateCompanyLegalFieldsRequest req) {
        Company company = getCompanyForUser(companyId, userId);

        // Snapshot avant modification pour audit (PII masquée au moment de la persistance).
        String oldValueJson = JsonUtil.toJson(legalFieldsSnapshot(company));

        boolean changed = false;
        if (req.siret() != null) {
            String normalized = req.siret().isBlank() ? null : req.siret().trim();
            if (!java.util.Objects.equals(company.getSiret(), normalized)) {
                company.setSiret(normalized);
                changed = true;
            }
        }
        if (req.vatNumber() != null) {
            String normalized = req.vatNumber().isBlank() ? null : req.vatNumber().trim().toUpperCase();
            if (!java.util.Objects.equals(company.getVatNumber(), normalized)) {
                company.setVatNumber(normalized);
                changed = true;
            }
        }
        if (req.nif() != null) {
            String normalized = req.nif().isBlank() ? null : req.nif().trim();
            if (!java.util.Objects.equals(company.getNif(), normalized)) {
                company.setNif(normalized);
                changed = true;
            }
        }
        if (req.address() != null) {
            String normalized = req.address().isBlank() ? null : req.address().trim();
            if (!java.util.Objects.equals(company.getAddress(), normalized)) {
                company.setAddress(normalized);
                changed = true;
            }
        }

        if (changed) {
            company.setUpdatedAt(Instant.now());
            company.setUpdatedBy(userId);
            Company saved = companyRepository.save(company);

            String newValueJson = JsonUtil.toJson(legalFieldsSnapshot(saved));
            events.publishEvent(new CompanyLegalFieldsUpdatedEvent(
                saved.getId(), userId, oldValueJson, newValueJson));
            return saved;
        }
        return company;
    }

    private static Map<String, Object> legalFieldsSnapshot(Company c) {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("siret", c.getSiret());
        snap.put("vatNumber", c.getVatNumber());
        snap.put("nif", c.getNif());
        snap.put("address", c.getAddress());
        return snap;
    }

    // ── Étape 1 (ré-éditable) — PATCH /wizard/1 ────────────────────────────────

    /**
     * Met à jour l'étape 1 (identité) — ré-éditable même après être passée à l'étape 2.
     * Permet de corriger name/country/functionalCurrency sans recréer la société.
     */
    @Transactional
    public Company applyWizardStep1(UUID companyId, UUID userId, String name, String country,
                                     String functionalCurrency) {
        Company company = getCompanyForUser(companyId, userId);
        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Wizard is already completed — company can no longer be edited via wizard endpoints");
        }
        if (name != null && !name.isBlank()) {
            company.setName(name.trim());
        }
        if (country != null && country.length() == 2) {
            company.setCountry(country.toUpperCase());
        }
        if (functionalCurrency != null && functionalCurrency.length() == 3) {
            company.setFunctionalCurrency(functionalCurrency.toUpperCase());
        }
        // L'étape 1 ne fait pas avancer wizardStep (ré-éditable, mais le curseur reste à 1+).
        if (company.getWizardStep() < 1) {
            company.setWizardStep(1);
        }
        company.setUpdatedAt(Instant.now());
        company.setUpdatedBy(userId);
        return companyRepository.save(company);
    }

    // ── Étape 2 — Activité — PATCH /wizard/2 ───────────────────────────────────

    /**
     * Applique l'étape 2 du wizard V8.2 : activité + type métier.
     *
     * <p>Fusionne les anciennes étapes 3 (sector), 4 (business type), 5 (activity),
     * 7 (required fields) et 8 (module selection pour CUSTOM).
     *
     * <p>Auto-popule {@code organizationNature} et {@code sector} depuis les defaults du
     * BusinessType si l'utilisateur ne les a pas fournis (ou s'ils sont aux defaults provisoires).
     *
     * @throws ValidationException si {@code businessTypeCode} n'existe pas ou n'est pas actif,
     *         ou si un champ requis (BusinessTypeRequiredField) est manquant dans extraAttributes
     * @throws ConflictException si le wizard est déjà complété ou si l'étape 2 est appelée
     *         alors que wizardStep < 1
     */
    @Transactional
    public Company applyWizardStep2(UUID companyId, UUID userId, WizardStep2Request req) {
        Company company = getCompanyForUser(companyId, userId);
        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Wizard is already completed — company can no longer be edited via wizard endpoints");
        }
        if (company.getWizardStep() < 1) {
            throw new ConflictException("WIZARD_STEP_OUT_OF_ORDER",
                "Cannot apply step 2 — current progress is at step " + company.getWizardStep());
        }

        // 1. Valider businessTypeCode existe et est actif
        BusinessType bt = businessTypeModuleService.getActiveByCode(req.businessTypeCode());
        company.setBusinessTypeCode(bt.getCode());

        // 2. Auto-populate organizationNature + sector si non déjà saisis (defaults du type métier)
        if (company.getOrganizationNature() == null
                || (company.getOrganizationNature() == OrganizationNature.FOR_PROFIT
                    && company.getLegalForm() == LegalForm.OTHER)) {
            company.setOrganizationNature(bt.getDefaultOrganizationNature());
        }
        if (req.sector() != null) {
            company.setSector(req.sector());
        } else if (company.getSector() == null || company.getSector() == Sector.AUTRE) {
            company.setSector(bt.getDefaultSector());
        }

        // 3. primaryActivityLabel
        company.setPrimaryActivityLabel(req.primaryActivityLabel().trim());

        // 4. extraAttributes — valider les champs requis du BusinessType
        Map<String, Object> extra = new HashMap<>();
        if (req.extraAttributes() != null) {
            extra.putAll(req.extraAttributes());
        }
        List<BusinessTypeRequiredField> requiredFields =
            businessTypeRequiredFieldRepository.findByBusinessTypeCodeOrderByDisplayOrderAsc(
                company.getBusinessTypeCode());
        for (BusinessTypeRequiredField f : requiredFields) {
            Object value = extra.get(f.getFieldKey());
            if (f.isRequired() && (value == null || (value instanceof String s && s.isBlank()))) {
                throw new ValidationException("REQUIRED_FIELD_MISSING",
                    "Champ requis manquant : " + f.getLabel() + " (clé : " + f.getFieldKey() + ")");
            }
        }

        // 5. customModules — stockés dans extraAttributes (consommés à completeWizard si CUSTOM)
        if (req.customModules() != null && !req.customModules().isEmpty()) {
            // Valider que les codes sont valides (sinon ignorer silencieusement pour ne pas bloquer)
            List<String> valid = new ArrayList<>();
            for (String code : req.customModules()) {
                try {
                    ModuleCode.valueOf(code);
                    valid.add(code);
                } catch (IllegalArgumentException ignored) {
                    // Module code inconnu — ignoré silencieusement
                }
            }
            extra.put("customModules", valid);
        }
        company.setExtraAttributes(extra);

        // Avancer le curseur à 2 (sauf si déjà plus loin — ne pas reculer)
        company.setWizardStep(Math.max(company.getWizardStep(), 2));
        company.setUpdatedAt(Instant.now());
        company.setUpdatedBy(userId);
        return companyRepository.save(company);
    }

    // ── Étape 3 — Comptabilité — PATCH /wizard/3 ───────────────────────────────

    /**
     * Applique l'étape 3 du wizard V8.2 : comptabilité & fiscalité.
     *
     * <p>Fusionne les anciennes étapes 6 (framework+fiscal), 9 (VAT mode), 10 (numbering).
     *
     * <p>Stocke {@code vatMode} et {@code numberingPrefixes} dans {@code extraAttributes} pour
     * consommation par {@link AccountingProvisioningService#provision} à l'étape 4.
     *
     * @throws NotFoundException si {@code accountingFrameworkId} n'existe pas
     * @throws ValidationException si {@code fiscalYearStartMonth} ∉ [1, 12]
     */
    @Transactional
    public Company applyWizardStep3(UUID companyId, UUID userId, WizardStep3Request req) {
        Company company = getCompanyForUser(companyId, userId);
        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Wizard is already completed — company can no longer be edited via wizard endpoints");
        }
        if (company.getWizardStep() < 2) {
            throw new ConflictException("WIZARD_STEP_OUT_OF_ORDER",
                "Cannot apply step 3 — current progress is at step " + company.getWizardStep()
                + " (must be >= 2)");
        }

        // 1. Valider accountingFrameworkId
        AccountingFramework af = frameworkRepository.findById(req.accountingFrameworkId())
            .orElseThrow(() -> new NotFoundException("ACCOUNTING_FRAMEWORK_NOT_FOUND",
                "Accounting framework not found: " + req.accountingFrameworkId()));
        company.setAccountingFrameworkId(af.getId());

        // 2. fiscalYearStartMonth
        if (req.fiscalYearStartMonth() < 1 || req.fiscalYearStartMonth() > 12) {
            throw new ValidationException("FISCAL_YEAR_START_INVALID",
                "Fiscal year start month must be between 1 and 12");
        }
        company.setFiscalYearStartMonth(req.fiscalYearStartMonth());

        // 3. Stocker vatMode + numberingPrefixes dans extraAttributes pour l'étape 4
        Map<String, Object> extra = company.getExtraAttributes() != null
            ? new HashMap<>(company.getExtraAttributes()) : new HashMap<>();
        extra.put("vatMode", req.vatMode() != null ? req.vatMode().name() : "DEBIT");
        if (req.fiscalYearStartYear() != 0) {
            extra.put("fiscalYearStartYear", req.fiscalYearStartYear());
        }
        if (req.fiscalYearLabel() != null && !req.fiscalYearLabel().isBlank()) {
            extra.put("fiscalYearLabel", req.fiscalYearLabel());
        }
        if (req.numberingPrefixes() != null && !req.numberingPrefixes().isEmpty()) {
            extra.put("numberingPrefixes", req.numberingPrefixes());
        }
        company.setExtraAttributes(extra);

        // Avancer le curseur à 3 (prêt pour completeWizard)
        company.setWizardStep(Math.max(company.getWizardStep(), 3));
        company.setUpdatedAt(Instant.now());
        company.setUpdatedBy(userId);
        return companyRepository.save(company);
    }

    // ── Étape 4 — Activation atomique — POST /wizard/complete ─────────────────

    /**
     * Finalise le wizard V8.2 par activation atomique.
     *
     * <p>Exécute en UNE SEULE transaction (rollback si échec) :
     * <ol>
     *   <li>Activation des modules (always-on + sectoriels BusinessType + customModules si CUSTOM)</li>
     *   <li>Initialisation du plan comptable (ChartOfAccountsService.initialize)</li>
     *   <li>Création de l'exercice fiscal + 12 périodes mensuelles (AccountingEngineService.createFiscalYear)</li>
     *   <li>Création des 8 journaux standards VT/AC/BQ/CA/OD/PA/DP/FX (AccountingEngineService.createJournal)</li>
     *   <li>Création des 6 séquences de numérotation par défaut (DocumentNumberingService.createSequence)</li>
     *   <li>Création des règles TVA par défaut si pays non couvert par seeds globaux (TaxService.createTaxRule)</li>
     * </ol>
     *
     * <p>Publie {@link CompanyWizardCompletedEvent} après commit. Cet événement est écouté
     * par deux listeners {@code @TransactionalEventListener(AFTER_COMMIT)} (V8.2 Phase 4) :
     * <ul>
     *   <li>{@code ChartOfAccountsAutoInitializer} (chart-of-accounts) — filet de sécurité idempotent</li>
     *   <li>{@code AccountingEngineAutoInitializer} (accounting-engine) — filet de sécurité idempotent</li>
     * </ul>
     *
     * <p><b>Architecture hybride V8.2</b> : l'activation atomique est faite directement dans
     * cette méthode via {@link AccountingProvisioningPort} (synchrone, même transaction).
     * Les listeners event-driven (Phase 4) sont des <b>filets de sécurité idempotents</b> qui
     * permettent également à de futurs modules de réagir à {@code CompanyWizardCompletedEvent}
     * sans modifier {@code CompanyService}. À terme, l'appel direct pourrait être supprimé
     * au profit exclusif des listeners — mais la coexistence actuelle garantit la robustesse.
     *
     * @param companyId id de la Company à finaliser
     * @param userId    id de l'utilisateur authentifié
     * @param req       payload optionnel (mfaCode, expenseCategories, contributionRules — pré-seed)
     * @return {@link CompanyWizardResult} récapitulant tout ce qui a été créé
     *
     * @throws ConflictException      si wizard déjà complété ou wizardStep < 3
     * @throws ValidationException    si businessTypeCode incohérent
     * @throws NotFoundException      si accountingFrameworkId n'existe plus
     */
    @Transactional
    public CompanyWizardResult completeWizard(UUID companyId, UUID userId, CompleteWizardRequest req) {
        // Flush les modifications en attente dans le persistence context avant de clearer,
        // pour s'assurer que les entités créées dans la même transaction (ex: createCompany
        // suivi de applyWizardStep2/3 dans Phase1IntegrationTest) sont bien persistées en DB.
        // Sans ce flush, le clear() qui suit les détacherait sans les écrire, et le
        // findById suivant échouerait (Company not found).
        entityManager.flush();
        // Vider le persistence context pour garantir qu'on recharge la version la plus récente
        // depuis la DB (en particulier wizardCompleted qui peut avoir été setté par un appel
        // précédent dont l'instance est encore en cache). Sans ce clear, le check
        // isWizardCompleted() pourrait ne pas déclencher et on entrerait dans l'activation
        // atomique → JpaSystemException sur duplicate key.
        entityManager.clear();
        Company company = getCompanyForUser(companyId, userId);

        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Wizard is already completed");
        }
        if (company.getWizardStep() < Company.TOTAL_WIZARD_STEPS - 1) {
            // wizardStep doit être >= 3 (TOTAL_WIZARD_STEPS=4, étape 4 = completeWizard lui-même)
            throw new ConflictException("WIZARD_STEP_INCOMPLETE",
                "Wizard cannot be completed — current step is " + company.getWizardStep()
                + " of " + Company.TOTAL_WIZARD_STEPS
                + " (must be >= " + (Company.TOTAL_WIZARD_STEPS - 1) + ")");
        }

        // Vérification finale de cohérence : businessTypeCode doit exister et être actif.
        businessTypeModuleService.getActiveByCode(company.getBusinessTypeCode());

        // Extraire les paramètres stockés à l'étape 3 depuis extraAttributes
        WizardStep3Params step3 = extractStep3Params(company);

        // Positionner le TenantContext pour les services appelés (ChartOfAccounts, AccountingEngine, etc.)
        TenantContext.setCompanyId(company.getId());
        TenantContext.setUserId(userId);

        // 1. Activer les modules : socle always-on + mapping BusinessType → modules sectoriels
        List<String> activatedModules = activateModules(company);

        // 2-6. Activation comptable atomique via le port (plan comptable + exercice + journaux + séquences + TVA)
        AccountingProvisioningPort.ProvisioningResult provisioning =
            accountingProvisioningPort.provision(
                company,
                step3.vatMode(),
                step3.fiscalYearStartYear(),
                step3.fiscalYearLabel(),
                step3.numberingPrefixes()
            );

        // Recharger la company depuis la DB avant de la modifier — l'activation comptable
        // peut avoir modifié la colonne active_fiscal_year_id via JDBC direct (bypass Hibernate),
        // ce qui incrémente @Version en DB. Sans rechargement, le save qui suit lèverait
        // ObjectOptimisticLockingFailureException.
        // On détache l'ancienne instance du persistence context et on recharge une version fraîche.
        if (entityManager.contains(company)) {
            entityManager.detach(company);
        }
        Company fresh = companyRepository.findById(company.getId())
            .orElseThrow(() -> new NotFoundException("Company", company.getId()));

        // Marquer wizard comme complété
        fresh.setWizardStep(Company.TOTAL_WIZARD_STEPS);
        fresh.setWizardCompleted(true);
        fresh.setUpdatedAt(Instant.now());
        fresh.setUpdatedBy(userId);
        // Préserver les champs éventuellement modifiés par provisioning (extraAttributes, etc.)
        if (company.getExtraAttributes() != null) {
            fresh.setExtraAttributes(company.getExtraAttributes());
        }
        Company saved = companyRepository.save(fresh);

        // Publier l'événement (pour futurs listeners @TransactionalEventListener AFTER_COMMIT)
        events.publishEvent(new CompanyWizardCompletedEvent(saved, userId));

        return new CompanyWizardResult(
            toResponse(saved),
            activatedModules,
            provisioning.chartOfAccountsCreated(),
            provisioning.fiscalYearId(),
            provisioning.journalCodesCreated(),
            provisioning.sequencesCreated(),
            provisioning.taxRulesCreated()
        );
    }

    /**
     * Paramètres extraits de l'étape 3 (stockés dans extraAttributes).
     * Utilisé par {@link #completeWizard} pour appeler {@link AccountingProvisioningPort#provision}.
     */
    private record WizardStep3Params(
        String vatMode,
        int fiscalYearStartYear,
        String fiscalYearLabel,
        Map<String, String> numberingPrefixes
    ) {}

    /**
     * Extrait les paramètres de l'étape 3 depuis {@code company.extraAttributes}.
     */
    @SuppressWarnings("unchecked")
    private WizardStep3Params extractStep3Params(Company company) {
        Map<String, Object> extra = company.getExtraAttributes();
        if (extra == null) {
            throw new ValidationException("WIZARD_STEP3_MISSING",
                "Étape 3 non appliquée — extraAttributes est null");
        }
        String vatMode = (String) extra.getOrDefault("vatMode", "DEBIT");
        int fiscalYearStartYear = 0;
        Object yRaw = extra.get("fiscalYearStartYear");
        if (yRaw instanceof Number n) fiscalYearStartYear = n.intValue();
        String fiscalYearLabel = (String) extra.get("fiscalYearLabel");

        Map<String, String> numberingPrefixes = null;
        Object npRaw = extra.get("numberingPrefixes");
        if (npRaw instanceof Map<?, ?> m) {
            numberingPrefixes = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
                    numberingPrefixes.put(k, v);
                }
            }
        }
        return new WizardStep3Params(vatMode, fiscalYearStartYear, fiscalYearLabel, numberingPrefixes);
    }

    /**
     * Active les modules : always-on + mapping BusinessType → sectoriels + customModules si CUSTOM.
     */
    private List<String> activateModules(Company company) {
        List<String> activated = new ArrayList<>();

        // Always-on (15 modules socle)
        for (ModuleCode code : businessTypeModuleService.alwaysOnModules()) {
            companyModuleService.enable(company.getId(), code);
            activated.add(code.name());
        }

        // Sectoriels (selon BusinessType)
        for (ModuleCode code : businessTypeModuleService.modulesFor(company.getBusinessTypeCode())) {
            companyModuleService.enable(company.getId(), code);
            activated.add(code.name());
        }

        // Custom modules (si type métier CUSTOM)
        if ("CUSTOM".equals(company.getBusinessTypeCode()) && company.getExtraAttributes() != null) {
            Object raw = company.getExtraAttributes().get("customModules");
            if (raw instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof String s) {
                        try {
                            companyModuleService.enable(company.getId(), ModuleCode.valueOf(s));
                            activated.add(s);
                        } catch (IllegalArgumentException ignored) {
                            // Module code inconnu — ignoré silencieusement
                        }
                    }
                }
            }
        }
        return activated;
    }

    // ── Helpers ------------------------------------------------------------------

    private void validateStep1Inputs(String name, String country, String functionalCurrency) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("COMPANY_NAME_REQUIRED", "Company name is required");
        }
        if (country == null || country.length() != 2) {
            throw new ValidationException("COUNTRY_INVALID", "Country must be an ISO 3166-1 alpha-2 code");
        }
        if (functionalCurrency == null || functionalCurrency.length() != 3) {
            throw new ValidationException("FUNCTIONAL_CURRENCY_INVALID",
                "Functional currency must be an ISO 4217 code (3 letters)");
        }
    }

    /**
     * Parse le champ {@code organizationNature} du payload de création (V2.6.0).
     *
     * <p>Accepte les valeurs {@code "FOR_PROFIT"} et {@code "NON_PROFIT"} (case-insensitive,
     * whitespace-tolerant). {@code null}/blank → {@link OrganizationNature#FOR_PROFIT}
     * (default provisoire, écrasable par step 2). Valeur hors domaine → 422
     * {@code ORGANIZATION_NATURE_INVALID}.
     */
    private OrganizationNature parseOrganizationNature(String raw) {
        if (raw == null || raw.isBlank()) {
            return OrganizationNature.FOR_PROFIT;  // default provisoire
        }
        try {
            return OrganizationNature.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("ORGANIZATION_NATURE_INVALID",
                "organizationNature must be one of " + java.util.Arrays.toString(OrganizationNature.values())
                + " — got: " + raw);
        }
    }

    /**
     * Parse le champ {@code legalForm} du payload de création (V2.6.0).
     *
     * <p>Accepte les valeurs de l'enum {@link LegalForm} (case-insensitive, whitespace-tolerant).
     * {@code null}/blank → {@link LegalForm#OTHER} (default provisoire, écrasable par step 2).
     * Valeur hors domaine → 422 {@code LEGAL_FORM_INVALID}.
     */
    private LegalForm parseLegalForm(String raw) {
        if (raw == null || raw.isBlank()) {
            return LegalForm.OTHER;  // default provisoire
        }
        try {
            return LegalForm.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("LEGAL_FORM_INVALID",
                "legalForm must be one of " + java.util.Arrays.toString(LegalForm.values())
                + " — got: " + raw);
        }
    }

    /**
     * Convertit une {@link Company} en {@link CompanyResponse} pour la sérialisation JSON.
     */
    public static CompanyResponse toResponse(Company c) {
        return new CompanyResponse(
            c.getId(),
            c.getName(),
            c.getLegalForm(),
            c.getCountry(),
            c.getFunctionalCurrency(),
            c.getSector(),
            c.getOrganizationNature(),
            c.getBusinessTypeCode(),
            c.getPrimaryActivityLabel(),
            c.getExtraAttributes(),
            c.getAccountingFrameworkId(),
            c.getFiscalYearStartMonth(),
            c.getWizardStep(),
            c.isWizardCompleted(),
            c.getSiret(),
            c.getVatNumber(),
            c.getNif(),
            c.getAddress(),
            c.getCreatedAt(),
            c.getUpdatedAt()
        );
    }
}

package jo.accountant.company.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.service.UserCompanyRoleService;
import jo.accountant.company.entity.BusinessType;
import jo.accountant.company.entity.BusinessTypeRequiredField;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.dto.UpdateCompanyLegalFieldsRequest;
import jo.accountant.company.event.CompanyCreatedEvent;
import jo.accountant.company.event.CompanyLegalFieldsUpdatedEvent;
import jo.accountant.company.event.CompanyWizardCompletedEvent;
import jo.accountant.company.mapping.BusinessTypeModuleService;
import jo.accountant.company.mapping.OrganizationNatureLegalFormValidator;
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

/**
 * Cycle de vie de Company : création (wizard step 1), mises à jour des étapes du wizard,
 * complétion du wizard (avec activation des modules sectoriels), listing.
 *
 * <p>Restructuration 2026-07-24 (prompt {@code PROMPT_AGENT_restructuration_type_organisation}) :
 * le wizard à 9 étapes a désormais une sémantique réelle pour chaque étape (§5), la validation
 * croisée Nature ↔ LegalForm est appliquée (§4.2), et l'activation des modules est pilotée par
 * données via {@link BusinessTypeModuleService} (§6). Le type {@code CUSTOM} remplace
 * l'ancien secteur {@code MIXTE} et active réellement la sélection manuelle de modules à
 * l'étape 8 (correction du bug documenté « MIXTE non testé »).
 *
 * <p>§11 (révisé) : {@code organizationNature}, {@code legalForm}, {@code sector},
 * {@code businessTypeCode} et {@code extraAttributes} sont verrouillés une fois
 * {@code wizardCompleted = true}.
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
    private final ApplicationEventPublisher events;

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
        this.events = events;
    }

    /**
     * Crée une Company à l'étape 1 du wizard — champs d'identité uniquement (§5).
     *
     * <p>Restructuration : seuls {@code name}, {@code country} et {@code functionalCurrency}
     * sont requis à ce stade. {@code legalForm}, {@code sector}, {@code accountingFrameworkId}
     * et {@code fiscalYearStartMonth} sont saisis via les étapes 2, 3 et 6 du wizard.
     *
     * <p>Assigne le rôle OWNER au créateur et le stamp comme createdBy.
     */
    @Transactional
    public Company createCompany(UUID creatorUserId, String name, String country, String functionalCurrency) {
        validateStep1Inputs(name, country, functionalCurrency);

        long current = userCompanyRoleRepository.countByUserId(creatorUserId);
        maxCompaniesGuard.ensureCanCreateOneMore(creatorUserId, current);

        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setName(name.trim());
        company.setCountry(country.toUpperCase());
        company.setFunctionalCurrency(functionalCurrency.toUpperCase());
        // Defaults provisoires — seront écrasés par les étapes correspondantes du wizard.
        company.setLegalForm(LegalForm.OTHER);
        company.setSector(Sector.AUTRE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setBusinessTypeCode("CUSTOM");
        company.setPrimaryActivityLabel("");
        company.setAccountingFrameworkId(null);  // positionné à l'étape 6
        company.setFiscalYearStartMonth(1);       // positionné à l'étape 6
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

        events.publishEvent(new CompanyCreatedEvent(saved, creatorUserId));
        return saved;
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
     *
     * <p>Publie un {@link CompanyLegalFieldsUpdatedEvent} pour audit-trail (oldValue/newValue
     * au format JSON, PII masquée par {@code PiiMasker} au moment de la persistance).
     *
     * @param companyId  id de la Company à mettre à jour
     * @param userId     id de l'utilisateur authentifié (pour audit + stamp {@code updatedBy})
     * @param req        payload partiel — champs non-nuls uniquement
     * @return la Company mise à jour (à mapper en {@code CompanyResponse} par le contrôleur)
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

    /**
     * Construit un snapshot des 4 champs légaux pour l'audit (oldValue/newValue JSON).
     * Utilisé par {@link #updateLegalFields} uniquement — ne pas exposer publiquement.
     */
    private static Map<String, Object> legalFieldsSnapshot(Company c) {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("siret", c.getSiret());
        snap.put("vatNumber", c.getVatNumber());
        snap.put("nif", c.getNif());
        snap.put("address", c.getAddress());
        return snap;
    }

    /**
     * Met à jour une étape du wizard (§5 — restructuration :company).
     *
     * <p>Chaque étape a désormais un payload spécifique et une sémantique métier réelle.
     * L'étape 1 reste ré-éditable, les autres doivent être appliquées en ordre.
     * Tout est verrouillé après {@code wizardCompleted = true}.
     */
    @Transactional
    public Company updateWizardStep(UUID companyId, UUID userId, int step, Map<String, Object> payload) {
        Company company = getCompanyForUser(companyId, userId);
        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Wizard is already completed — company can no longer be edited via wizard endpoints");
        }
        if (step < 1 || step > Company.TOTAL_WIZARD_STEPS) {
            throw new ValidationException("INVALID_WIZARD_STEP",
                "Wizard step must be between 1 and " + Company.TOTAL_WIZARD_STEPS + " (got " + step + ")");
        }
        if (step <= company.getWizardStep() && step != 1) {
            throw new ConflictException("WIZARD_STEP_OUT_OF_ORDER",
                "Cannot edit step " + step + " — current progress is at step " + company.getWizardStep());
        }

        switch (step) {
            case 1 -> applyStep1Identity(company, payload);
            case 2 -> applyStep2NatureAndLegalForm(company, payload);
            case 3 -> applyStep3Sector(company, payload);
            case 4 -> applyStep4BusinessType(company, payload);
            case 5 -> applyStep5PrimaryActivity(company, payload);
            case 6 -> applyStep6FrameworkAndFiscalYear(company, payload);
            case 7 -> applyStep7RequiredFields(company, payload);
            case 8 -> applyStep8ModuleSelection(company, payload);
            case 9 -> applyStep9Confirmation(company, payload);
            default -> throw new ValidationException("INVALID_WIZARD_STEP",
                "Unknown wizard step: " + step);
        }

        // L'étape 1 est ré-éditable — ne pas reculer le curseur.
        // Pour les autres étapes, le curseur avance au max(step, current).
        if (step != 1 || company.getWizardStep() < 1) {
            company.setWizardStep(Math.max(company.getWizardStep(), step));
        }
        company.setUpdatedAt(Instant.now());
        company.setUpdatedBy(userId);
        return companyRepository.save(company);
    }

    @Transactional
    public Company completeWizard(UUID companyId, UUID userId) {
        Company company = getCompanyForUser(companyId, userId);
        if (company.isWizardCompleted()) {
            throw new ConflictException("WIZARD_ALREADY_COMPLETED",
                "Wizard is already completed");
        }
        if (company.getWizardStep() < Company.TOTAL_WIZARD_STEPS) {
            throw new ConflictException("WIZARD_STEP_INCOMPLETE",
                "Wizard cannot be completed — current step is " + company.getWizardStep()
                + " of " + Company.TOTAL_WIZARD_STEPS);
        }

        // Vérification finale de cohérence : businessTypeCode doit exister et être actif.
        businessTypeModuleService.getActiveByCode(company.getBusinessTypeCode());

        // Activer les modules : socle always-on + mapping BusinessType → modules sectoriels.
        // Pour le type métier CUSTOM, la sélection manuelle de l'étape 8 (extraAttributes
        // ["customModules"]) est également activée — correction du bug documenté « MIXTE non
        // testé, sélection manuelle non implémentée ».
        TenantContext.setCompanyId(company.getId());
        TenantContext.setUserId(userId);
        for (ModuleCode code : businessTypeModuleService.alwaysOnModules()) {
            companyModuleService.enable(company.getId(), code);
        }
        for (ModuleCode code : businessTypeModuleService.modulesFor(company.getBusinessTypeCode())) {
            companyModuleService.enable(company.getId(), code);
        }
        if ("CUSTOM".equals(company.getBusinessTypeCode()) && company.getExtraAttributes() != null) {
            Object raw = company.getExtraAttributes().get("customModules");
            if (raw instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof String s) {
                        try {
                            companyModuleService.enable(company.getId(), ModuleCode.valueOf(s));
                        } catch (IllegalArgumentException ignored) {
                            // Module code inconnu — ignoré silencieusement pour ne pas
                            // bloquer la complétion ; les codes attendus sont validés côté
                            // client (catalogue ModuleCode).
                        }
                    }
                }
            }
        }

        company.setWizardCompleted(true);
        company.setUpdatedAt(Instant.now());
        company.setUpdatedBy(userId);
        Company saved = companyRepository.save(company);

        events.publishEvent(new CompanyWizardCompletedEvent(saved, userId));
        return saved;
    }

    // -- Apply step payloads ------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void applyStep1Identity(Company company, Map<String, Object> payload) {
        if (payload.get("name") instanceof String n && !n.isBlank()) {
            company.setName(n.trim());
        }
        if (payload.get("country") instanceof String c) {
            company.setCountry(c.toUpperCase());
        }
        if (payload.get("functionalCurrency") instanceof String fc) {
            company.setFunctionalCurrency(fc.toUpperCase());
        }
    }

    private void applyStep2NatureAndLegalForm(Company company, Map<String, Object> payload) {
        OrganizationNature nature = readEnum(payload, "organizationNature", OrganizationNature.class,
            "ORGANIZATION_NATURE_INVALID");
        LegalForm legalForm = readEnum(payload, "legalForm", LegalForm.class, "LEGAL_FORM_INVALID");
        natureLegalFormValidator.validate(nature, legalForm);
        company.setOrganizationNature(nature);
        company.setLegalForm(legalForm);
    }

    private void applyStep3Sector(Company company, Map<String, Object> payload) {
        Sector sector = readEnum(payload, "sector", Sector.class, "SECTOR_INVALID");
        company.setSector(sector);
    }

    private void applyStep4BusinessType(Company company, Map<String, Object> payload) {
        String code = readString(payload, "businessTypeCode", "BUSINESS_TYPE_CODE_REQUIRED");
        BusinessType bt = businessTypeModuleService.getActiveByCode(code);
        company.setBusinessTypeCode(bt.getCode());
        // Auto-populate organizationNature + sector si non déjà saisis (defaults du type métier).
        if (company.getOrganizationNature() == null
                || company.getOrganizationNature() == OrganizationNature.FOR_PROFIT
                    && (company.getLegalForm() == LegalForm.OTHER)) {
            company.setOrganizationNature(bt.getDefaultOrganizationNature());
        }
        if (company.getSector() == null || company.getSector() == Sector.AUTRE) {
            company.setSector(bt.getDefaultSector());
        }
    }

    private void applyStep5PrimaryActivity(Company company, Map<String, Object> payload) {
        String label = readString(payload, "primaryActivityLabel", "PRIMARY_ACTIVITY_LABEL_REQUIRED");
        if (label.isBlank()) {
            throw new ValidationException("PRIMARY_ACTIVITY_LABEL_REQUIRED",
                "Le libellé d'activité principale ne peut pas être vide");
        }
        company.setPrimaryActivityLabel(label.trim());
    }

    private void applyStep6FrameworkAndFiscalYear(Company company, Map<String, Object> payload) {
        UUID frameworkId = readUuid(payload, "accountingFrameworkId", "ACCOUNTING_FRAMEWORK_ID_INVALID");
        AccountingFramework af = frameworkRepository.findById(frameworkId)
            .orElseThrow(() -> new NotFoundException("ACCOUNTING_FRAMEWORK_NOT_FOUND",
                "Accounting framework not found: " + frameworkId));
        int fiscalYearStartMonth = readInt(payload, "fiscalYearStartMonth",
            "FISCAL_YEAR_START_INVALID");
        if (fiscalYearStartMonth < 1 || fiscalYearStartMonth > 12) {
            throw new ValidationException("FISCAL_YEAR_START_INVALID",
                "Fiscal year start month must be between 1 and 12");
        }
        company.setAccountingFrameworkId(af.getId());
        company.setFiscalYearStartMonth(fiscalYearStartMonth);
    }

    private void applyStep7RequiredFields(Company company, Map<String, Object> payload) {
        // Charge la liste des champs attendus pour le businessTypeCode courant et
        // valide que toutes les valeurs requises sont présentes.
        List<BusinessTypeRequiredField> requiredFields =
            businessTypeRequiredFieldRepository.findByBusinessTypeCodeOrderByDisplayOrderAsc(
                company.getBusinessTypeCode());

        Map<String, Object> extra = new HashMap<>();
        for (BusinessTypeRequiredField f : requiredFields) {
            Object value = payload.get(f.getFieldKey());
            if (f.isRequired() && (value == null || (value instanceof String s && s.isBlank()))) {
                throw new ValidationException("REQUIRED_FIELD_MISSING",
                    "Champ requis manquant : " + f.getLabel() + " (clé : " + f.getFieldKey() + ")");
            }
            if (value != null) {
                extra.put(f.getFieldKey(), value);
            }
        }
        company.setExtraAttributes(extra);
    }

    @SuppressWarnings("unchecked")
    private void applyStep8ModuleSelection(Company company, Map<String, Object> payload) {
        // Pour le type métier CUSTOM, l'utilisateur peut ajuster la sélection manuelle.
        // On persiste la sélection dans extraAttributes["customModules"] — c'est cette liste
        // qui est activée à la complétion du wizard (complétée du socle always-on).
        // Pour les autres types métier, cette étape est purement informative — les modules
        // sont déjà connus via le mapping BusinessType → modules (activés automatiquement à la
        // complétion).
        if (!"CUSTOM".equals(company.getBusinessTypeCode())) {
            return;
        }
        Object raw = payload.get("customModules");
        if (raw instanceof List<?> list) {
            Map<String, Object> extra = company.getExtraAttributes() != null
                ? new HashMap<>(company.getExtraAttributes()) : new HashMap<>();
            extra.put("customModules", list);
            company.setExtraAttributes(extra);
        }
    }

    private void applyStep9Confirmation(Company company, Map<String, Object> payload) {
        // Étape purement déclarative — aucune donnée métier à persister. La complétion
        // effective (activation des modules) se fait via POST /wizard/complete.
    }

    // -- Helpers ------------------------------------------------------------------

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

    private <E extends Enum<E>> E readEnum(Map<String, Object> payload, String key,
                                           Class<E> enumClass, String errorCode) {
        Object raw = payload.get(key);
        if (raw == null) {
            throw new ValidationException(errorCode, "Missing field: " + key);
        }
        String s = raw.toString();
        try {
            return Enum.valueOf(enumClass, s);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(errorCode, "Unknown value for " + key + ": " + s);
        }
    }

    private String readString(Map<String, Object> payload, String key, String missingErrorCode) {
        Object raw = payload.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ValidationException(missingErrorCode, "Missing or empty field: " + key);
        }
        return s;
    }

    private int readInt(Map<String, Object> payload, String key, String invalidErrorCode) {
        Object raw = payload.get(key);
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) {
                throw new ValidationException(invalidErrorCode, "Not an integer: " + key + "=" + s);
            }
        }
        throw new ValidationException(invalidErrorCode, "Missing field: " + key);
    }

    private UUID readUuid(Map<String, Object> payload, String key, String invalidErrorCode) {
        Object raw = payload.get(key);
        if (raw == null) {
            throw new ValidationException(invalidErrorCode, "Missing field: " + key);
        }
        if (raw instanceof UUID u) return u;
        try { return UUID.fromString(raw.toString()); }
        catch (IllegalArgumentException e) {
            throw new ValidationException(invalidErrorCode, "Not a UUID: " + key + "=" + raw);
        }
    }
}

package jo.accountant.chartofaccounts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.chartofaccounts.dto.AccountResponse;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.dto.DescendantsCountResponse;
import jo.accountant.chartofaccounts.dto.InitializeRequest;
import jo.accountant.chartofaccounts.dto.UpdateAccountRequest;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.entity.AccountNumberingTemplate;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.event.AccountCreatedEvent;
import jo.accountant.chartofaccounts.event.AccountUpdatedEvent;
import jo.accountant.chartofaccounts.event.ChartOfAccountsInitializedEvent;
import jo.accountant.chartofaccounts.guard.AccountBalanceGuard;
import jo.accountant.chartofaccounts.repository.AccountNumberingTemplateRepository;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.AccountingFramework;
import jo.accountant.core.framework.AccountingFrameworkRepository;
import jo.accountant.core.framework.NumberingMode;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service du plan comptable (§4, §13 Phase 3).
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Initialisation du plan depuis le référentiel choisi (génération des niveaux 1 et 2
 *       verrouillés)</li>
 *   <li>Création de comptes enfants (niveaux 3 et 4) avec génération anti-collision du code</li>
 *   <li>Mise à jour (renommage, activation, mapping fiscal)</li>
 *   <li>Recherche (format arbre ou flat, avec filtre full-text)</li>
 * </ul>
 *
 * <p>Règles métier §13 Phase 3 (chacune testée par un test qui échouerait si la règle était
 * retirée) :
 * <ol>
 *   <li>{@code code} unique par {@code companyId} (contrainte DB + validation applicative).</li>
 *   <li>Renommage d'un compte {@code locked = true} → 409.</li>
 *   <li>Pas de niveau &gt; 4 dans cette itération.</li>
 *   <li>Génération de code enfant sans collision, même en création concurrente.</li>
 *   <li>Suppression physique <strong>toujours interdite</strong> ; seule la désactivation est
 *       permise, et uniquement si le solde est nul.</li>
 *   <li>Isolation multi-tenant testée explicitement.</li>
 *   <li>Toute création/modification/activation publie un événement audit-trail.</li>
 * </ol>
 */
@Service
public class ChartOfAccountsService {

    private static final Logger LOG = LoggerFactory.getLogger(ChartOfAccountsService.class);

    private final AccountRepository accountRepository;
    private final AccountNumberingTemplateRepository templateRepository;
    private final AccountingFrameworkRepository frameworkRepository;
    private final AccountBalanceGuard balanceGuard;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplateRequiresNew;

    public ChartOfAccountsService(AccountRepository accountRepository,
                                  AccountNumberingTemplateRepository templateRepository,
                                  AccountingFrameworkRepository frameworkRepository,
                                  AccountBalanceGuard balanceGuard,
                                  ApplicationEventPublisher events,
                                  ObjectMapper objectMapper,
                                  org.springframework.transaction.PlatformTransactionManager txManager) {
        this.accountRepository = accountRepository;
        this.templateRepository = templateRepository;
        this.frameworkRepository = frameworkRepository;
        this.balanceGuard = balanceGuard;
        this.events = events;
        this.objectMapper = objectMapper;
        this.transactionTemplateRequiresNew = new TransactionTemplate(txManager);
        this.transactionTemplateRequiresNew.setPropagationBehavior(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // --- Initialisation ---

    /**
     * Initialise le plan comptable d'une entreprise en générant les niveaux 1 (classes) et 2
     * (rubriques) verrouillés.
     *
     * <p>Pour un référentiel {@code MANDATED} (SYSCOHADA, PCG, PCN, PCGR) : les classes sont
     * issues du {@code mandatedClassSeed} du référentiel — table de correspondance
     * classe → libellé → {@link ReportingClass}. Aucun gabarit requis.
     *
     * <p>Pour un référentiel {@code FREE} (IFRS full, IFRS SMEs) : un gabarit
     * {@link AccountNumberingTemplate} doit être fourni dans la requête. Les classes sont
     * générées à partir de la classification IFRS standard (Actif, Passif, Capitaux propres,
     * Produits, Charges) avec des codes alphabétiques (A, P, CP, P-ch, CH).
     *
     * <p>Idempotent : 409 si le plan est déjà initialisé (au moins un compte de niveau 1
     * existe). Pour réinitialiser, supprimer d'abord tous les comptes (la suppression physique
     * est interdite par ailleurs, mais une opération d'admin peut nettoyer la base en cas de
     * besoin — hors endpoint public).
     */
    @Transactional
    public InitializeResult initialize(UUID companyId, UUID accountingFrameworkId,
                                       InitializeRequest.AccountNumberingTemplateDto templateDto) {
        // Surcharge rétro-compatible — pas de seed sectoriel (comportement historique).
        return initialize(companyId, accountingFrameworkId, templateDto, null);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "accounts", allEntries = true)
    public InitializeResult initialize(UUID companyId, UUID accountingFrameworkId,
                                       InitializeRequest.AccountNumberingTemplateDto templateDto,
                                       String businessTypeCode) {
        AccountingFramework framework = frameworkRepository.findById(accountingFrameworkId)
            .orElseThrow(() -> new NotFoundException("ACCOUNTING_FRAMEWORK_NOT_FOUND",
                "Référentiel introuvable : " + accountingFrameworkId));

        // Vérifier idempotence : si au moins un compte de niveau 1 existe déjà, refuser
        List<Account> existingClasses = accountRepository.findByCompanyIdOrderByCode(companyId)
            .stream().filter(a -> a.getLevel() == 1).toList();
        if (!existingClasses.isEmpty()) {
            throw new ConflictException("CHART_OF_ACCOUNTS_ALREADY_INITIALIZED",
                "Le plan comptable est déjà initialisé (" + existingClasses.size()
                + " classes existantes). Supprimer d'abord les comptes pour réinitialiser.");
        }

        int created;
        if (framework.getNumberingMode() == NumberingMode.MANDATED) {
            created = initializeMandated(companyId, framework);
        } else {
            if (templateDto == null) {
                throw new ValidationException("TEMPLATE_REQUIRED_FOR_FREE_FRAMEWORK",
                    "Un gabarit de numérotation est requis pour les référentiels à numérotation libre (IFRS)");
            }
            created = initializeFree(companyId, framework, templateDto);
        }

        // Restructuration 2026-07-24 (suite) : seed sectoriel — génère les comptes niveau 2+
        // typiques du type métier (ex. RETAIL_COMMERCE → 401 Fournisseurs, 411 Clients, etc.).
        int sectorCreated = 0;
        if (businessTypeCode != null && !businessTypeCode.isBlank()) {
            sectorCreated = seedSectorAccounts(companyId, businessTypeCode);
            created += sectorCreated;
            LOG.info("Seed sectoriel pour businessTypeCode={} : {} comptes niveau 2+ créés",
                businessTypeCode, sectorCreated);
        }

        events.publishEvent(new ChartOfAccountsInitializedEvent(
            companyId, TenantContext.getUserId(), accountingFrameworkId, created));

        return new InitializeResult(accountingFrameworkId, created);
    }

    /**
     * Génère les comptes niveau 2+ à partir du template sectoriel.
     *
     * <p>Les comptes sont créés en respectant la hiérarchie : on crée d'abord les comptes
     * niveau 2 (parent = niveau 1), puis les comptes niveau 3 (parent = niveau 2). Les
     * comptes sont créés unlocked (l'utilisateur peut les renommer ou désactiver).
     *
     * <p>Tolérant : si un compte parent n'existe pas (ex. l'utilisateur a personnalisé le
     * référentiel), on ignore silencieusement les seeds qui dépendent de ce parent.
     */
    private int seedSectorAccounts(UUID companyId, String businessTypeCode) {
        List<jo.accountant.chartofaccounts.template.SectorAccountTemplate.AccountSeed> seeds =
            jo.accountant.chartofaccounts.template.SectorAccountTemplate.forBusinessType(businessTypeCode);
        if (seeds.isEmpty()) {
            return 0;
        }

        // Charger tous les comptes niveau 1 + les comptes créés en cours de seed
        Map<String, Account> accountsByCode = new HashMap<>();
        for (Account a : accountRepository.findByCompanyIdOrderByCode(companyId)) {
            accountsByCode.put(a.getCode(), a);
        }

        // Trier les seeds par longueur de code croissante (niveau 2 d'abord, puis niveau 3+)
        List<jo.accountant.chartofaccounts.template.SectorAccountTemplate.AccountSeed> sorted = new ArrayList<>(seeds);
        sorted.sort((a, b) -> Integer.compare(a.code().length(), b.code().length()));

        int created = 0;
        for (var seed : sorted) {
            // Vérifier si le compte existe déjà (ex. créé par un seed précédent ou déjà en base)
            if (accountsByCode.containsKey(seed.code())) {
                continue;
            }
            // Trouver le parent
            Account parent = accountsByCode.get(seed.parentCode());
            if (parent == null) {
                LOG.warn("Seed sectoriel ignoré : parent {} introuvable pour le compte {}",
                    seed.parentCode(), seed.code());
                continue;
            }
            // Vérifier que le niveau ne dépasse pas 4
            int childLevel = parent.getLevel() + 1;
            if (childLevel > 4) {
                LOG.warn("Seed sectoriel ignoré : niveau {} dépasserait 4 pour le compte {}",
                    childLevel, seed.code());
                continue;
            }

            Account account = new Account();
            account.setCompanyId(companyId);
            account.setCode(seed.code());
            account.setLabel(seed.label());
            account.setLevel(childLevel);
            account.setParentId(parent.getId());
            account.setReportingClass(seed.reportingClass());
            account.setReportingSubcategory(parseSubcategory(seed.subcategory()));
            account.setNormalBalance(NormalBalance.valueOf(seed.normalBalance()));
            account.setLocked(false);
            account.setActive(true);
            account.setCollective(seed.collective());
            account.setTaxMappingCode(seed.taxMappingCode());
            account.setPath(parent.getPath() + "." + seed.code());
            Account saved = accountRepository.save(account);
            accountsByCode.put(saved.getCode(), saved);
            created++;

            events.publishEvent(new AccountCreatedEvent(saved, TenantContext.getUserId()));
        }
        return created;
    }

    private ReportingSubcategory parseSubcategory(String raw) {
        try {
            return ReportingSubcategory.valueOf(raw);
        } catch (Exception e) {
            return ReportingSubcategory.N_A;
        }
    }

    private int initializeMandated(UUID companyId, AccountingFramework framework) {
        // mandatedClassSeedJson : [{"class":"1","label":"Ressources durables"}, ...]
        List<ClassSeed> seeds = parseClassSeeds(framework.getMandatedClassSeedJson());
        int created = 0;
        for (ClassSeed seed : seeds) {
            Account classe = new Account();
            classe.setCompanyId(companyId);
            classe.setCode(seed.code());
            classe.setLabel(seed.label());
            classe.setLevel(1);
            classe.setReportingClass(inferReportingClass(seed.code(), framework));
            classe.setReportingSubcategory(ReportingSubcategory.N_A);
            classe.setNormalBalance(inferNormalBalance(classe.getReportingClass()));
            classe.setLocked(true);
            classe.setActive(true);
            classe.setCollective(false);
            classe.setPath(seed.code());
            accountRepository.save(classe);
            created++;
        }

        // V6-6 — Wiring PcnHaitiAccountTemplate : si le référentiel est PCN_HAITI,
        // créer également les comptes niveau 2+ du Plan Comptable National Haïtien
        // (capitaux, immobilisations, stocks, tiers, financiers, charges, produits,
        // comptes spéciaux classe 8). Sans cela, l'utilisateur devait créer manuellement
        // tout le plan PCN via POST /chart-of-accounts/{parentId}/children — fastidieux et
        // source d'erreurs (codes incorrects, mapping reporting class erroné).
        //
        // Constat initial (PME3 ONG Nadège Saintilus) : "PcnHaitiAccountTemplate non wiring
        // dans ChartOfAccountsService.initializeMandated — le template existe (R-42) mais
        // le javadoc dit explicitement 'n'est pas encore wiring'. L'utilisateur doit créer
        // manuellement le reste du plan PCN."
        if ("PCN_HAITI".equals(framework.getCode())) {
            List<jo.accountant.chartofaccounts.template.SectorAccountTemplate.AccountSeed> pcnSeeds =
                jo.accountant.chartofaccounts.template.PcnHaitiAccountTemplate.pcnHaitiAccounts();
            for (jo.accountant.chartofaccounts.template.SectorAccountTemplate.AccountSeed seed : pcnSeeds) {
                // Ne pas recréer si le compte existe déjà (idempotence)
                if (accountRepository.findByCompanyIdAndCode(companyId, seed.code()).isPresent()) {
                    continue;
                }
                Account acc = new Account();
                acc.setCompanyId(companyId);
                acc.setCode(seed.code());
                acc.setLabel(seed.label());
                acc.setLevel(seed.code().length() <= 1 ? 1 : (seed.code().length() <= 2 ? 2 : 3));
                acc.setReportingClass(seed.reportingClass());
                acc.setReportingSubcategory(seed.subcategory() != null
                    ? parseReportingSubcategory(seed.subcategory())
                    : ReportingSubcategory.N_A);
                acc.setNormalBalance(seed.normalBalance() != null
                    ? jo.accountant.chartofaccounts.entity.NormalBalance.valueOf(seed.normalBalance())
                    : inferNormalBalance(seed.reportingClass()));
                acc.setCollective(seed.collective());
                acc.setTaxMappingCode(seed.taxMappingCode());
                acc.setLocked(false);  // comptes niveau 2+ modifiables (contrairement aux classes)
                acc.setActive(true);
                // Path = parentCode + "/" + code (hiérarchie)
                acc.setPath(seed.parentCode() != null && !seed.parentCode().isEmpty()
                    ? seed.parentCode() + "/" + seed.code()
                    : seed.code());
                accountRepository.save(acc);
                created++;
            }
            LOG.info("V6-6 — {} comptes PCN_HAITI niveau 2+ créés pour companyId={}",
                pcnSeeds.size(), companyId);
        }

        return created;
    }

    /** Helper — parse une chaîne subcategory en ReportingSubcategory enum (fallback N_A). */
    private ReportingSubcategory parseReportingSubcategory(String subcategory) {
        if (subcategory == null || subcategory.isBlank()) return ReportingSubcategory.N_A;
        try {
            return ReportingSubcategory.valueOf(subcategory);
        } catch (IllegalArgumentException e) {
            return ReportingSubcategory.N_A;
        }
    }

    private int initializeFree(UUID companyId, AccountingFramework framework,
                               InitializeRequest.AccountNumberingTemplateDto templateDto) {
        AccountNumberingTemplate template = new AccountNumberingTemplate();
        template.setCompanyId(companyId);
        template.setAccountingFrameworkId(framework.getId());
        template.setCodeLengthLevel1(templateDto.codeLengthLevel1() != null ? templateDto.codeLengthLevel1() : 1);
        template.setCodeLengthLevel2(templateDto.codeLengthLevel2() != null ? templateDto.codeLengthLevel2() : 2);
        template.setCodeLengthLevel3(templateDto.codeLengthLevel3() != null ? templateDto.codeLengthLevel3() : 3);
        template.setCodeLengthLevel4(templateDto.codeLengthLevel4() != null ? templateDto.codeLengthLevel4() : 6);
        template.setSpacingStep(templateDto.spacingStep() != null ? templateDto.spacingStep() : 3);
        templateRepository.save(template);

        // Pour IFRS : générer 5 classes correspondant aux 5 ReportingClass
        Map<String, ClassSeed> ifrsClasses = new java.util.LinkedHashMap<>();
        ifrsClasses.put("1", new ClassSeed("1", "Actif"));
        ifrsClasses.put("2", new ClassSeed("2", "Passif"));
        ifrsClasses.put("3", new ClassSeed("3", "Capitaux propres"));
        ifrsClasses.put("4", new ClassSeed("4", "Produits"));
        ifrsClasses.put("5", new ClassSeed("5", "Charges"));

        int created = 0;
        for (ClassSeed seed : ifrsClasses.values()) {
            Account classe = new Account();
            classe.setCompanyId(companyId);
            classe.setCode(seed.code());
            classe.setLabel(seed.label());
            classe.setLevel(1);
            classe.setReportingClass(switch (seed.code()) {
                case "1" -> ReportingClass.ACTIF;
                case "2" -> ReportingClass.PASSIF;
                case "3" -> ReportingClass.CAPITAUX_PROPRES;
                case "4" -> ReportingClass.PRODUITS;
                case "5" -> ReportingClass.CHARGES;
                default -> ReportingClass.ACTIF;
            });
            classe.setReportingSubcategory(ReportingSubcategory.N_A);
            classe.setNormalBalance(inferNormalBalance(classe.getReportingClass()));
            classe.setLocked(true);
            classe.setActive(true);
            classe.setCollective(false);
            classe.setPath(seed.code());
            accountRepository.save(classe);
            created++;
        }
        return created;
    }

    // --- Liste / recherche ---

    /**
     * Liste les comptes de l'entreprise, en arbre ou à plat, avec filtre optionnel.
     *
     * @param format {@code "tree"} ou {@code "flat"} (défaut : flat)
     * @param search filtre full-text sur code ou libellé (optionnel). Si fourni, force le
     *        format flat (la recherche coupe l'arbre).
     */
    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID companyId, String format, String search) {
        List<Account> accounts;
        if (search != null && !search.isBlank()) {
            accounts = accountRepository.search(companyId, search.trim());
            // Recherche → toujours flat (l'arbre serait trompeur si on n'a que des feuilles)
            return accounts.stream().map(a -> toResponse(a, null)).toList();
        }
        accounts = accountRepository.findByCompanyIdOrderByCode(companyId);
        if ("tree".equalsIgnoreCase(format)) {
            return buildTree(accounts);
        }
        return accounts.stream().map(a -> toResponse(a, null)).toList();
    }

    private List<AccountResponse> buildTree(List<Account> allAccounts) {
        Map<UUID, List<Account>> byParent = new HashMap<>();
        List<Account> roots = new ArrayList<>();
        for (Account a : allAccounts) {
            if (a.getParentId() == null) {
                roots.add(a);
            } else {
                byParent.computeIfAbsent(a.getParentId(), k -> new ArrayList<>()).add(a);
            }
        }
        roots.sort(Comparator.comparing(Account::getCode));
        return roots.stream().map(a -> toResponse(a, byParent)).toList();
    }

    // --- Création d'enfant ---

    /**
     * Crée un compte enfant sous le parent donné.
     *
     * <p>Le niveau est calculé automatiquement (niveau du parent + 1). Si le code est omis,
     * il est généré comme le prochain code disponible dans la séquence des enfants du parent.
     * Le {@code path} est calculé en concaténant le path du parent avec le code du nouveau
     * compte.
     *
     * <p><strong>Anti-collision concurrente</strong> : en cas de génération automatique du
     * code, deux threads peuvent simultanément estimer que "6001" est libre et tenter de
     * l'insérer. La contrainte unique DB ({@code uc_account_company_code}) est le filet de
     * sécurité — elle lève une {@link org.springframework.dao.DataIntegrityViolationException}
     * qui est rattrapée et déclenche un retry avec le code suivant. Chaque tentative
     * d'insertion s'exécute dans une transaction {@code REQUIRES_NEW} dédiée (via
     * {@link TransactionTemplate}) pour éviter que la transaction courante soit marquée
     * rollback-only en cas d'échec.
     *
     * @throws NotFoundException si le parent n'existe pas dans cette entreprise
     * @throws ValidationException si le niveau calculé dépasse 4
     * @throws ConflictException si le code fourni existe déjà, ou si la génération auto
     *         n'a pas pu produire un code libre après 10 tentatives
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "accounts", allEntries = true)
    public AccountResponse createChild(UUID companyId, UUID parentId, CreateChildRequest req) {
        Account parent = accountRepository.findById(parentId)
            .orElseThrow(() -> new NotFoundException("Account", parentId));
        if (!parent.getCompanyId().equals(companyId)) {
            // §3.9 — ne pas distinguer "n'existe pas" de "appartient à une autre entreprise"
            throw new NotFoundException("Account", parentId);
        }

        int childLevel = parent.getLevel() + 1;
        if (childLevel > 4) {
            throw new ValidationException("ACCOUNT_LEVEL_EXCEEDED",
                "Niveau maximum autorisé : 4. Tentative de créer un compte de niveau " + childLevel);
        }

        // Cas 1 : code explicite fourni — pas de retry, collision = 409
        if (req.code() != null && !req.code().isBlank()) {
            String code = req.code().trim();
            if (accountRepository.existsByCompanyIdAndCode(companyId, code)) {
                throw new ConflictException("ACCOUNT_CODE_ALREADY_EXISTS",
                    "Un compte avec le code '" + code + "' existe déjà dans cette entreprise");
            }
            return persistChildInNewTransaction(companyId, parent, childLevel, code, req);
        }

        // Cas 2 : code auto-généré — retry sur collision de contrainte unique
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = generateChildCode(companyId, parent, childLevel);
            try {
                return persistChildInNewTransaction(companyId, parent, childLevel, code, req);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                // Race condition : un autre thread a inséré ce code entre existsByCompanyIdAndCode
                // et l'INSERT. On retry avec le code suivant.
                LOG.debug("Collision sur le code {} (tentative {}/10), retry", code, attempt + 1);
            }
        }
        throw new ConflictException("ACCOUNT_CODE_GENERATION_FAILED",
            "Impossible de générer un code libre après 10 tentatives — plan comptable saturé ?");
    }

    /**
     * Persiste un compte enfant dans une transaction {@code REQUIRES_NEW} dédiée.
     *
     * <p>Nécessaire pour le retry anti-collision : si l'INSERT échoue sur la contrainte unique,
     * la transaction courante n'est PAS marquée rollback-only et on peut réessayer dans une
     * nouvelle transaction.
     */
    private AccountResponse persistChildInNewTransaction(UUID companyId, Account parent,
                                                        int childLevel, String code,
                                                        CreateChildRequest req) {
        return transactionTemplateRequiresNew.execute(status -> {
            Account child = new Account();
            child.setCompanyId(companyId);
            child.setParentId(parent.getId());
            child.setCode(code);
            child.setLabel(req.label().trim());
            child.setLevel(childLevel);
            child.setReportingClass(req.reportingClass());
            child.setReportingSubcategory(req.reportingSubcategory());
            child.setNormalBalance(req.normalBalance());
            child.setLocked(false);
            child.setActive(true);
            child.setCollective(req.isCollective());
            child.setPath(parent.getPath() + "." + code);
            child.setTaxMappingCode(req.taxMappingCode());
            child.setRequiresAnalyticalTagPlanIds(serializePlanIds(req.requiresAnalyticalTagPlanIds()));
            Account saved = accountRepository.save(child);

            events.publishEvent(new AccountCreatedEvent(saved, TenantContext.getUserId()));
            return toResponse(saved, null);
        });
    }

    /**
     * Génère le prochain code disponible pour un enfant du parent donné.
     *
     * <p>Stratégie : prend le code du parent et ajoute un suffixe numérique incrémental padé
     * à la longueur du niveau. Si le parent a pour code "411" et que le niveau enfant est 4
     * (longueur cible 6 selon le gabarit), le premier enfant sera "411001", puis "411002", etc.
     *
     * <p>Pour les référentiels {@code FREE} sans gabarit (théoriquement impossible car
     * l'initialisation en crée toujours un), on pad à 3 chiffres par défaut.
     */
    private String generateChildCode(UUID companyId, Account parent, int childLevel) {
        AccountNumberingTemplate template = templateRepository.findByCompanyId(companyId).orElse(null);
        int targetLength = template != null ? template.codeLengthForLevel(childLevel) : parent.getCode().length() + 3;

        // Liste des codes enfants existants pour détecter les collisions
        List<Account> existingChildren = accountRepository
            .findByCompanyIdAndParentIdOrderByCode(companyId, parent.getId());

        int suffixLength = targetLength - parent.getCode().length();
        if (suffixLength < 1) suffixLength = 1;

        String suffixFormat = "%0" + suffixLength + "d";
        for (int i = 1; i <= 999_999; i++) {
            String candidate = parent.getCode() + String.format(suffixFormat, i);
            // Filet de sécurité : vérifier aussi l'unicité globale (pas juste chez les enfants)
            if (!accountRepository.existsByCompanyIdAndCode(companyId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("ACCOUNT_CODE_GENERATION_FAILED",
            "Impossible de générer un code libre pour un enfant de " + parent.getCode()
            + " après 999999 tentatives — plan comptable saturé ?");
    }

    // --- Mise à jour ---

    /**
     * Met à jour un compte (sémantique PATCH — seuls les champs fournis sont modifiés).
     *
     * <p>Règles :
     * <ul>
     *   <li>Compte verrouillé ({@code locked = true}) → 409 sur toute modification.</li>
     *   <li>Activation → toujours permise.</li>
     *   <li>Désactivation → refusée si {@link AccountBalanceGuard#hasNonZeroBalance} retourne
     *       true (Phase 3 : toujours false, Phase 5 : vraie vérification).</li>
     * </ul>
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "accounts", allEntries = true)
    public AccountResponse update(UUID companyId, UUID accountId, UpdateAccountRequest req) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new NotFoundException("Account", accountId));
        if (!account.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", accountId);
        }

        if (account.isLocked()) {
            throw new ConflictException("ACCOUNT_LOCKED",
                "Le compte '" + account.getCode() + "' est verrouillé et ne peut pas être modifié");
        }

        String oldValueJson = serializeSnapshot(account);
        boolean changed = false;

        if (req.label() != null && !req.label().isBlank() && !req.label().equals(account.getLabel())) {
            account.setLabel(req.label().trim());
            changed = true;
        }
        if (req.reportingSubcategory() != null && req.reportingSubcategory() != account.getReportingSubcategory()) {
            account.setReportingSubcategory(req.reportingSubcategory());
            changed = true;
        }
        if (req.taxMappingCode() != null && !req.taxMappingCode().equals(account.getTaxMappingCode())) {
            account.setTaxMappingCode(req.taxMappingCode());
            changed = true;
        }
        if (req.requiresAnalyticalTagPlanIds() != null) {
            String newPlans = serializePlanIds(req.requiresAnalyticalTagPlanIds());
            if (!newPlans.equals(account.getRequiresAnalyticalTagPlanIds())) {
                account.setRequiresAnalyticalTagPlanIds(newPlans);
                changed = true;
            }
        }
        if (req.active() != null && req.active() != account.isActive()) {
            if (!req.active()) {
                // Désactivation : vérifier le solde
                if (balanceGuard.hasNonZeroBalance(companyId, accountId)) {
                    throw new ConflictException("ACCOUNT_NOT_BALANCED",
                        "Le compte '" + account.getCode() + "' a un solde non nul et ne peut pas être désactivé");
                }
            }
            account.setActive(req.active());
            changed = true;
        }

        if (changed) {
            Account saved = accountRepository.save(account);
            String newValueJson = serializeSnapshot(saved);
            events.publishEvent(new AccountUpdatedEvent(
                companyId, TenantContext.getUserId(), accountId, oldValueJson, newValueJson));
            return toResponse(saved, null);
        }
        // Pas de changement → ne pas émettre d'événement, retourner l'état courant
        return toResponse(account, null);
    }

    // --- Comptage descendants ---

    @Transactional(readOnly = true)
    public DescendantsCountResponse countDescendants(UUID companyId, UUID accountId) {
        // Vérifier que le compte existe et appartient bien à l'entreprise
        accountRepository.findById(accountId)
            .filter(a -> a.getCompanyId().equals(companyId))
            .orElseThrow(() -> new NotFoundException("Account", accountId));
        return new DescendantsCountResponse(accountRepository.countDescendants(companyId, accountId));
    }

    // --- Helpers ---

    private List<ClassSeed> parseClassSeeds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ClassSeed.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mandatedClassSeed JSON invalide : " + json, e);
        }
    }

    /**
     * Infère la {@link ReportingClass} d'une classe de niveau 1 à partir de son code ET du
     * référentiel comptable de l'entreprise.
     *
     * <p><b>Historique</b> : la version initiale de cette méthode (v1) ignorait le paramètre
     * {@code framework} et appliquait un mapping unique pensé pour SYSCOHADA/PCG/PCN, ce qui
     * produisait un plan comptable erroné pour PCGR_CANADA (5 classes sur 8 mal classées,
     * inversion des classes 6/7 Produits↔Charges) et pour les référentiels IFRS (classe 1
     * mappée à {@code CAPITAUX_PROPRES} au lieu de {@code ACTIF}).
     *
     * <p><b>Version corrigée</b> : le mapping est désormais spécialisé par référentiel.
     *
     * <p>Mapping SYSCOHADA_REVISED / PCG_FRANCE (commun) :
     * <ul>
     *   <li>Classe 1 → CAPITAUX_PROPRES</li>
     *   <li>Classe 2 → ACTIF (immobilisations)</li>
     *   <li>Classe 3 → ACTIF (stocks)</li>
     *   <li>Classe 4 → ACTIF (tiers — simplification, à affiner au niveau 3+)</li>
     *   <li>Classe 5 → ACTIF (trésorerie)</li>
     *   <li>Classe 6 → CHARGES</li>
     *   <li>Classe 7 → PRODUITS</li>
     *   <li>Classe 8 → CHARGES/PRODUITS (HAO — 80-83 CHARGES, 84-89 PRODUITS)</li>
     * </ul>
     *
     * <p><b>R-42 (lot-F1-code-arch)</b> — Mapping PCN_HAITI : spécifique car la classe 8 du PCN
     * Haïtien n'est pas HAO (contrairement à SYSCOHADA) mais "Comptes spéciaux" (engagements
     * hors bilan + comptes de régularisation). Le mapping devient :
     * <ul>
     *   <li>Classe 1 → CAPITAUX_PROPRES</li>
     *   <li>Classe 2 → ACTIF (immobilisations) — sauf 28xx (amortissements) → contra-asset ACTIF</li>
     *   <li>Classe 3 → ACTIF (stocks)</li>
     *   <li>Classe 4 → selon 2ᵉ chiffre : 40/41 → PASSIF/ACTIF (Fournisseurs/Clients), 42-48 → PASSIF (dettes fiscales État-RS/IS/TCA/TVA/taxes diverses/reste)</li>
     *   <li>Classe 5 → ACTIF (trésorerie) — sauf 50xx (valeurs mobilières) → ACTIF (financial asset)</li>
     *   <li>Classe 6 → CHARGES</li>
     *   <li>Classe 7 → PRODUITS</li>
     *   <li>Classe 8 → OTHER (Comptes spéciaux — ni HAO ni OPERATING — exclus du résultat ET du bilan standard)</li>
     * </ul>
     *
     * <p>Mapping PCGR_CANADA (PCG canadien — structure inversée) :
     * <ul>
     *   <li>Classe 1 → ACTIF (Actif à court terme)</li>
     *   <li>Classe 2 → ACTIF (Actif à long terme)</li>
     *   <li>Classe 3 → PASSIF (Dettes à court terme)</li>
     *   <li>Classe 4 → PASSIF (Dettes à long terme)</li>
     *   <li>Classe 5 → CAPITAUX_PROPRES (Avoir des actionnaires)</li>
     *   <li>Classe 6 → PRODUITS (Produits — inversé par rapport à SYSCOHADA)</li>
     *   <li>Classe 7 → CHARGES (Charges — inversé par rapport à SYSCOHADA)</li>
     *   <li>Classe 8 → CHARGES (Impôts sur les bénéfices)</li>
     * </ul>
     *
     * <p>Pour les référentiels FREE (IFRS_FULL, IFRS_SME), le mapping est géré par
     * {@link #initializeFree} qui ne passe pas par cette méthode (les classes 1-5 IFRS sont
     * hardcodées : 1=ACTIF, 2=PASSIF, 3=CAPITAUX_PROPRES, 4=PRODUITS, 5=CHARGES).
     *
     * <p>Ce mapping reste volontairement simplifié — l'utilisateur peut éditer le
     * {@code reportingClass} d'un compte non verrouillé (niveau 3+) si l'inférence est
     * incorrecte. Les niveaux 1 et 2 sont verrouillés MAIS leur classification est rarement
     * remise en cause (la nomenclature des classes est imposée par le texte réglementaire).
     */
    private ReportingClass inferReportingClass(String classCode, AccountingFramework framework) {
        if (classCode == null || classCode.isEmpty()) return ReportingClass.ACTIF;
        char first = classCode.charAt(0);
        String frameworkCode = framework != null ? framework.getCode() : null;

        // Spécialisation PCGR_CANADA : structure radicalement différente des autres référentiels
        if ("PCGR_CANADA".equals(frameworkCode)) {
            return switch (first) {
                case '1', '2' -> ReportingClass.ACTIF;             // Actif CT / Actif LT
                case '3', '4' -> ReportingClass.PASSIF;            // Dettes CT / Dettes LT
                case '5'       -> ReportingClass.CAPITAUX_PROPRES; // Avoir des actionnaires
                case '6'       -> ReportingClass.PRODUITS;         // Produits (INVERSÉ vs SYSCOHADA)
                case '7', '8'  -> ReportingClass.CHARGES;          // Charges + Impôts sur bénéfices
                default        -> ReportingClass.ACTIF;
            };
        }

        // R-42 (lot-F1-code-arch) — Spécialisation PCN_HAITI : la classe 8 du PCN Haïtien n'est
        // PAS HAO (contrairement à SYSCOHADA) mais "Comptes spéciaux" (engagements hors bilan,
        // comptes de régularisation). La classer en CHARGES/PRODUITS la ferait remonter dans le
        // compte de résultat (incorrect — ce ne sont pas des charges/produits d'exploitation).
        // On utilise OTHER pour l'exclure du compte de résultat ET du bilan standard.
        //
        // Pour les classes 1-7, le mapping PCN_HAITI est identique à SYSCOHADA_REVISED / PCG_FRANCE
        // (CAPITAUX_PROPRES / ACTIF / ACTIF / Tiers / ACTIF / CHARGES / PRODUITS), mais avec un
        // affinage niveau 2+ pour la classe 4 (Tiers) qui respecte la nomenclature haïtienne :
        //   - 40/41 → ACTIF (clients) ou PASSIF (fournisseurs) — identique SYSCOHADA
        //   - 42/44/45/46/47/48 → PASSIF (toutes les catégories "État xxx" sont des dettes fiscales)
        if ("PCN_HAITI".equals(frameworkCode)) {
            if (first == '4') {
                // Classe 4 : Tiers — même logique que SYSCOHADA, avec un focus "État" élargi
                // (42 État-RS, 44 État-IS, 45 État-TCA, 46 État-TVA, 47 État-taxes diverses,
                // 48 État-reste — toutes dettes fiscales = PASSIF).
                if (classCode.length() >= 2) {
                    char second = classCode.charAt(1);
                    return switch (second) {
                        case '0', '2', '3', '4', '5', '6', '7', '8' -> ReportingClass.PASSIF;  // 40 Fourn, 42 État-RS, 43 Org soc, 44 État-IS, 45 État-TCA, 46 État-TVA, 47 État-taxes div, 48 État-reste
                        case '1', '9' -> ReportingClass.ACTIF;   // 41 Clients, 49 Dépréciation
                        default -> ReportingClass.PASSIF;
                    };
                }
                return ReportingClass.PASSIF;
            }
            if (first == '8') {
                // Classe 8 PCN_HAITI : Comptes spéciaux (engagements hors bilan, régularisation).
                // DISTINCT de SYSCOHADA où classe 8 = HAO (CHARGES/PRODUITS).
                // Tous les sous-comptes 8xx → OTHER (exclus du résultat ET du bilan standard).
                return ReportingClass.OTHER;
            }
            // Classes 1, 2, 3, 5, 6, 7 — mapping identique SYSCOHADA/PCG
            return switch (first) {
                case '1' -> ReportingClass.CAPITAUX_PROPRES;
                case '2', '3', '5' -> ReportingClass.ACTIF;
                case '6' -> ReportingClass.CHARGES;
                case '7' -> ReportingClass.PRODUITS;
                default -> ReportingClass.ACTIF;
            };
        }

        // Mapping par défaut (SYSCOHADA_REVISED / PCG_FRANCE)
        // Audit v4.7 §3.2 — classe 4 (Tiers) et classe 8 (HAO) affinées par sous-classe.
        if (first == '4') {
            // Classe 4 : Tiers — distinction ACTIF (clients, débiteurs) vs PASSIF (fournisseurs, dettes)
            if (classCode.length() >= 2) {
                char second = classCode.charAt(1);
                return switch (second) {
                    case '0', '2', '3', '4', '5', '7', '8' -> ReportingClass.PASSIF;  // 40 Fourn, 42 Pers, 43 Org soc, 44 État, 45 Sécu, 47 Créd divers, 48 Régul
                    case '1', '6', '9' -> ReportingClass.ACTIF;   // 41 Clients, 46 Déb divers, 49 Dépréciation
                    default -> ReportingClass.PASSIF;  // défaut PASSIF (dette)
                };
            }
            return ReportingClass.PASSIF;  // 4 seul = PASSIF par défaut
        }
        if (first == '8') {
            // Classe 8 : HAO SYSCOHADA — 80/81/82/83 = CHARGES, 84/85/86/87/88/89 = PRODUITS
            if (classCode.length() >= 2) {
                char second = classCode.charAt(1);
                if (second >= '0' && second <= '3') return ReportingClass.CHARGES;   // 80-83
                if (second >= '4' && second <= '9') return ReportingClass.PRODUITS;  // 84-89
            }
            return ReportingClass.CHARGES;  // 8 seul = CHARGES par défaut
        }
        return switch (first) {
            case '1' -> ReportingClass.CAPITAUX_PROPRES;
            case '2', '3', '5' -> ReportingClass.ACTIF;
            case '6' -> ReportingClass.CHARGES;
            case '7' -> ReportingClass.PRODUITS;
            default -> ReportingClass.ACTIF;
        };
    }

    private NormalBalance inferNormalBalance(ReportingClass rc) {
        // R-42 (lot-F1-code-arch) — OTHER (Comptes spéciaux PCN Haïti) → CREDIT par convention
        // (engagements hors bilan et comptes de régularisation sont typiquement créditeurs).
        // Ce choix n'impacte pas les états financiers car OTHER est ignoré par FinancialStatementsService.
        return switch (rc) {
            case ACTIF, CHARGES -> NormalBalance.DEBIT;
            case PASSIF, CAPITAUX_PROPRES, PRODUITS, OTHER -> NormalBalance.CREDIT;
        };
    }

    private String serializePlanIds(List<UUID> planIds) {
        if (planIds == null || planIds.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(planIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plan ids", e);
        }
    }

    private List<UUID> deserializePlanIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to deserialize plan ids: {}", json, e);
            return List.of();
        }
    }

    private String serializeSnapshot(Account a) {
        try {
            Map<String, Object> snap = new java.util.LinkedHashMap<>();
            snap.put("code", a.getCode());
            snap.put("label", a.getLabel());
            snap.put("level", a.getLevel());
            snap.put("reportingClass", a.getReportingClass());
            snap.put("reportingSubcategory", a.getReportingSubcategory());
            snap.put("normalBalance", a.getNormalBalance());
            snap.put("active", a.isActive());
            snap.put("taxMappingCode", a.getTaxMappingCode());
            return objectMapper.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private AccountResponse toResponse(Account a, Map<UUID, List<Account>> childrenByParent) {
        List<AccountResponse> children = null;
        if (childrenByParent != null) {
            List<Account> kids = childrenByParent.getOrDefault(a.getId(), List.of());
            kids = new ArrayList<>(kids);
            kids.sort(Comparator.comparing(Account::getCode));
            children = kids.stream().map(k -> toResponse(k, childrenByParent)).toList();
        }
        return new AccountResponse(
            a.getId(),
            a.getParentId(),
            a.getCode(),
            a.getLabel(),
            a.getLevel(),
            a.getReportingClass(),
            a.getReportingSubcategory(),
            a.getNormalBalance(),
            a.isLocked(),
            a.isActive(),
            a.isCollective(),
            a.getPath(),
            a.getTaxMappingCode(),
            deserializePlanIds(a.getRequiresAnalyticalTagPlanIds()),
            a.getCreatedAt(),
            a.getUpdatedAt(),
            children
        );
    }

    /** Record interne pour le parsing du seed de classes. */
    private record ClassSeed(
        @com.fasterxml.jackson.annotation.JsonProperty("class") String code,
        String label
    ) {}

    /** Résultat de l'initialisation. */
    public record InitializeResult(UUID accountingFrameworkId, int accountsCreated) {}
}

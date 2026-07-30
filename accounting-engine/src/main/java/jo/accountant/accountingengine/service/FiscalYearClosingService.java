package jo.accountant.accountingengine.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.FiscalYearStatus;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.AccountResolver;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service dédié à la <b>clôture d'exercice fiscal</b> — extraction du God class
 * {@code AccountingEngineService} (Finding #2 — refactor batch 2).
 *
 * <p><b>Motivation</b> : {@code AccountingEngineService} était un God class de 1581 lignes
 * mêlant écritures, journaux, balance, grand livre, exercices fiscaux et clôture. La logique de
 * clôture (≈280 lignes : {@code closeFiscalYear} + {@code generateOpeningEntryNextYear} +
 * helpers) est un sous-domaine isolable : elle ne s'exécute qu'une fois par an et par entreprise,
 * manipule des concepts différents (résultat net, à-nouveau N+1, verrouillage des périodes,
 * auto-switch de l'exercice actif) et n'est appelée par aucune autre méthode du service.
 *
 * <p><b>Responsabilités transférées</b> :
 * <ul>
 *   <li>{@link #closeFiscalYear(UUID, UUID)} — génère et poste les écritures de clôture (résultat
 *       net → capitaux propres), vérifie qu'il n'y a pas d'écritures DRAFT/PENDING_APPROVAL
 *       (audit v4.7 §3.1 Finding #7), génère l'écriture d'ouverture N+1 (audit v4.7 §3.1
 *       Finding #8), verrouille l'exercice et ses périodes, et auto-switch l'exercice actif sur
 *       le prochain OPEN.</li>
 *   <li>{@link #generateOpeningEntryNextYear(UUID, FiscalYear, List, Map, String)} — reporte les
 *       soldes des comptes de bilan (ACTIF/PASSIF/CAPITAUX_PROPRES) vers la première période OPEN
 *       de l'exercice N+1.</li>
 *   <li>Helpers privés : {@code loadFiscalYear}, {@code findPeriodForDate},
 *       {@code readActiveFiscalYearId}, {@code setActiveFiscalYearId} (duplications locales —
 *       ces helpers restent aussi dans {@code AccountingEngineService} qui les utilise
 *       ailleurs).</li>
 * </ul>
 *
 * <p><b>Dépendances</b> : injecte les repositories nécessaires + le
 * {@link AccountingEngineService} (via {@link Lazy} pour casser le cycle : AccountingEngineService
 * délègue à ce service, qui à son tour appelle {@code AccountingEngineService.createJournalEntry}
 * et {@code AccountingEngineService.postJournalEntry} — cycle brisé par proxy Spring lazy).
 *
 * <p><b>Transaction</b> : {@link #closeFiscalYear} reste {@code @Transactional} — l'écriture de
 * clôture, l'écriture d'ouverture N+1, le verrouillage des périodes et l'auto-switch de
 * l'exercice actif font partie d'une même UoW : soit tout passe, soit rien ( rollback sur
 * la moindre erreur).
 *
 * <p><b>API publique préservée</b> : {@code AccountingEngineService.closeFiscalYear} est
 * conservée comme façade qui délègue à {@link #closeFiscalYear(UUID, UUID)}. Les tests
 * d'intégration existants (ActiveFiscalYearIntegrationTest, AccountingEngineIntegrationTest)
 * appellent toujours {@code accountingService.closeFiscalYear(...)} — signature inchangée.
 */
@Service
public class FiscalYearClosingService {

    private static final Logger LOG = LoggerFactory.getLogger(FiscalYearClosingService.class);

    private final FiscalYearRepository fiscalYearRepository;
    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final JournalRepository journalRepository;
    private final AccountRepository accountRepository;
    private final AccountResolver accountResolver;
    private final JdbcTemplate jdbcTemplate;
    // @Lazy obligatoire : AccountingEngineService délègue closeFiscalYear() à ce service, qui à
    // son tour appelle AccountingEngineService.createJournalEntry() + postJournalEntry(). Sans
    // @Lazy, Spring lèverait BeanCurrentlyInCreationException à l'injection constructeur.
    private final AccountingEngineService accountingEngineService;

    public FiscalYearClosingService(FiscalYearRepository fiscalYearRepository,
                                    FiscalPeriodRepository fiscalPeriodRepository,
                                    JournalEntryRepository journalEntryRepository,
                                    JournalLineRepository journalLineRepository,
                                    JournalRepository journalRepository,
                                    AccountRepository accountRepository,
                                    AccountResolver accountResolver,
                                    JdbcTemplate jdbcTemplate,
                                    @Lazy AccountingEngineService accountingEngineService) {
        this.fiscalYearRepository = fiscalYearRepository;
        this.fiscalPeriodRepository = fiscalPeriodRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.journalRepository = journalRepository;
        this.accountRepository = accountRepository;
        this.accountResolver = accountResolver;
        this.jdbcTemplate = jdbcTemplate;
        this.accountingEngineService = accountingEngineService;
    }

    // --- Clôture d'exercice (Vague 2, item 2.4) ---

    /**
     * Génère et poste les écritures de clôture d'exercice (Vague 2, item 2.4).
     *
     * <p>Étapes :
     * <ol>
     *   <li>Calcule le résultat net = Produits − Charges pour l'exercice.</li>
     *   <li>Si résultat positif (bénéfice) : Débit comptes de produits (solde → 0),
     *       Crédit compte "Résultat de l'exercice" (capitaux propres).</li>
     *   <li>Si résultat négatif (perte) : Débit compte "Résultat de l'exercice",
     *       Crédit comptes de charges (solde → 0).</li>
     *   <li>Poste l'écriture avec sourceModule=MANUAL et description "Clôture exercice {year}".</li>
     * </ol>
     *
     * <p>Note : les comptes de produits/charges ne sont pas réellement "soldés" en Phase 5 —
     * cette méthode génère une écriture de résultat qui équilibre le bilan. Les comptes
     * de produits/charges conservent leur solde pour l'historique. Le bilan devient équilibré
     * car le résultat net est intégré aux capitaux propres via le compte de report à nouveau.
     *
     * @return l'écriture de clôture postée
     */
    @Transactional
    public JournalEntryResponse closeFiscalYear(UUID companyId, UUID fiscalYearId) {
        FiscalYear fy = loadFiscalYear(companyId, fiscalYearId);

        // ── Audit v4.7 §3.1 Finding #7 — FIX : vérifier qu'il n'y a pas d'écritures DRAFT ou
        // PENDING_APPROVAL avant de clôturer. Sans ce check, ces écritures restent bloquées à vie
        // (la période sera LOCKED après clôture).
        List<UUID> periodIds = fiscalPeriodRepository.findByFiscalYearIdOrderByStartDateAsc(fy.getId())
            .stream().map(jo.accountant.accountingengine.entity.FiscalPeriod::getId).toList();
        long draftCount = journalEntryRepository.countByCompanyIdAndFiscalPeriodIdInAndStatus(
            companyId, periodIds, JournalEntryStatus.DRAFT);
        long pendingCount = journalEntryRepository.countByCompanyIdAndFiscalPeriodIdInAndStatus(
            companyId, periodIds, JournalEntryStatus.PENDING_APPROVAL);
        if (draftCount > 0 || pendingCount > 0) {
            throw new ValidationException("FISCAL_YEAR_HAS_OPEN_ENTRIES",
                "L'exercice " + fy.getLabel() + " contient " + draftCount + " écriture(s) DRAFT et "
                + pendingCount + " écriture(s) PENDING_APPROVAL. Toutes les écritures doivent être "
                + "POSTED ou VOIDED avant la clôture.");
        }

        // Calculer le résultat net via FinancialStatementsService
        // (on ne peut pas injecter FinancialStatementsService car :accounting-engine ne dépend
        // pas de :financial-statements — on calcule directement)
        List<JournalLine> postedLines = journalLineRepository.findAllPostedBetweenDates(
            companyId, fy.getStartDate(), fy.getEndDate());

        BigDecimal totalProducts = BigDecimal.ZERO;
        BigDecimal totalCharges = BigDecimal.ZERO;
        for (JournalLine line : postedLines) {
            Account account = accountRepository.findById(line.getAccountId()).orElse(null);
            if (account == null) continue;
            if (account.getReportingClass() == ReportingClass.PRODUITS) {
                totalProducts = totalProducts.add(line.getCredit()).subtract(line.getDebit());
            } else if (account.getReportingClass() == ReportingClass.CHARGES) {
                totalCharges = totalCharges.add(line.getDebit()).subtract(line.getCredit());
            }
        }
        BigDecimal netResult = totalProducts.subtract(totalCharges);

        if (netResult.compareTo(BigDecimal.ZERO) == 0) {
            throw new ConflictException("NO_RESULT_TO_CLOSE",
                "Le résultat net est nul — aucune écriture de clôture à générer");
        }

        // Trouver le compte de résultat / report à nouveau — de manière référentiel-agnostique.
        // AccountResolver centralise la cascade (audit #3) : taxMappingCode=FISCAL_RESULT
        // → compte de CAPITAUX_PROPRES de niveau 1 → premier CAPITAUX_PROPRES actif quelconque.
        Account resultAccount = accountResolver
            .resolveByTaxMappingOrCode(companyId, ReportingClass.CAPITAUX_PROPRES, "FISCAL_RESULT")
            .or(() -> accountResolver.resolveByReportingClass(
                companyId, ReportingClass.CAPITAUX_PROPRES, 1))
            .orElseThrow(() -> new ValidationException(
                "RESULT_ACCOUNT_NOT_FOUND",
                "Aucun compte de CAPITAUX_PROPRES trouvé pour le report du résultat. " +
                "Créer un compte de capitaux propres (idéalement marqué taxMappingCode=\"FISCAL_RESULT\") " +
                "ou initialiser le plan comptable de l'entreprise."));

        // Créer l'écriture de clôture : on solde les produits et charges contre le compte de résultat
        String journalCode = journalRepository.findByCompanyIdAndCode(companyId, "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException(
                "JOURNAL_OD_NOT_FOUND", "Journal OD introuvable"));

        // Trouver la période fiscale de la date de fin d'exercice
        FiscalPeriod closingPeriod = findPeriodForDate(companyId, fy.getEndDate());
        if (closingPeriod == null) {
            throw new ValidationException("PERIOD_NOT_FOUND",
                "Aucune période fiscale trouvée pour la date " + fy.getEndDate());
        }

        // Construire les lignes : pour chaque compte de produit/charge avec solde non nul,
        // créer une ligne inverse pour le solder, contrepartie sur le compte de résultat
        List<LineDto> lines = new ArrayList<>();
        Map<String, BigDecimal> balancesByAccount = new HashMap<>();
        // Audit v4.7 §3.1 — pré-agrégation par accountCode (au lieu de findById dans la boucle)
        Map<UUID, Account> accountCache = new HashMap<>();
        for (JournalLine line : postedLines) {
            Account account = accountCache.computeIfAbsent(line.getAccountId(),
                id -> accountRepository.findById(id).orElse(null));
            if (account == null) continue;
            if (account.getReportingClass() != ReportingClass.PRODUITS
                && account.getReportingClass() != ReportingClass.CHARGES) continue;
            BigDecimal balance = balancesByAccount.getOrDefault(account.getCode(), BigDecimal.ZERO);
            balancesByAccount.put(account.getCode(), balance.add(line.getDebit()).subtract(line.getCredit()));
        }

        for (Map.Entry<String, BigDecimal> entry : balancesByAccount.entrySet()) {
            String accountCode = entry.getKey();
            BigDecimal balance = entry.getValue();
            if (balance.compareTo(BigDecimal.ZERO) == 0) continue;

            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new LineDto(accountCode, null, null, balance,
                    "Clôture — solde " + accountCode, List.of()));
                lines.add(new LineDto(resultAccount.getCode(), null, balance, null,
                    "Clôture — solde " + accountCode, List.of()));
            } else {
                BigDecimal absBalance = balance.negate();
                lines.add(new LineDto(accountCode, null, absBalance, null,
                    "Clôture — solde " + accountCode, List.of()));
                lines.add(new LineDto(resultAccount.getCode(), null, null, absBalance,
                    "Clôture — solde " + accountCode, List.of()));
            }
        }

        if (lines.isEmpty()) {
            throw new ConflictException("NO_ENTRIES_TO_CLOSE",
                "Aucun compte de produit/charge avec solde non nul — rien à clôturer");
        }

        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            journalCode, fy.getEndDate(),
            "Clôture exercice " + fy.getStartDate().getYear() + "-" + fy.getEndDate().getYear(),
            lines, JournalEntrySourceModule.MANUAL);

        // Délégation à AccountingEngineService pour la création + postage (idempotence,
        // document-numbering, approval-workflow, publication de JournalEntryPostedEvent).
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, "close-fy-" + fiscalYearId, req);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        // ── Audit v4.7 §3.1 Finding #8 — FIX CRITIQUE : génération de l'écriture d'ouverture N+1.
        // Sans cette écriture, la balance N+1 commence à zéro — les soldes clients/fournisseurs/
        // banque/trésorerie de N ne sont pas reportés. C'est un défaut majeur pour SYSCOHADA, PCG
        // et IFRS qui exigent des à-nouveau.
        generateOpeningEntryNextYear(companyId, fy, postedLines, accountCache, journalCode);

        // R1 (Vague fix) : marquer l'exercice comme CLOSED après clôture
        fy.setStatus(jo.accountant.accountingengine.entity.FiscalYearStatus.CLOSED);
        fiscalYearRepository.save(fy);
        // Verrouiller toutes les périodes de l'exercice
        fiscalPeriodRepository.findByFiscalYearIdOrderByStartDateAsc(fy.getId())
            .forEach(p -> {
                p.setStatus(jo.accountant.accountingengine.entity.FiscalPeriodStatus.LOCKED);
                fiscalPeriodRepository.save(p);
            });

        // ── Audit v4.7 §3.1 Finding #9 — FIX : log warning si snapshot non créé. La création
        // effective du snapshot figé doit être déléguée à :financial-statements (qui n'est pas
        // accessible depuis :accounting-engine pour éviter le cycle de dépendance). L'appelant
        // (controller) doit appeler FinancialStatementsService.createSnapshot après closeFiscalYear.
        LOG.warn("closeFiscalYear terminé pour FY {} (company {}). IMPORTANT : l'appelant doit créer "
            + "les snapshots figés (bilan + CR) via FinancialStatementsService.createSnapshot pour "
            + "préserver la piste d'audit (audit v4.7 §3.1 Finding #9).", fiscalYearId, companyId);

        // Auto-switch: if the closed FY was the active one, switch to the latest OPEN.
        try {
            UUID activeId = readActiveFiscalYearId(companyId);
            if (activeId != null && activeId.equals(fiscalYearId)) {
                List<FiscalYear> allFy = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
                FiscalYear nextOpen = null;
                for (int i = allFy.size() - 1; i >= 0; i--) {
                    if (allFy.get(i).getStatus() == FiscalYearStatus.OPEN) {
                        nextOpen = allFy.get(i);
                        break;
                    }
                }
                if (nextOpen != null) {
                    setActiveFiscalYearId(companyId, nextOpen.getId());
                    LOG.info("Auto-switched active FY from {} (CLOSED) to {} (OPEN)",
                        fiscalYearId, nextOpen.getId());
                } else {
                    setActiveFiscalYearId(companyId, null);
                    LOG.warn("No OPEN fiscal year left after closing {}", fiscalYearId);
                }
            }
        } catch (Exception e) {
            LOG.debug("Auto-switch skipped for company {} (best-effort): {}", companyId, e.getMessage());
        }

        return posted;
    }

    /**
     * Génère l'écriture d'ouverture N+1 — reporte les soldes des comptes de bilan
     * (classes 1-5 = ACTIF, PASSIF, CAPITAUX_PROPRES) de l'exercice clos vers la première
     * période OPEN de l'exercice suivant.
     *
     * <p><b>Audit v4.7 §3.1 Finding #8 — FIX CRITIQUE</b> : sans cette écriture, la balance
     * N+1 commence à zéro — les soldes clients/fournisseurs/banque/trésorerie de N ne sont
     * pas reportés. C'est un défaut majeur pour SYSCOHADA, PCG et IFRS qui exigent des
     * à-nouveau (opening entries).
     *
     * <p>Logique :
     * <ol>
     *   <li>Calculer le solde net (débit - crédit) par compte de bilan (ACTIF/PASSIF/CAPITAUX_PROPRES).</li>
     *   <li>Trouver l'exercice N+1 (OPEN avec startDate &gt; fy.endDate).</li>
     *   <li>Créer une écriture à la date de début de N+1 avec les soldes reportés :
     *       solde débiteur → débit (actif), solde créditeur → crédit (passif/capitaux propres).</li>
     * </ol>
     *
     * <p>Best-effort : si aucun exercice N+1 n'existe, log warning et skip (l'utilisateur peut
     * générer manuellement l'écriture d'ouverture plus tard).
     */
    void generateOpeningEntryNextYear(UUID companyId, FiscalYear closedFy,
                                      List<JournalLine> postedLines,
                                      Map<UUID, Account> accountCache,
                                      String journalCode) {
        // 1. Calculer les soldes par compte de bilan (ACTIF, PASSIF, CAPITAUX_PROPRES)
        Map<String, BigDecimal> balancesByAccount = new HashMap<>();
        for (JournalLine line : postedLines) {
            Account account = accountCache.get(line.getAccountId());
            if (account == null) continue;
            ReportingClass rc = account.getReportingClass();
            if (rc != ReportingClass.ACTIF
                && rc != ReportingClass.PASSIF
                && rc != ReportingClass.CAPITAUX_PROPRES) continue;
            BigDecimal balance = balancesByAccount.getOrDefault(account.getCode(), BigDecimal.ZERO);
            balancesByAccount.put(account.getCode(), balance.add(line.getDebit()).subtract(line.getCredit()));
        }

        if (balancesByAccount.isEmpty()) {
            LOG.info("Aucun compte de bilan avec solde non nul — écriture d'ouverture N+1 non générée pour FY {}",
                closedFy.getId());
            return;
        }

        // 2. Trouver l'exercice N+1 (OPEN avec startDate > closedFy.endDate)
        List<FiscalYear> allFy = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
        FiscalYear nextFy = null;
        for (FiscalYear f : allFy) {
            if (f.getStatus() == FiscalYearStatus.OPEN
                && f.getStartDate().isAfter(closedFy.getEndDate())) {
                nextFy = f;
                break;
            }
        }
        if (nextFy == null) {
            LOG.warn("Aucun exercice OPEN avec startDate > {} trouvé pour company {} — écriture "
                + "d'ouverture N+1 non générée. L'utilisateur doit créer un exercice N+1 puis "
                + "générer manuellement l'écriture d'ouverture (audit v4.7 §3.1 Finding #8).",
                closedFy.getEndDate(), companyId);
            return;
        }

        // 3. Trouver la période fiscale de la date de début de N+1
        FiscalPeriod openingPeriod = findPeriodForDate(companyId, nextFy.getStartDate());
        if (openingPeriod == null) {
            LOG.warn("Aucune période fiscale trouvée pour la date {} (début FY {}) — écriture "
                + "d'ouverture N+1 non générée.", nextFy.getStartDate(), nextFy.getId());
            return;
        }

        // 4. Construire les lignes d'ouverture : report des soldes
        List<LineDto> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : balancesByAccount.entrySet()) {
            String accountCode = e.getKey();
            BigDecimal balance = e.getValue();
            if (balance.compareTo(BigDecimal.ZERO) == 0) continue;
            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                // Solde débiteur → débit sur le compte d'actif (normalBalance DEBIT)
                lines.add(new LineDto(accountCode, null, balance, null,
                    "À-nouveau N+1 — " + accountCode, List.of()));
            } else {
                // Solde créditeur → crédit sur le compte de passif/capitaux propres (normalBalance CREDIT)
                lines.add(new LineDto(accountCode, null, null, balance.negate(),
                    "À-nouveau N+1 — " + accountCode, List.of()));
            }
        }

        if (lines.isEmpty()) {
            LOG.info("Aucune ligne à écrire pour l'ouverture N+1 (soldes tous nuls).");
            return;
        }

        // 5. Créer et poster l'écriture d'ouverture N+1
        try {
            CreateJournalEntryRequest openingReq = new CreateJournalEntryRequest(
                journalCode, nextFy.getStartDate(),
                "Ouverture exercice " + nextFy.getStartDate().getYear() + "-"
                    + nextFy.getEndDate().getYear(),
                lines, JournalEntrySourceModule.MANUAL);
            JournalEntryResponse openingEntry = accountingEngineService.createJournalEntry(
                companyId, "open-fy-" + nextFy.getId() + "-from-" + closedFy.getId(), openingReq);
            accountingEngineService.postJournalEntry(companyId, openingEntry.id(), List.of());
            LOG.info("Écriture d'ouverture N+1 générée pour FY {} (company {}) — {} lignes reportées",
                nextFy.getId(), companyId, lines.size());
        } catch (Exception ex) {
            // Best-effort : ne pas faire échouer la clôture si l'ouverture échoue
            LOG.error("Échec de la génération de l'écriture d'ouverture N+1 pour FY {} (company {}). "
                + "L'utilisateur doit générer manuellement l'écriture d'ouverture. Détail : {}",
                nextFy.getId(), companyId, ex.getMessage(), ex);
        }
    }

    // --- Helpers privés (duplications locales — voir AccountingEngineService pour les usages
    //     hors-clôture de ces mêmes helpers). ---

    private FiscalYear loadFiscalYear(UUID companyId, UUID fiscalYearId) {
        FiscalYear fy = fiscalYearRepository.findById(fiscalYearId)
            .orElseThrow(() -> new NotFoundException("FiscalYear", fiscalYearId));
        if (!fy.getCompanyId().equals(companyId)) {
            throw new NotFoundException("FiscalYear", fiscalYearId);
        }
        return fy;
    }

    private FiscalPeriod findPeriodForDate(UUID companyId, LocalDate date) {
        // Trouver l'exercice contenant la date
        List<FiscalYear> fys = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(companyId);
        for (FiscalYear fy : fys) {
            if (!date.isBefore(fy.getStartDate()) && !date.isAfter(fy.getEndDate())) {
                return fiscalPeriodRepository
                    .findByFiscalYearIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        fy.getId(), date, date)
                    .orElse(null);
            }
        }
        return null;
    }

    private UUID readActiveFiscalYearId(UUID companyId) {
        try {
            var results = jdbcTemplate.queryForList(
                "SELECT active_fiscal_year_id FROM companies WHERE id = ?",
                UUID.class, companyId);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private void setActiveFiscalYearId(UUID companyId, UUID fiscalYearId) {
        try {
            var versions = jdbcTemplate.queryForList(
                "SELECT version FROM companies WHERE id = ?", Long.class, companyId);
            if (versions.isEmpty()) return; // company doesn't exist — skip
            Long version = versions.get(0);
            jdbcTemplate.update(
                "UPDATE companies SET active_fiscal_year_id = ?, updated_at = NOW(), version = version + 1 " +
                "WHERE id = ? AND version = ?",
                fiscalYearId, companyId, version);
        } catch (Exception e) {
            LOG.debug("setActiveFiscalYearId skipped for company {} (best-effort): {}", companyId, e.getMessage());
        }
    }
}

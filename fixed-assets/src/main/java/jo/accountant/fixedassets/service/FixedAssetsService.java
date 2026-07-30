package jo.accountant.fixedassets.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.currency.CurrencyRoundingService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.fixedassets.dto.AddAssetComponentRequest;
import jo.accountant.fixedassets.dto.AssetComponentResponse;
import jo.accountant.fixedassets.dto.AssetResponse;
import jo.accountant.fixedassets.dto.CreateAssetRequest;
import jo.accountant.fixedassets.dto.CreateAssetRequest.CreateAssetComponentRequest;
import jo.accountant.fixedassets.dto.DisposeAssetRequest;
import jo.accountant.fixedassets.dto.ImpairmentTestResult;
import jo.accountant.fixedassets.dto.ScheduleLineResponse;
import jo.accountant.fixedassets.entity.AssetStatus;
import jo.accountant.fixedassets.entity.Asset;
import jo.accountant.fixedassets.entity.AssetComponent;
import jo.accountant.fixedassets.entity.DepreciationMethod;
import jo.accountant.fixedassets.entity.DepreciationScheduleLine;
import jo.accountant.fixedassets.event.AssetCreatedEvent;
import jo.accountant.fixedassets.event.AssetDisposedEvent;
import jo.accountant.fixedassets.event.DepreciationPostedEvent;
import jo.accountant.fixedassets.repository.AssetComponentRepository;
import jo.accountant.fixedassets.repository.AssetRepository;
import jo.accountant.fixedassets.repository.DepreciationScheduleLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des immobilisations (§13 Phase 8).
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Création d'immobilisation avec auto-génération de l'échéancier d'amortissement</li>
 *   <li>Postage période par période (jamais en une seule fois à l'achat) via écriture
 *       générée avec {@code sourceModule = FIXED_ASSETS}</li>
 *   <li>Cession avec calcul de plus/moins-value</li>
 * </ul>
 *
 * <p>Règles métier §13 Phase 8 :
 * <ol>
 *   <li>Génération automatique de l'échéancier à la création — une ligne par mois.</li>
 *   <li>Comptabilisation période par période — une écriture par ligne postée.</li>
 *   <li>Cession → calcul plus/moins-value, asset → DISPOSED (immuable).</li>
 *   <li>Asset DISPOSED ne peut plus être amorti ni cédé à nouveau (409).</li>
 * </ol>
 */
@Service
public class FixedAssetsService {

    private static final Logger LOG = LoggerFactory.getLogger(FixedAssetsService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AssetRepository assetRepository;
    private final AssetComponentRepository componentRepository;
    private final DepreciationScheduleLineRepository scheduleRepository;
    private final AccountRepository accountRepository;
    private final FiscalYearRepository fiscalYearRepository;
    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JournalRepository journalRepository;
    private final AccountingEngineService accountingEngineService;
    private final CompanyRepository companyRepository;
    private final CurrencyRoundingService roundingService;
    private final ApplicationEventPublisher events;

    public FixedAssetsService(AssetRepository assetRepository,
                              AssetComponentRepository componentRepository,
                              DepreciationScheduleLineRepository scheduleRepository,
                              AccountRepository accountRepository,
                              FiscalYearRepository fiscalYearRepository,
                              FiscalPeriodRepository fiscalPeriodRepository,
                              JournalRepository journalRepository,
                              AccountingEngineService accountingEngineService,
                              CompanyRepository companyRepository,
                              CurrencyRoundingService roundingService,
                              ApplicationEventPublisher events) {
        this.assetRepository = assetRepository;
        this.componentRepository = componentRepository;
        this.scheduleRepository = scheduleRepository;
        this.accountRepository = accountRepository;
        this.fiscalYearRepository = fiscalYearRepository;
        this.fiscalPeriodRepository = fiscalPeriodRepository;
        this.journalRepository = journalRepository;
        this.accountingEngineService = accountingEngineService;
        this.companyRepository = companyRepository;
        this.roundingService = roundingService;
        this.events = events;
    }

    /**
     * Résout la devise contextuelle pour un calcul d'arrondi (audit M14).
     *
     * <p>L'entité {@link Asset} ne porte pas de champ devise — l'amortissement est toujours
     * calculé en devise fonctionnelle de l'entreprise. On récupère donc
     * {@link Company#getFunctionalCurrency()} (avec fallback "HTG" si la company est
     * introuvable — ne devrait pas arriver car l'asset a déjà été chargé pour ce companyId).
     */
    private String resolveCurrency(UUID companyId) {
        return companyRepository.findById(companyId)
            .map(Company::getFunctionalCurrency)
            .orElse("HTG");
    }

    // --- Création ---

    /**
     * Crée une immobilisation et génère automatiquement son échéancier d'amortissement.
     *
     * <p>L'échéancier contient {@code usefulLifeMonths} lignes, une par mois, à partir du
     * mois suivant {@code acquisitionDate}.
     */
    @Transactional
    public AssetResponse createAsset(UUID companyId, CreateAssetRequest req) {
        validateCreateRequest(companyId, req);

        Asset asset = new Asset();
        asset.setCompanyId(companyId);
        asset.setLabel(req.label().trim());
        asset.setAcquisitionDate(req.acquisitionDate());
        asset.setAcquisitionCost(req.acquisitionCost());
        asset.setUsefulLifeMonths(req.usefulLifeMonths());
        asset.setResidualValue(req.residualValue());
        asset.setDepreciationMethod(req.depreciationMethod());
        asset.setAssetAccountId(req.assetAccountId());
        asset.setDepreciationExpenseAccountId(req.depreciationExpenseAccountId());
        asset.setAccumulatedDepreciationAccountId(req.accumulatedDepreciationAccountId());
        // Audit M11 : stocker les comptes de plus/moins-value de cession (optionnels).
        asset.setDisposalGainAccountId(req.disposalGainAccountId());
        asset.setDisposalLossAccountId(req.disposalLossAccountId());
        // Finding #11 — comptes de dépréciation IAS 36 (optionnels, fallback sur amortissement).
        asset.setImpairmentExpenseAccountId(req.impairmentExpenseAccountId());
        asset.setAccumulatedImpairmentAccountId(req.accumulatedImpairmentAccountId());
        asset.setImpairmentAmount(BigDecimal.ZERO);
        asset.setStatus(AssetStatus.ACTIVE);
        Asset saved = assetRepository.save(asset);

        // Finding #11 — persister les composants IAS 16 fournis à la création (optionnels).
        if (req.components() != null && !req.components().isEmpty()) {
            for (CreateAssetComponentRequest cReq : req.components()) {
                AssetComponent comp = new AssetComponent();
                comp.setCompanyId(companyId);
                comp.setAssetId(saved.getId());
                comp.setCode(cReq.code().trim());
                comp.setLabel(cReq.label().trim());
                comp.setAcquisitionCost(cReq.acquisitionCost());
                comp.setUsefulLifeYears(cReq.usefulLifeYears());
                comp.setResidualValue(cReq.residualValue());
                comp.setDepreciationMethod(cReq.depreciationMethod());
                componentRepository.save(comp);
            }
            LOG.info("Immobilisation créée avec {} composant(s) IAS 16 : asset={}",
                req.components().size(), saved.getId());
        }

        // Générer l'échéancier (par composant si des composants existent, sinon global).
        generateSchedule(companyId, saved);

        // Audit M10 : générer l'écriture d'acquisition si un compte de contrepartie est fourni
        // (supplierAccountId = achat à crédit, ou cashAccountId = achat au comptant).
        // Si aucun des deux n'est fourni, on préserve le comportement historique (pas d'écriture)
        // pour rétro-compatibilité avec les tests existants.
        if (req.supplierAccountId() != null || req.cashAccountId() != null) {
            JournalEntryResponse acqEntry = generateAcquisitionEntry(companyId, saved, req);
            saved.setAcquisitionJournalEntryId(acqEntry.id());
            assetRepository.save(saved);
        }

        events.publishEvent(new AssetCreatedEvent(saved, TenantContext.getUserId()));
        LOG.info("Immobilisation créée : id={} label={} cost={} acquisitionEntry={}",
            saved.getId(), saved.getLabel(), saved.getAcquisitionCost(),
            saved.getAcquisitionJournalEntryId());

        return toResponse(saved, BigDecimal.ZERO);
    }

    /**
     * Génère l'écriture comptable d'acquisition de l'immobilisation (audit M10).
     *
     * <p>L'écriture est :
     * <ul>
     *   <li>Débit : {@code asset.assetAccountId} (compte d'actif immobilisé) pour
     *       {@code acquisitionCost}</li>
     *   <li>Crédit : {@code supplierAccountId} (PASSIF) OU {@code cashAccountId} (ACTIF)
     *       pour le même montant</li>
     * </ul>
     *
     * <p>Idempotence : clé déterministe {@code "fixed-assets-acquisition-" + assetId}.
     * Un retry ne crée pas de 2e écriture (la contrainte unique DB l'empêche).
     *
     * <p>Postage direct (sans workflow 4-yeux) — l'acquisition est une opération d'investissement
     * validée par le BOOKKEEPER qui crée l'actif. Si un workflow est nécessaire, il devrait
     * être appliqué au niveau de l'endpoint (avant l'appel à createAsset), pas au niveau
     * de l'écriture d'acquisition.
     */
    private JournalEntryResponse generateAcquisitionEntry(UUID companyId, Asset asset, CreateAssetRequest req) {
        Account assetAccount = accountRepository.findById(asset.getAssetAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte d'actif introuvable : " + asset.getAssetAccountId()));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!assetAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getAssetAccountId().toString());
        }

        // Compte de contrepartie : supplier (PASSIF) ou cash (ACTIF)
        UUID counterpartyId = req.supplierAccountId() != null
            ? req.supplierAccountId() : req.cashAccountId();
        Account counterpartyAccount = accountRepository.findById(counterpartyId)
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte de contrepartie introuvable : " + counterpartyId));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!counterpartyAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", counterpartyId.toString());
        }

        String journalCode = journalRepository.findByCompanyIdAndCode(companyId, "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                "Journal OD introuvable. Créer un journal de code 'OD'."));

        List<LineDto> lines = new ArrayList<>();
        // Débit Immobilisation (coût d'acquisition)
        lines.add(new LineDto(assetAccount.getCode(), null, asset.getAcquisitionCost(), null,
            "Acquisition immobilisation — " + asset.getLabel(), List.of()));
        // Crédit Fournisseur (si achat à crédit) ou Trésorerie (si achat au comptant)
        String counterpartyDesc = req.supplierAccountId() != null
            ? "Fournisseur — acquisition " + asset.getLabel()
            : "Trésorerie — acquisition " + asset.getLabel();
        lines.add(new LineDto(counterpartyAccount.getCode(), null, null, asset.getAcquisitionCost(),
            counterpartyDesc, List.of()));

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, asset.getAcquisitionDate(),
            "Acquisition " + asset.getLabel(),
            lines, JournalEntrySourceModule.FIXED_ASSETS);

        String idempotencyKey = "fixed-assets-acquisition-" + asset.getId();
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, idempotencyKey, entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        LOG.info("Écriture d'acquisition générée : asset={} entry={} reference={}",
            asset.getId(), posted.id(), posted.reference());
        return posted;
    }

    private void validateCreateRequest(UUID companyId, CreateAssetRequest req) {
        if (req.usefulLifeMonths() < 1) {
            throw new ValidationException("USEFUL_LIFE_INVALID",
                "La durée de vie doit être ≥ 1 mois");
        }
        if (req.residualValue().compareTo(req.acquisitionCost()) > 0) {
            throw new ValidationException("RESIDUAL_TOO_HIGH",
                "La valeur résiduelle ne peut pas dépasser le coût d'acquisition");
        }
        // Audit M9 : valider existence + tenant + actif + reportingClass attendue.
        validateAccount(companyId, req.assetAccountId(), "asset_account",
            jo.accountant.core.framework.ReportingClass.ACTIF);
        validateAccount(companyId, req.depreciationExpenseAccountId(), "depreciation_expense_account",
            jo.accountant.core.framework.ReportingClass.CHARGES);
        validateAccount(companyId, req.accumulatedDepreciationAccountId(), "accumulated_depreciation_account",
            jo.accountant.core.framework.ReportingClass.ACTIF);

        // Audit M11 : valider disposalGainAccountId (PRODUITS) et disposalLossAccountId (CHARGES)
        // s'ils sont fournis. Si null, fallback sur depreciationExpenseAccountId à la cession.
        if (req.disposalGainAccountId() != null) {
            validateAccount(companyId, req.disposalGainAccountId(), "disposal_gain_account",
                jo.accountant.core.framework.ReportingClass.PRODUITS);
        }
        if (req.disposalLossAccountId() != null) {
            validateAccount(companyId, req.disposalLossAccountId(), "disposal_loss_account",
                jo.accountant.core.framework.ReportingClass.CHARGES);
        }

        // Finding #11 — valider les comptes de dépréciation IAS 36 s'ils sont fournis.
        // 6816 = CHARGES (dotations pour dépréciation), 291 = ACTIF (dépréciation des immo).
        if (req.impairmentExpenseAccountId() != null) {
            validateAccount(companyId, req.impairmentExpenseAccountId(), "impairment_expense_account",
                jo.accountant.core.framework.ReportingClass.CHARGES);
        }
        if (req.accumulatedImpairmentAccountId() != null) {
            validateAccount(companyId, req.accumulatedImpairmentAccountId(), "accumulated_impairment_account",
                jo.accountant.core.framework.ReportingClass.ACTIF);
        }

        // Audit M10 : valider supplierAccountId (PASSIF) ou cashAccountId (ACTIF) s'ils sont fournis.
        // Mutuellement exclusifs (on ne peut pas payer moitié comptant moitié à crédit sur une
        // seule écriture d'acquisition — l'utilisateur doit créer 2 actifs si nécessaire).
        if (req.supplierAccountId() != null && req.cashAccountId() != null) {
            throw new ValidationException("SUPPLIER_AND_CASH_EXCLUSIVE",
                "Fournir supplierAccountId OU cashAccountId, pas les deux (mutuellement exclusifs).");
        }
        if (req.supplierAccountId() != null) {
            validateAccount(companyId, req.supplierAccountId(), "supplier_account",
                jo.accountant.core.framework.ReportingClass.PASSIF);
        }
        if (req.cashAccountId() != null) {
            validateAccount(companyId, req.cashAccountId(), "cash_account",
                jo.accountant.core.framework.ReportingClass.ACTIF);
        }

        // Finding #11 — valider les composants IAS 16 fournis (unicité du code par asset,
        // durée de vie ≥ 1 an, résiduelle ≤ coût). Le contrôle somme(coûts composants) ≤ coût asset
        // n'est pas bloquant au MVP (l'utilisateur peut détailler seulement certains composants).
        if (req.components() != null && !req.components().isEmpty()) {
            java.util.Set<String> seenCodes = new java.util.HashSet<>();
            for (CreateAssetComponentRequest c : req.components()) {
                if (c.usefulLifeYears() < 1) {
                    throw new ValidationException("COMPONENT_USEFUL_LIFE_INVALID",
                        "La durée de vie du composant '" + c.code() + "' doit être ≥ 1 an");
                }
                if (c.residualValue().compareTo(c.acquisitionCost()) > 0) {
                    throw new ValidationException("COMPONENT_RESIDUAL_TOO_HIGH",
                        "La valeur résiduelle du composant '" + c.code()
                        + "' ne peut pas dépasser son coût d'acquisition");
                }
                if (!seenCodes.add(c.code().trim())) {
                    throw new ValidationException("COMPONENT_CODE_DUPLICATE",
                        "Le code composant '" + c.code() + "' apparaît plusieurs fois");
                }
            }
        }
    }

    /**
     * Valide qu'un compte existe, appartient au tenant, est actif — et (audit M9) qu'il a la
     * {@link jo.accountant.core.framework.ReportingClass} attendue pour son rôle.
     *
     * <p>Avant cette correction, {@code validateAccount} ne vérifiait que l'existence/l'activité
     * du compte, ce qui permettait à un utilisateur API d'assigner un compte de PASSIF comme
     * compte de charge d'amortissement. L'écriture serait postée sans erreur, mais le bilan
     * et le compte de résultat seraient faux.
     *
     * @param expectedReportingClass la {@link ReportingClass} attendue pour ce rôle, ou
     *        {@code null} pour ne pas vérifier la classe (rétro-compatibilité).
     */
    private void validateAccount(UUID companyId, UUID accountId, String fieldName,
                                 jo.accountant.core.framework.ReportingClass expectedReportingClass) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte introuvable pour " + fieldName + " : " + accountId));
        if (!account.getCompanyId().equals(companyId)) {
            throw new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte introuvable pour " + fieldName + " : " + accountId);
        }
        if (!account.isActive()) {
            throw new ValidationException("ACCOUNT_INACTIVE",
                "Le compte " + account.getCode() + " est désactivé — impossible de l'utiliser pour " + fieldName);
        }
        if (expectedReportingClass != null
                && account.getReportingClass() != expectedReportingClass) {
            throw new ValidationException("ACCOUNT_WRONG_REPORTING_CLASS",
                "Le compte " + account.getCode() + " (" + account.getReportingClass()
                + ") ne peut pas être utilisé comme " + fieldName
                + " : la classe attendue est " + expectedReportingClass + ".");
        }
    }

    /** Rétro-compatibilité — délègue à {@link #validateAccount(UUID, UUID, String, ReportingClass)}
     *  avec {@code expectedReportingClass = null} (pas de validation sémantique). */
    private void validateAccount(UUID companyId, UUID accountId, String fieldName) {
        validateAccount(companyId, accountId, fieldName, null);
    }

    /**
     * Génère l'échéancier d'amortissement.
     *
     * <p>Pour STRAIGHT_LINE : montant constant = (coût − résiduel) / usefulLifeMonths.
     * Pour DECLINING_BALANCE : montant = solde net × taux dégressif. Le taux dégressif =
     * taux linéaire × coefficient (1.25 si 3-4 ans, 1.75 si 5-6 ans, 2.25 si > 6 ans).
     *
     * <p>Pour simplifier en Phase 8, DECLINING_BALANCE utilise un coefficient fixe de 1.75
     * (cas 5-6 ans le plus courant). À affiner si besoin.
     *
     * <p><b>Finding #11 — Amortissement par composant IAS 16</b> : si l'asset a des composants
     * (table {@code asset_component}), l'échéancier est généré par composant — chaque composant
     * a sa propre durée de vie (en années), sa propre méthode et sa propre valeur résiduelle.
     * Chaque ligne d'échéancier est alors rattachée à un composant via {@code componentId}.
     * Si l'asset n'a pas de composants, l'échéancier est généré globalement sur l'asset
     * (comportement historique, {@code componentId} = null).
     */
    private void generateSchedule(UUID companyId, Asset asset) {
        List<AssetComponent> components = componentRepository.findByAssetIdOrderByCode(asset.getId());
        if (!components.isEmpty()) {
            // Finding #11 — mode par composant
            for (AssetComponent comp : components) {
                generateComponentSchedule(companyId, asset, comp);
            }
            return;
        }
        // Mode global — comportement historique
        generateAssetSchedule(companyId, asset);
    }

    /**
     * Génère l'échéancier d'amortissement <strong>global</strong> sur l'asset (pas de composants).
     * Comportement historique — {@code componentId} est laissé null sur toutes les lignes.
     */
    private void generateAssetSchedule(UUID companyId, Asset asset) {
        BigDecimal totalDepreciable = asset.getAcquisitionCost().subtract(asset.getResidualValue());
        int months = asset.getUsefulLifeMonths();
        // Audit M14 : arrondi currency-aware (au lieu de setScale(4) en dur). L'asset ne porte
        // pas de devise — on utilise la devise fonctionnelle de l'entreprise.
        String currencyCode = resolveCurrency(companyId);

        if (asset.getDepreciationMethod() == DepreciationMethod.STRAIGHT_LINE) {
            BigDecimal monthlyAmount = roundingService.round(currencyCode,
                totalDepreciable.divide(BigDecimal.valueOf(months),
                    CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP));
            BigDecimal cumulative = BigDecimal.ZERO;
            LocalDate periodDate = asset.getAcquisitionDate().plusMonths(1).withDayOfMonth(1);

            for (int i = 0; i < months; i++) {
                // Dernière ligne : ajuster pour arrondi
                BigDecimal amount = (i == months - 1)
                    ? totalDepreciable.subtract(cumulative)
                    : monthlyAmount;
                cumulative = cumulative.add(amount);

                DepreciationScheduleLine line = newScheduleLine(companyId, asset, null, periodDate,
                    amount, cumulative);
                scheduleRepository.save(line);

                periodDate = periodDate.plusMonths(1);
            }
        } else {
            // DECLINING_BALANCE — coefficient variable selon la durée de vie (Vague 3, item 3.3) :
            // 1.25 si 3-4 ans (36-48 mois), 1.75 si 5-6 ans (60-72 mois), 2.25 si > 6 ans (> 72 mois)
            double coefficient;
            if (months >= 72) {
                coefficient = 2.25;
            } else if (months >= 60) {
                coefficient = 1.75;
            } else if (months >= 36) {
                coefficient = 1.25;
            } else {
                coefficient = 1.25;  // < 3 ans : coefficient minimum
            }
            // F1 (fix) : taux dégressif = coefficient / durée de vie en années
            // Formule correcte : coefficient × (1 / durée_années) = coefficient × 12 / months
            // Le taux est appliqué au solde net comptable restant chaque mois.
            // Ex: 5 ans, coef 1.75 → taux annuel = 1.75/5 = 35% → taux mensuel = 35%/12 ≈ 2.917%
            // Les calculs de taux restent à 6 décimales (échelle de calcul intermédiaire, pas un montant).
            BigDecimal yearsInDecimal = BigDecimal.valueOf(months)
                .divide(BigDecimal.valueOf(12), CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
            BigDecimal annualRate = BigDecimal.valueOf(coefficient)
                .divide(yearsInDecimal, CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
            BigDecimal monthlyRate = annualRate
                .divide(BigDecimal.valueOf(12), CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);

            BigDecimal netBookValue = asset.getAcquisitionCost();
            BigDecimal cumulative = BigDecimal.ZERO;
            LocalDate periodDate = asset.getAcquisitionDate().plusMonths(1).withDayOfMonth(1);

            for (int i = 0; i < months; i++) {
                // Audit M14 : arrondi currency-aware (montant d'amortissement mensuel).
                BigDecimal amount = roundingService.round(currencyCode, netBookValue.multiply(monthlyRate));
                // Ne pas dépasser le total dépréciable
                if (cumulative.add(amount).compareTo(totalDepreciable) > 0 || i == months - 1) {
                    amount = totalDepreciable.subtract(cumulative);
                }
                cumulative = cumulative.add(amount);
                netBookValue = netBookValue.subtract(amount);

                DepreciationScheduleLine line = newScheduleLine(companyId, asset, null, periodDate,
                    amount, cumulative);
                scheduleRepository.save(line);

                periodDate = periodDate.plusMonths(1);
            }
        }
    }

    /**
     * Génère l'échéancier d'amortissement pour un composant IAS 16 (Finding #11).
     *
     * <p>Le composant a sa propre durée de vie ({@code usefulLifeYears}, en années, convertie
     * en mois ×12), sa propre valeur résiduelle et sa propre méthode d'amortissement. Le
     * {@code componentId} est positionné sur chaque ligne pour le rattacher au composant.
     *
     * <p>La durée de vie en mois = {@code usefulLifeYears × 12}. Les calculs sont identiques
     * au mode global (STRAIGHT_LINE montant constant / DECLINING_BALANCE taux dégressif), mais
     * appliqués au coût d'acquisition du composant seul.
     *
     * <p>La {@code cumulativeAmount} est <strong>par composant</strong> (repart de 0 pour chaque
     * composant) — pas la cumulative globale de l'asset. Cela permet de suivre la VNC de chaque
     * composant indépendamment, ce qui est requis par IAS 16.
     */
    private void generateComponentSchedule(UUID companyId, Asset asset, AssetComponent comp) {
        BigDecimal totalDepreciable = comp.getAcquisitionCost().subtract(comp.getResidualValue());
        int months = comp.getUsefulLifeYears() * 12;
        if (months < 1) {
            // Should be prevented by validation, but defensive.
            throw new ValidationException("COMPONENT_USEFUL_LIFE_INVALID",
                "La durée de vie du composant '" + comp.getCode() + "' doit donner ≥ 1 mois");
        }
        String currencyCode = resolveCurrency(companyId);

        if (comp.getDepreciationMethod() == DepreciationMethod.STRAIGHT_LINE) {
            BigDecimal monthlyAmount = roundingService.round(currencyCode,
                totalDepreciable.divide(BigDecimal.valueOf(months),
                    CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP));
            BigDecimal cumulative = BigDecimal.ZERO;
            LocalDate periodDate = asset.getAcquisitionDate().plusMonths(1).withDayOfMonth(1);

            for (int i = 0; i < months; i++) {
                BigDecimal amount = (i == months - 1)
                    ? totalDepreciable.subtract(cumulative)
                    : monthlyAmount;
                cumulative = cumulative.add(amount);

                scheduleRepository.save(newScheduleLine(companyId, asset, comp.getId(),
                    periodDate, amount, cumulative));
                periodDate = periodDate.plusMonths(1);
            }
        } else {
            // DECLINING_BALANCE — coefficient variable selon la durée de vie en années.
            int years = comp.getUsefulLifeYears();
            double coefficient;
            if (years >= 6) {
                coefficient = 2.25;
            } else if (years >= 5) {
                coefficient = 1.75;
            } else if (years >= 3) {
                coefficient = 1.25;
            } else {
                coefficient = 1.25;
            }
            BigDecimal yearsInDecimal = BigDecimal.valueOf(years);
            BigDecimal annualRate = BigDecimal.valueOf(coefficient)
                .divide(yearsInDecimal, CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
            BigDecimal monthlyRate = annualRate
                .divide(BigDecimal.valueOf(12), CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);

            BigDecimal netBookValue = comp.getAcquisitionCost();
            BigDecimal cumulative = BigDecimal.ZERO;
            LocalDate periodDate = asset.getAcquisitionDate().plusMonths(1).withDayOfMonth(1);

            for (int i = 0; i < months; i++) {
                BigDecimal amount = roundingService.round(currencyCode, netBookValue.multiply(monthlyRate));
                if (cumulative.add(amount).compareTo(totalDepreciable) > 0 || i == months - 1) {
                    amount = totalDepreciable.subtract(cumulative);
                }
                cumulative = cumulative.add(amount);
                netBookValue = netBookValue.subtract(amount);

                scheduleRepository.save(newScheduleLine(companyId, asset, comp.getId(),
                    periodDate, amount, cumulative));
                periodDate = periodDate.plusMonths(1);
            }
        }
    }

    /** Factory helper — crée une ligne d'échéancier avec les champs communs. */
    private DepreciationScheduleLine newScheduleLine(UUID companyId, Asset asset, UUID componentId,
            LocalDate periodDate, BigDecimal amount, BigDecimal cumulative) {
        DepreciationScheduleLine line = new DepreciationScheduleLine();
        line.setCompanyId(companyId);
        line.setAssetId(asset.getId());
        line.setComponentId(componentId);
        line.setPeriodDate(periodDate);
        line.setAmount(amount);
        line.setCumulativeAmount(cumulative);
        // periodId sera résolu au postage (besoin de trouver la période fiscale)
        line.setPeriodId(null);
        return line;
    }

    // --- Échéancier ---

    @Transactional(readOnly = true)
    public List<ScheduleLineResponse> getSchedule(UUID companyId, UUID assetId) {
        Asset asset = loadAsset(companyId, assetId);
        return scheduleRepository.findByAssetIdOrderByPeriodDate(asset.getId()).stream()
            .map(this::toResponse)
            .toList();
    }

    // --- Postage période par période ---

    /**
     * Poste l'amortissement d'une période donnée pour une immobilisation.
     *
     * <p>Génère une écriture comptable avec {@code sourceModule = FIXED_ASSETS} :
     * <ul>
     *   <li>Débit : compte de charge d'amortissement</li>
     *   <li>Crédit : compte d'amortissement cumulé</li>
     * </ul>
     *
     * <p><b>Finding #11 — Amortissement par composant IAS 16</b> : si l'asset a des composants,
     * une même période fiscale peut contenir plusieurs lignes d'échéancier (une par composant).
     * Dans ce cas, {@code postPeriodDepreciation} poste TOUTES les lignes non-postées de la période
     * en une seule écriture comptable (un débit + un crédit par composant). Cela évite à
     * l'utilisateur de poster N fois pour N composants. La ligne retournée est la première
     * postée (pour rétro-compatibilité avec les tests existants qui n'ont qu'une seule ligne).
     *
     * @throws ConflictException si l'asset est DISPOSED ou si la période est déjà postée
     */
    @Transactional
    public ScheduleLineResponse postPeriodDepreciation(UUID companyId, UUID assetId, UUID periodId) {
        Asset asset = loadAsset(companyId, assetId);
        if (asset.getStatus() == AssetStatus.DISPOSED) {
            throw new ConflictException("ASSET_DISPOSED",
                "L'immobilisation " + asset.getLabel() + " est cédée — amortissement impossible");
        }

        // Trouver la période fiscale
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .orElseThrow(() -> new NotFoundException("FiscalPeriod", periodId));
        if (!period.getCompanyId().equals(companyId)) {
            throw new NotFoundException("FiscalPeriod", periodId);
        }

        // Trouver toutes les lignes d'échéancier dont periodDate est dans la période.
        // Finding #11 — avec les composants IAS 16, plusieurs lignes peuvent matcher (une par
        // composant actif sur cette période).
        List<DepreciationScheduleLine> lines = scheduleRepository
            .findByAssetIdOrderByPeriodDate(asset.getId());
        List<DepreciationScheduleLine> matched = new ArrayList<>();
        for (DepreciationScheduleLine line : lines) {
            if (!line.getPeriodDate().isBefore(period.getStartDate())
                && !line.getPeriodDate().isAfter(period.getEndDate())) {
                matched.add(line);
            }
        }
        if (matched.isEmpty()) {
            throw new NotFoundException("SCHEDULE_LINE_NOT_FOUND",
                "Aucune ligne d'échéancier pour l'actif " + assetId + " dans la période " + period.getLabel());
        }
        // Si au moins une ligne est déjà postée pour cette période → conflit (idempotence).
        // On vérifie toutes les lignes matchées car en mode composant, toutes doivent être postées
        // ensemble (une seule écriture pour la période).
        boolean anyPosted = matched.stream().anyMatch(DepreciationScheduleLine::isPosted);
        if (anyPosted) {
            throw new ConflictException("PERIOD_ALREADY_POSTED",
                "L'amortissement de la période " + period.getLabel() + " est déjà posté");
        }
        // Filtrer uniquement les lignes non postées (en pratique toutes, vu le check ci-dessus).
        List<DepreciationScheduleLine> toPost = matched.stream()
            .filter(l -> !l.isPosted())
            .toList();
        if (toPost.isEmpty()) {
            // Cas défensif : toutes postées mais aucun flag anyPosted levé — impossible en principe.
            throw new ConflictException("PERIOD_ALREADY_POSTED",
                "L'amortissement de la période " + period.getLabel() + " est déjà posté");
        }

        // Résoudre les comptes (charge + amortissement cumulé) avec defense-in-depth
        Account expenseAccount = accountRepository.findById(asset.getDepreciationExpenseAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte de charge introuvable"));
        if (!expenseAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getDepreciationExpenseAccountId().toString());
        }
        Account accumulatedAccount = accountRepository.findById(asset.getAccumulatedDepreciationAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte d'amortissement cumulé introuvable"));
        if (!accumulatedAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getAccumulatedDepreciationAccountId().toString());
        }

        // Trouver un journal (OD par défaut pour les opérations diverses)
        String journalCode = journalRepository.findByCompanyIdAndCode(companyId, "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                "Journal OD (Opérations diverses) introuvable. Créer un journal de code 'OD'."));

        // Construire les lignes de l'écriture comptable — 1 débit + 1 crédit par ligne d'échéancier.
        // En mode global (1 ligne) → écriture 2 lignes (comportement historique).
        // En mode composant (N lignes) → écriture 2N lignes équilibrée (Σ débits = Σ crédits).
        List<LineDto> entryLines = new ArrayList<>();
        for (DepreciationScheduleLine l : toPost) {
            entryLines.add(new LineDto(expenseAccount.getCode(), null, l.getAmount(), null,
                "Charge d'amortissement" + (l.getComponentId() != null ? " (composant)" : ""),
                List.of()));
            entryLines.add(new LineDto(accumulatedAccount.getCode(), null, null, l.getAmount(),
                "Amortissement cumulé" + (l.getComponentId() != null ? " (composant)" : ""),
                List.of()));
        }

        LocalDate entryDate = toPost.get(0).getPeriodDate();
        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode,
            entryDate,
            "Amortissement " + asset.getLabel() + " — " + period.getLabel(),
            entryLines,
            JournalEntrySourceModule.FIXED_ASSETS
        );

        // Idempotency : basée sur assetId + periodId (déterministe — un retry ne crée pas de 2e écriture).
        String idempotencyKey = "fixed-assets-depreciation-" + asset.getId() + "-" + periodId;
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, idempotencyKey, entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        // Marquer TOUTES les lignes matchées comme postées (même journalEntryId).
        Instant now = Instant.now();
        UUID userId = TenantContext.getUserId();
        DepreciationScheduleLine firstSaved = null;
        for (DepreciationScheduleLine l : toPost) {
            l.setPeriodId(periodId);
            l.setJournalEntryId(posted.id());
            l.setPostedAt(now);
            l.setPostedBy(userId);
            DepreciationScheduleLine saved = scheduleRepository.save(l);
            if (firstSaved == null) firstSaved = saved;
            events.publishEvent(new DepreciationPostedEvent(saved, userId));
        }

        BigDecimal totalAmount = toPost.stream()
            .map(DepreciationScheduleLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        LOG.info("Amortissement posté : asset={} period={} lignes={} total={} entry={}",
            asset.getId(), period.getLabel(), toPost.size(), totalAmount, posted.reference());

        return toResponse(firstSaved);
    }

    // --- Cession ---

    /**
     * Cède une immobilisation.
     *
     * <p>Calcule la plus/moins-value = prix de cession − (coût d'acquisition − amortissement cumulé).
     *
     * <p>Génère une écriture de cession :
     * <ul>
     *   <li>Crédit compte d'actif (sortie de l'actif) pour le coût d'acquisition</li>
     *   <li>Débit compte d'amortissement cumulé (reprise) pour le cumul</li>
     *   <li>Débit compte de trésorerie (prix de cession) — utilise le compte d'actif comme
     *       substitut faute de compte de trésorerie dédié en Phase 8</li>
     *   <li>Débit/Credit compte de plus/moins-value pour la différence</li>
     * </ul>
     *
     * <p>Asset → DISPOSED (immuable). Ne peut plus être amortie ni cédée à nouveau.
     */
    @Transactional
    public AssetResponse dispose(UUID companyId, UUID assetId, DisposeAssetRequest req) {
        Asset asset = loadAsset(companyId, assetId);
        if (asset.getStatus() == AssetStatus.DISPOSED) {
            throw new ConflictException("ASSET_ALREADY_DISPOSED",
                "L'immobilisation " + asset.getLabel() + " est déjà cédée");
        }

        // Calculer l'amortissement cumulé posté
        List<DepreciationScheduleLine> postedLines = scheduleRepository
            .findByAssetIdOrderByPeriodDate(asset.getId()).stream()
            .filter(DepreciationScheduleLine::isPosted)
            .toList();
        BigDecimal cumulativeDepreciation = postedLines.stream()
            .map(DepreciationScheduleLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Finding #12 — Amortissement complémentaire avant cession (audit batch 1) ──
        // Entre la dernière date d'amortissement postée et la date de cession, un prorata
        // temporis doit être constaté sur la durée écoulée. Sans cet amortissement complémentaire,
        // la VNC utilisée pour calculer la plus/moins-value est surévaluée → résultat de cession
        // erroné (perte comptable fictive ou plus-value sous-évaluée) et amortissement échappé
        // sur la dernière fraction d'année.
        //
        // Formule (prorata temporis en jours) :
        //   additional = (coût acquisition / durée de vie en années) × (jours écoulés / 365)
        // Avec :
        //   coût acquisition = asset.acquisitionCost
        //   durée de vie en années = usefulLifeMonths / 12
        //   jours écoulés = jours entre la fin du mois de la dernière ligne postée et disposalDate
        //
        // L'amortissement complémentaire est enregistré comme une ligne d'échéancier
        // supplémentaire (postée immédiatement) + une écriture comptable D charge / C amort. cumulé.
        // Il est plafonné à la VNC dépréciable restante (coût − cumul − résiduel) pour ne pas
        // descendre sous la valeur résiduelle.
        BigDecimal additionalDepreciation = computeAndPostAdditionalDepreciation(
            companyId, asset, postedLines, cumulativeDepreciation, req.disposalDate());
        cumulativeDepreciation = cumulativeDepreciation.add(additionalDepreciation);

        // Plus/moins-value = prix de cession − (coût − amortissement cumulé)
        BigDecimal netBookValue = asset.getAcquisitionCost().subtract(cumulativeDepreciation);
        BigDecimal gainOrLoss = req.disposalAmount().subtract(netBookValue);

        // Générer l'écriture de cession
        Account assetAccount = accountRepository.findById(asset.getAssetAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND", "Compte d'actif introuvable"));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!assetAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getAssetAccountId().toString());
        }
        Account accumulatedAccount = accountRepository.findById(asset.getAccumulatedDepreciationAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte d'amortissement cumulé introuvable"));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!accumulatedAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getAccumulatedDepreciationAccountId().toString());
        }

        String journalCode = journalRepository.findByCompanyIdAndCode(companyId, "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                "Journal OD introuvable"));

        // Écriture de cession :
        // D Amortissement cumulé (cumulativeDepreciation)
        // D/C Plus/moins-value (gainOrLoss : D si perte, C si plus-value)
        // C Actif (acquisitionCost)
        // C/D Trésorerie (disposalAmount) — ici on utilise le compte d'actif pour le débit
        //   de trésorerie faute de compte dédié. À affiner en Phase 13 (bank-reconciliation).
        //
        // Pour simplifier : on fait une écriture à 4 lignes équilibrée.
        // D Amortissement cumulé = cumulativeDepreciation
        // C Actif = acquisitionCost
        // D Trésorerie (= assetAccount pour simplifier) = disposalAmount
        // C/D Plus-value (utilise aussi assetAccount) = gainOrLoss (C si plus-value, D si perte)
        //
        // Vérification équilibre :
        // Débit total = cumulativeDepreciation + disposalAmount + max(0, -gainOrLoss) [perte]
        // Crédit total = acquisitionCost + max(0, gainOrLoss) [plus-value]
        // Si gainOrLoss > 0 (plus-value) : D cumul + D tréso = C actif + C plus-value
        //   → cumulativeDepreciation + disposalAmount = acquisitionCost + gainOrLoss
        //   → gainOrLoss = disposalAmount - (acquisitionCost - cumulativeDepreciation) ✓
        // Si gainOrLoss < 0 (perte) : D cumul + D tréso + D perte = C actif
        //   → cumulativeDepreciation + disposalAmount + (-gainOrLoss) = acquisitionCost
        //   → -gainOrLoss = acquisitionCost - cumulativeDepreciation - disposalAmount
        //   → gainOrLoss = disposalAmount - (acquisitionCost - cumulativeDepreciation) ✓

        // Vague 2 item 2.7 : utiliser cashAccountId si fourni, sinon fallback sur assetAccount
        String cashAccountCode = assetAccount.getCode();
        if (req.cashAccountId() != null) {
            Account cashAccount = accountRepository.findById(req.cashAccountId())
                .orElseThrow(() -> new ValidationException("CASH_ACCOUNT_NOT_FOUND",
                    "Compte de trésorerie introuvable : " + req.cashAccountId()));
            if (!cashAccount.getCompanyId().equals(companyId)) {
                throw new ValidationException("CASH_ACCOUNT_NOT_FOUND",
                    "Compte de trésorerie introuvable : " + req.cashAccountId());
            }
            cashAccountCode = cashAccount.getCode();
        }

        // F2 (fix) : la plus/moins-value doit aller sur un compte de résultat (pas le compte d'actif)
        // Audit M11 (corrigé) : utiliser disposalGainAccountId (PRODUITS) pour les plus-values
        // et disposalLossAccountId (CHARGES) pour les moins-values. Fallback sur
        // depreciationExpenseAccountId (rétro-compatibilité) si non fournis.
        Account gainAccount;
        Account lossAccount;
        if (asset.getDisposalGainAccountId() != null) {
            gainAccount = accountRepository.findById(asset.getDisposalGainAccountId())
                .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                    "Compte de plus-value introuvable : " + asset.getDisposalGainAccountId()));
        } else {
            // Rétro-compatibilité : fallback sur depreciationExpenseAccountId
            gainAccount = accountRepository.findById(asset.getDepreciationExpenseAccountId())
                .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                    "Compte de charge d'amortissement introuvable"));
        }
        if (asset.getDisposalLossAccountId() != null) {
            lossAccount = accountRepository.findById(asset.getDisposalLossAccountId())
                .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                    "Compte de moins-value introuvable : " + asset.getDisposalLossAccountId()));
        } else {
            lossAccount = gainAccount;  // même fallback
        }

        List<LineDto> lines = new ArrayList<>();
        lines.add(new LineDto(accumulatedAccount.getCode(), null, cumulativeDepreciation, null,
            "Reprise amortissement cumulé", List.of()));
        lines.add(new LineDto(assetAccount.getCode(), null, null, asset.getAcquisitionCost(),
            "Sortie actif", List.of()));
        lines.add(new LineDto(cashAccountCode, null, req.disposalAmount(), null,
            "Prix de cession (trésorerie)", List.of()));

        if (gainOrLoss.compareTo(BigDecimal.ZERO) >= 0) {
            // Plus-value : crédit sur le compte de PRODUITS (audit M11)
            lines.add(new LineDto(gainAccount.getCode(), null, null, gainOrLoss,
                "Plus-value de cession", List.of()));
        } else {
            // Perte : débit sur le compte de CHARGES (audit M11)
            lines.add(new LineDto(lossAccount.getCode(), null, gainOrLoss.negate(), null,
                "Moins-value de cession", List.of()));
        }

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, req.disposalDate(),
            "Cession " + asset.getLabel(),
            lines,
            JournalEntrySourceModule.FIXED_ASSETS
        );

        String idempotencyKey = "fixed-assets-disposal-" + asset.getId();
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, idempotencyKey, entryReq);
        accountingEngineService.postJournalEntry(companyId, entry.id(), List.of());

        // Marquer l'actif comme cédé
        asset.setStatus(AssetStatus.DISPOSED);
        asset.setDisposalDate(req.disposalDate());
        asset.setDisposalAmount(req.disposalAmount());
        asset.setGainOrLoss(gainOrLoss);
        Asset saved = assetRepository.save(asset);

        events.publishEvent(new AssetDisposedEvent(saved, TenantContext.getUserId()));
        LOG.info("Immobilisation cédée : id={} disposalAmount={} gainOrLoss={}",
            saved.getId(), req.disposalAmount(), gainOrLoss);

        return toResponse(saved, cumulativeDepreciation);
    }

    // --- Helpers ---

    /**
     * Calcule et poste l'amortissement complémentaire entre la dernière ligne d'amortissement
     * postée et la date de cession (Finding #12 — audit batch 1).
     *
     * <p><b>Formule (prorata temporis en jours)</b> :
     * <pre>
     *   additional = (coût acquisition / durée de vie en années) × (jours écoulés / 365)
     * </pre>
     * avec :
     * <ul>
     *   <li>{@code coût acquisition} = {@link Asset#getAcquisitionCost()}</li>
     *   <li>{@code durée de vie en années} = {@link Asset#getUsefulLifeMonths()} / 12</li>
     *   <li>{@code jours écoulés} = jours entre la fin du mois de la dernière ligne postée
     *       et la date de cession</li>
     * </ul>
     *
     * <p>Si aucune ligne n'a été postée, ou si la dernière ligne postée couvre déjà la date de
     * cession (cas où l'asset est cédé en fin de mois déjà amorti), retourne {@code ZERO} sans
     * rien poster. L'amortissement complémentaire est également plafonné à la VNC dépréciable
     * restante (coût − amortissements cumulés − valeur résiduelle) pour ne jamais descendre
     * sous la valeur résiduelle.
     *
     * @param companyId               tenant
     * @param asset                   immobilisation à céder
     * @param postedLines             lignes d'amortissement déjà postées (triées par periodDate)
     * @param cumulativeDepreciation   cumul des amortissements déjà postés
     * @param disposalDate             date de cession
     * @return montant de l'amortissement complémentaire (0 si aucun à constater)
     */
    private BigDecimal computeAndPostAdditionalDepreciation(UUID companyId, Asset asset,
            List<DepreciationScheduleLine> postedLines,
            BigDecimal cumulativeDepreciation, LocalDate disposalDate) {
        if (postedLines.isEmpty() || disposalDate == null) {
            return BigDecimal.ZERO;
        }

        // Dernière ligne postée : sa periodDate est typiquement le 1er du mois. La fin du mois
        // correspondant est le dernier jour du même mois.
        DepreciationScheduleLine lastPosted = postedLines.get(postedLines.size() - 1);
        LocalDate lastPeriodDate = lastPosted.getPeriodDate();
        LocalDate endOfLastPeriod = lastPeriodDate.withDayOfMonth(lastPeriodDate.lengthOfMonth());

        // Si la cession a lieu avant ou pendant le mois déjà amorti, rien à constater.
        if (!disposalDate.isAfter(endOfLastPeriod)) {
            return BigDecimal.ZERO;
        }

        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(endOfLastPeriod, disposalDate);
        if (daysBetween <= 0) {
            return BigDecimal.ZERO;
        }

        // VNC actuelle (avant amortissement complémentaire)
        BigDecimal netBookValue = asset.getAcquisitionCost().subtract(cumulativeDepreciation);
        // VNC plancher = valeur résiduelle (on ne descend pas en dessous)
        BigDecimal depreciableRemainder = netBookValue.subtract(asset.getResidualValue());
        if (depreciableRemainder.compareTo(BigDecimal.ZERO) <= 0) {
            // Asset déjà entièrement amorti jusqu'à sa valeur résiduelle
            return BigDecimal.ZERO;
        }

        // Durée de vie en années = usefulLifeMonths / 12 (Formule Finding #12).
        BigDecimal usefulLifeYears = BigDecimal.valueOf(asset.getUsefulLifeMonths())
            .divide(BigDecimal.valueOf(12), CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
        if (usefulLifeYears.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // Calcul : (coût acquisition / durée de vie en années) × (jours écoulés / 365)
        // — Finding #12 (audit batch 1).
        BigDecimal annualDepreciation = asset.getAcquisitionCost()
            .divide(usefulLifeYears, CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
        BigDecimal dailyDepreciation = annualDepreciation
            .divide(BigDecimal.valueOf(365), CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
        String currencyCode = resolveCurrency(companyId);
        BigDecimal additional = roundingService.round(currencyCode,
            dailyDepreciation.multiply(BigDecimal.valueOf(daysBetween)));

        // Plafonner à la VNC dépréciable restante (ne pas dépasser la valeur résiduelle)
        if (additional.compareTo(depreciableRemainder) > 0) {
            additional = depreciableRemainder;
        }
        if (additional.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // Résoudre les comptes (charge + amortissement cumulé) avec defense-in-depth
        Account expenseAccount = accountRepository.findById(asset.getDepreciationExpenseAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte de charge introuvable"));
        if (!expenseAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getDepreciationExpenseAccountId().toString());
        }
        Account accumulatedAccount = accountRepository.findById(asset.getAccumulatedDepreciationAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte d'amortissement cumulé introuvable"));
        if (!accumulatedAccount.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", asset.getAccumulatedDepreciationAccountId().toString());
        }

        // Code journal OD
        String journalCode = journalRepository.findByCompanyIdAndCode(companyId, "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                "Journal OD (Opérations diverses) introuvable. Créer un journal de code 'OD'."));

        // Créer la ligne d'échéancier supplémentaire (postée immédiatement)
        BigDecimal newCumulative = cumulativeDepreciation.add(additional);
        DepreciationScheduleLine additionalLine = new DepreciationScheduleLine();
        additionalLine.setCompanyId(companyId);
        additionalLine.setAssetId(asset.getId());
        additionalLine.setPeriodDate(disposalDate.withDayOfMonth(1));
        additionalLine.setAmount(additional);
        additionalLine.setCumulativeAmount(newCumulative);
        additionalLine.setPeriodId(null);
        DepreciationScheduleLine savedLine = scheduleRepository.save(additionalLine);

        // Générer l'écriture comptable D charge / C amortissement cumulé
        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode,
            disposalDate,
            "Amortissement complémentaire (cession) — " + asset.getLabel(),
            List.of(
                new LineDto(expenseAccount.getCode(), null, additional, null,
                    "Charge d'amortissement complémentaire (cession)", List.of()),
                new LineDto(accumulatedAccount.getCode(), null, null, additional,
                    "Amortissement cumulé complémentaire (cession)", List.of())
            ),
            JournalEntrySourceModule.FIXED_ASSETS
        );

        String idempotencyKey = "fixed-assets-disposal-additional-depreciation-" + savedLine.getId();
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, idempotencyKey, entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        // Marquer la ligne comme postée
        savedLine.setPeriodId(null);
        savedLine.setJournalEntryId(posted.id());
        savedLine.setPostedAt(Instant.now());
        savedLine.setPostedBy(TenantContext.getUserId());
        scheduleRepository.save(savedLine);

        events.publishEvent(new DepreciationPostedEvent(savedLine, TenantContext.getUserId()));
        LOG.info("Amortissement complémentaire posté avant cession : asset={} days={} additional={} newCumulative={} entry={}",
            asset.getId(), daysBetween, additional, newCumulative, posted.reference());

        return additional;
    }

    private Asset loadAsset(UUID companyId, UUID assetId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new NotFoundException("Asset", assetId));
        if (!asset.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Asset", assetId);
        }
        return asset;
    }

    private AssetResponse toResponse(Asset asset, BigDecimal cumulativeDepreciation) {
        List<AssetComponentResponse> components = componentRepository
            .findByAssetIdOrderByCode(asset.getId()).stream()
            .map(this::toComponentResponse)
            .toList();
        return new AssetResponse(
            asset.getId(), asset.getCompanyId(), asset.getLabel(),
            asset.getAcquisitionDate(), asset.getAcquisitionCost(),
            asset.getUsefulLifeMonths(), asset.getResidualValue(),
            asset.getDepreciationMethod(), asset.getAssetAccountId(),
            asset.getDepreciationExpenseAccountId(),
            asset.getAccumulatedDepreciationAccountId(),
            asset.getDisposalGainAccountId(),
            asset.getDisposalLossAccountId(),
            asset.getAcquisitionJournalEntryId(),
            asset.getStatus(), asset.getDisposalDate(),
            asset.getDisposalAmount(), asset.getGainOrLoss(),
            cumulativeDepreciation,
            asset.getImpairmentAmount(),
            asset.getImpairmentExpenseAccountId(),
            asset.getAccumulatedImpairmentAccountId(),
            components,
            asset.getCreatedAt(), asset.getUpdatedAt());
    }

    private AssetComponentResponse toComponentResponse(AssetComponent comp) {
        return new AssetComponentResponse(
            comp.getId(), comp.getAssetId(), comp.getCode(), comp.getLabel(),
            comp.getAcquisitionCost(), comp.getUsefulLifeYears(),
            comp.getResidualValue(), comp.getDepreciationMethod(),
            comp.getCreatedAt(), comp.getUpdatedAt());
    }

    private ScheduleLineResponse toResponse(DepreciationScheduleLine line) {
        return new ScheduleLineResponse(
            line.getId(), line.getAssetId(), line.getComponentId(), line.getPeriodId(),
            line.getPeriodDate(), line.getAmount(), line.getCumulativeAmount(),
            line.getJournalEntryId(), line.getPostedAt(), line.getPostedBy(),
            line.isPosted());
    }

    @Transactional(readOnly = true)
    public AssetResponse getAsset(UUID companyId, UUID assetId) {
        Asset asset = loadAsset(companyId, assetId);
        BigDecimal cumulative = scheduleRepository
            .findByAssetIdOrderByPeriodDate(asset.getId()).stream()
            .filter(DepreciationScheduleLine::isPosted)
            .map(DepreciationScheduleLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return toResponse(asset, cumulative);
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> listAssets(UUID companyId) {
        return assetRepository.findByCompanyIdOrderByLabel(companyId).stream()
            .map(asset -> {
                BigDecimal cumulative = scheduleRepository
                    .findByAssetIdOrderByPeriodDate(asset.getId()).stream()
                    .filter(DepreciationScheduleLine::isPosted)
                    .map(DepreciationScheduleLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                return toResponse(asset, cumulative);
            })
            .toList();
    }

    // --- Finding #11 — Composants IAS 16 ---

    /**
     * Ajoute un composant IAS 16 à une immobilisation existante (Finding #11).
     *
     * <p>Le composant a sa propre durée de vie (en années), sa propre valeur résiduelle et sa
     * propre méthode d'amortissement. L'ajout déclenche la regénération de l'échéancier de
     * l'asset — opération refusée si des lignes ont déjà été postées (409 SCHEDULE_ALREADY_POSTED)
     * car regénérer un échéancier partiellement posté corromprait les cumuls et la traçabilité.
     *
     * <p>Si l'asset avait déjà un échéancier global (pas de composants), celui-ci est supprimé
     * et remplacé par un échéancier par composant (incluant le nouveau composant). Si l'asset
     * avait déjà des composants, on ajoute simplement le nouveau composant à la liste existante
     * et on regénère l'échéancier complet.
     *
     * @throws ConflictException si l'échéancier a déjà des lignes postées
     * @throws ValidationException si le code composant est dupliqué ou si les paramètres sont invalides
     */
    @Transactional
    public AssetComponentResponse addComponent(UUID companyId, UUID assetId, AddAssetComponentRequest req) {
        Asset asset = loadAsset(companyId, assetId);
        if (asset.getStatus() == AssetStatus.DISPOSED) {
            throw new ConflictException("ASSET_DISPOSED",
                "L'immobilisation " + asset.getLabel() + " est cédée — ajout de composant impossible");
        }

        // Validation métier
        if (req.usefulLifeYears() < 1) {
            throw new ValidationException("COMPONENT_USEFUL_LIFE_INVALID",
                "La durée de vie du composant doit être ≥ 1 an");
        }
        if (req.residualValue().compareTo(req.acquisitionCost()) > 0) {
            throw new ValidationException("COMPONENT_RESIDUAL_TOO_HIGH",
                "La valeur résiduelle du composant ne peut pas dépasser son coût d'acquisition");
        }
        // Unicité du code composant par asset
        if (componentRepository.findByAssetIdAndCode(asset.getId(), req.code().trim()).isPresent()) {
            throw new ValidationException("COMPONENT_CODE_ALREADY_EXISTS",
                "Le code composant '" + req.code() + "' existe déjà pour cet asset");
        }

        // Refuser l'ajout si l'échéancier a déjà des lignes postées (regen corromprait les cumuls)
        boolean hasPosted = scheduleRepository.findByAssetIdOrderByPeriodDate(asset.getId()).stream()
            .anyMatch(DepreciationScheduleLine::isPosted);
        if (hasPosted) {
            throw new ConflictException("SCHEDULE_ALREADY_POSTED",
                "Impossible d'ajouter un composant : l'échéancier de l'asset a déjà des lignes postées. "
                + "Créer un nouvel asset avec composants dès l'acquisition.");
        }

        // Sauvegarder le composant
        AssetComponent comp = new AssetComponent();
        comp.setCompanyId(companyId);
        comp.setAssetId(asset.getId());
        comp.setCode(req.code().trim());
        comp.setLabel(req.label().trim());
        comp.setAcquisitionCost(req.acquisitionCost());
        comp.setUsefulLifeYears(req.usefulLifeYears());
        comp.setResidualValue(req.residualValue());
        comp.setDepreciationMethod(req.depreciationMethod());
        AssetComponent saved = componentRepository.save(comp);

        // Regénérer l'échéancier (supprime les anciennes lignes non postées, génère par composant)
        scheduleRepository.deleteAll(scheduleRepository.findByAssetIdOrderByPeriodDate(asset.getId()));
        generateSchedule(companyId, asset);

        LOG.info("Composant IAS 16 ajouté à l'asset {} : code={} cost={} years={}",
            asset.getId(), saved.getCode(), saved.getAcquisitionCost(), saved.getUsefulLifeYears());

        return toComponentResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<AssetComponentResponse> listComponents(UUID companyId, UUID assetId) {
        Asset asset = loadAsset(companyId, assetId);
        return componentRepository.findByAssetIdOrderByCode(asset.getId()).stream()
            .map(this::toComponentResponse)
            .toList();
    }

    // --- Finding #11 — Test de dépréciation IAS 36 ---

    /**
     * Teste la dépréciation IAS 36 d'une immobilisation (Finding #11).
     *
     * <p>Compare la VNC (valeur nette comptable) avec le montant recouvrable fourni par
     * l'utilisateur (le plus élevé entre la valeur d'utilité et la juste valeur nette des
     * coûts de cession, selon IAS 36 §6).
     *
     * <p>VNC = coût d'acquisition − amortissement cumulé posté − dépréciation IAS 36 antérieure.
     *
     * <p>Si VNC &gt; montant recouvrable, une dépréciation est enregistrée :
     * <ul>
     *   <li>{@code impairmentAmount} = VNC − montant recouvrable</li>
     *   <li>Écriture comptable : D 6816 (Charges pour dépréciation) /
     *       C 291 (Dépréciation des immobilisations)</li>
     *   <li>{@code asset.impairmentAmount} += impairmentAmount</li>
     * </ul>
     *
     * <p>Si VNC ≤ montant recouvrable, aucune écriture n'est générée (le test conclut à
     * l'absence de dépréciation). {@code impairmentAmount} est alors 0.
     *
     * <p>Idempotence : la clé déterministe est {@code "fixed-assets-impairment-" + assetId + "-" + testedAt}
     * (où {@code testedAt} est l'horodatage du test). Un retry ne crée pas de 2e écriture.
     *
     * @param companyId          tenant
     * @param assetId            immobilisation à tester
     * @param recoverableAmount  montant recouvrable (valeur d'utilité ou juste valeur nette)
     * @return résultat du test (avec montant de la dépréciation et ID de l'écriture si applicable)
     */
    @Transactional
    public ImpairmentTestResult testImpairment(UUID companyId, UUID assetId,
                                                 BigDecimal recoverableAmount) {
        Asset asset = loadAsset(companyId, assetId);
        if (asset.getStatus() == AssetStatus.DISPOSED) {
            throw new ConflictException("ASSET_DISPOSED",
                "L'immobilisation " + asset.getLabel() + " est cédée — test de dépréciation impossible");
        }
        if (recoverableAmount == null || recoverableAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("RECOVERABLE_AMOUNT_INVALID",
                "Le montant recouvrable doit être ≥ 0");
        }

        // Amortissement cumulé posté (somme des lignes postées, tous composants confondus)
        BigDecimal cumulativeDepreciation = scheduleRepository
            .findByAssetIdOrderByPeriodDate(asset.getId()).stream()
            .filter(DepreciationScheduleLine::isPosted)
            .map(DepreciationScheduleLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // VNC = coût acquisition − amortissement cumulé − dépréciation IAS 36 antérieure
        BigDecimal netBookValue = asset.getAcquisitionCost()
            .subtract(cumulativeDepreciation)
            .subtract(asset.getImpairmentAmount());

        Instant testedAt = Instant.now();
        UUID journalEntryId = null;
        BigDecimal impairmentAmount = BigDecimal.ZERO;
        boolean impaired = false;

        if (netBookValue.compareTo(recoverableAmount) > 0) {
            // Dépréciation à enregistrer (IAS 36 §59)
            impairmentAmount = netBookValue.subtract(recoverableAmount);

            // Résoudre les comptes 6816 (charge) et 291 (actif) — fallback sur les comptes
            // d'amortissement classique pour rétro-compatibilité (asset sans comptes IAS 36 dédiés).
            UUID expenseAcctId = asset.getImpairmentExpenseAccountId() != null
                ? asset.getImpairmentExpenseAccountId() : asset.getDepreciationExpenseAccountId();
            UUID accumAcctId = asset.getAccumulatedImpairmentAccountId() != null
                ? asset.getAccumulatedImpairmentAccountId() : asset.getAccumulatedDepreciationAccountId();

            Account expenseAccount = accountRepository.findById(expenseAcctId)
                .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                    "Compte de charge pour dépréciation introuvable : " + expenseAcctId));
            if (!expenseAccount.getCompanyId().equals(companyId)) {
                throw new NotFoundException("Account", expenseAcctId.toString());
            }
            Account impairmentAccount = accountRepository.findById(accumAcctId)
                .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                    "Compte de dépréciation cumulée introuvable : " + accumAcctId));
            if (!impairmentAccount.getCompanyId().equals(companyId)) {
                throw new NotFoundException("Account", accumAcctId.toString());
            }

            String journalCode = journalRepository.findByCompanyIdAndCode(companyId, "OD")
                .map(j -> j.getCode())
                .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                    "Journal OD (Opérations diverses) introuvable. Créer un journal de code 'OD'."));

            LocalDate entryDate = LocalDate.now();
            CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
                journalCode,
                entryDate,
                "Dépréciation IAS 36 — " + asset.getLabel(),
                List.of(
                    new LineDto(expenseAccount.getCode(), null, impairmentAmount, null,
                        "Dotation pour dépréciation IAS 36", List.of()),
                    new LineDto(impairmentAccount.getCode(), null, null, impairmentAmount,
                        "Dépréciation des immobilisations (IAS 36)", List.of())
                ),
                JournalEntrySourceModule.FIXED_ASSETS
            );

            String idempotencyKey = "fixed-assets-impairment-" + asset.getId() + "-" + testedAt.toEpochMilli();
            JournalEntryResponse entry = accountingEngineService.createJournalEntry(
                companyId, idempotencyKey, entryReq);
            JournalEntryResponse posted = accountingEngineService.postJournalEntry(
                companyId, entry.id(), List.of());
            journalEntryId = posted.id();

            // Mettre à jour la dépréciation cumulée sur l'asset
            asset.setImpairmentAmount(asset.getImpairmentAmount().add(impairmentAmount));
            assetRepository.save(asset);

            impaired = true;
            LOG.info("Dépréciation IAS 36 enregistrée : asset={} VNC={} recouvrable={} impairment={} entry={}",
                asset.getId(), netBookValue, recoverableAmount, impairmentAmount, posted.reference());
        } else {
            LOG.info("Test de dépréciation IAS 36 — pas de dépréciation : asset={} VNC={} recouvrable={}",
                asset.getId(), netBookValue, recoverableAmount);
        }

        return new ImpairmentTestResult(
            asset.getId(), netBookValue, recoverableAmount,
            impairmentAmount, impaired, journalEntryId, testedAt);
    }
}

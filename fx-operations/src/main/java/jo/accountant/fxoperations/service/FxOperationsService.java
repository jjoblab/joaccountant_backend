package jo.accountant.fxoperations.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.currency.ExchangeRateService;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.fxoperations.dto.CreateFxOperationRequest;
import jo.accountant.fxoperations.dto.FxOperationResponse;
import jo.accountant.fxoperations.entity.FxOperation;
import jo.accountant.fxoperations.entity.FxOperationStatus;
import jo.accountant.fxoperations.entity.FxOperationType;
import jo.accountant.fxoperations.repository.FxOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des opérations en devises étrangères (restructuration 2026-07-24 suite 3).
 *
 * <p>Trois types d'opérations :
 * <ul>
 *   <li><b>BUY</b> — achat de devise étrangère (ex. HTG → USD). L'utilisateur vend
 *       {@code fromAmount} de {@code fromCurrency} pour acheter {@code toAmount} de
 *       {@code toCurrency} au taux {@code rate}.</li>
 *   <li><b>SELL</b> — vente de devise étrangère (ex. USD → HTG). Symétrique de BUY.</li>
 *   <li><b>REVALUATION</b> — réévaluation de fin de période. Le solde converti au taux
 *       de clôture est comparé au solde converti au taux historique ; la différence est
 *       un gain/perte de change latent.</li>
 * </ul>
 *
 * <p>Pour chaque opération, le service :
 * <ol>
 *   <li>Calcule les montants en devise fonctionnelle via {@link ExchangeRateService#convert}.</li>
 *   <li>Calcule le gain/perte de change : {@code toAmountFunctional - fromAmountFunctional}.</li>
 *   <li>Génère l'écriture comptable correspondante via {@link AccountingEngineService}.</li>
 * </ol>
 *
 * <p><b>Résolution des comptes</b> :
 * <ul>
 *   <li>Compte de trésorerie : si {@code bankAccountId} fourni, on l'utilise. Sinon,
 *       recherche d'un compte {@code ACTIF + taxMappingCode="CASH"} (fallback SYSCOHADA
 *       "521" / "521000").</li>
 *   <li>Compte de gain de change (si gain > 0) : {@code PRODUITS + taxMappingCode="FX_GAIN"}
 *       (fallback "776").</li>
 *   <li>Compte de perte de change (si perte > 0) : {@code CHARGES + taxMappingCode="FX_LOSS"}
 *       (fallback "676").</li>
 * </ul>
 *
 * <p><b>Code journal</b> : "OD" (opérations diverses) par défaut. Pas de journal dédié
 * "FX" au MVP — l'utilisateur peut le créer si nécessaire et les écritures de change
 * apparaîtront toujours dans OD (lisible côté mobile via le filtre {@code sourceModule=FX}).
 */
@Service
public class FxOperationsService {

    private static final Logger LOG = LoggerFactory.getLogger(FxOperationsService.class);
    /**
     * Devise fonctionnelle par défaut si {@link Company#getFunctionalCurrency()} est null ou vide.
     * Conservé pour backward-compat (entreprises créées avant l'ajout du champ functionalCurrency).
     *
     * <p><b>Audit v4.7 §3.1 Finding #2 — FIX CRITIQUE</b> : la version originale utilisait
     * <code>FUNCTIONAL_CURRENCY_DEFAULT = "HTG"</code> pour TOUTES les entreprises, ignorant
     * complètement {@code Company.functionalCurrency}. Pour une entreprise PCGR_CANADA (CAD),
     * tous les montants FX étaient convertis en HTG au lieu de CAD. Désormais, on lit la vraie
     * devise fonctionnelle de la company, avec HTG comme fallback uniquement si non configurée.
     */
    private static final String FUNCTIONAL_CURRENCY_FALLBACK = "HTG";

    private final FxOperationRepository operationRepository;
    private final AccountRepository accountRepository;
    private final JournalRepository journalRepository;
    private final AccountingEngineService accountingEngineService;
    private final ExchangeRateService exchangeRateService;
    private final CompanyRepository companyRepository;
    // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
    private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;

    public FxOperationsService(FxOperationRepository operationRepository,
                                AccountRepository accountRepository,
                                JournalRepository journalRepository,
                                AccountingEngineService accountingEngineService,
                                ExchangeRateService exchangeRateService,
                                CompanyRepository companyRepository,
                                jo.accountant.chartofaccounts.service.AccountResolver accountResolver) {
        this.operationRepository = operationRepository;
        this.accountRepository = accountRepository;
        this.journalRepository = journalRepository;
        this.accountingEngineService = accountingEngineService;
        this.exchangeRateService = exchangeRateService;
        this.companyRepository = companyRepository;
        this.accountResolver = accountResolver;
    }

    /**
     * Résout la devise fonctionnelle réelle de l'entreprise.
     *
     * <p>Audit v4.7 §3.1 fix : lit {@link Company#getFunctionalCurrency()} au lieu de hardcoder
     * HTG. Fallback vers HTG uniquement si l'entreprise n'a pas configuré sa devise fonctionnelle
     * (backward-compat pour les entreprises créées avant l'ajout du champ).
     */
    private String resolveFunctionalCurrency(UUID companyId) {
        return companyRepository.findById(companyId)
            .map(Company::getFunctionalCurrency)
            .filter(c -> c != null && !c.isBlank())
            .orElse(FUNCTIONAL_CURRENCY_FALLBACK);
    }

    /**
     * Crée et poste une opération de change.
     *
     * <p>Étapes :
     * <ol>
     *   <li>Valider les paramètres (devises distinctes, montants > 0, taux > 0).</li>
     *   <li>Convertir fromAmount et toAmount en devise fonctionnelle via le taux
     *       applicable à la date d'opération.</li>
     *   <li>Calculer le gain/perte de change.</li>
     *   <li>Générer l'écriture comptable (D/C Banque + D/C 776 ou 676 selon le sens).</li>
     * </ol>
     */
    @Transactional
    public FxOperationResponse create(UUID companyId, CreateFxOperationRequest req) {
        validateRequest(req);

        // Audit v4.7 §3.1 fix — lire la vraie devise fonctionnelle de l'entreprise au lieu de
        // hardcoder HTG. Pour une entreprise PCGR_CANADA (CAD), tous les montants FX doivent être
        // convertis en CAD, pas en HTG.
        String functionalCurrency = resolveFunctionalCurrency(companyId);

        // Convertir fromAmount et toAmount en devise fonctionnelle
        BigDecimal fromAmountFunctional = exchangeRateService.convert(
            companyId, req.fromAmount(), req.fromCurrency(), functionalCurrency, req.operationDate());
        BigDecimal toAmountFunctional = exchangeRateService.convert(
            companyId, req.toAmount(), req.toCurrency(), functionalCurrency, req.operationDate());

        // Calculer le gain/perte de change
        // BUY : on vend fromCurrency (sortie) pour acheter toCurrency (entrée).
        //       Gain = toAmountFunctional - fromAmountFunctional (si > 0, on a reçu plus en équivalent).
        // SELL : symétrique.
        // REVALUATION : on fournit directement le solde et le taux de clôture ; le gain/perte
        //       est directement la différence entre toAmountFunctional et fromAmountFunctional.
        BigDecimal fxGainLoss;
        if (req.type() == FxOperationType.BUY) {
            fxGainLoss = toAmountFunctional.subtract(fromAmountFunctional);
        } else {
            // SELL et REVALUATION : le gain est fromAmountFunctional - toAmountFunctional
            // (on vend une devise qui vaut plus en équivalent fonctionnel)
            fxGainLoss = fromAmountFunctional.subtract(toAmountFunctional);
        }

        // Créer l'entité
        FxOperation op = new FxOperation();
        op.setCompanyId(companyId);
        op.setType(req.type());
        op.setFromCurrency(req.fromCurrency().toUpperCase());
        op.setToCurrency(req.toCurrency().toUpperCase());
        op.setFromAmount(req.fromAmount());
        op.setToAmount(req.toAmount());
        op.setRate(req.rate());
        op.setFromAmountFunctional(fromAmountFunctional);
        op.setToAmountFunctional(toAmountFunctional);
        op.setFxGainLoss(fxGainLoss);
        op.setOperationDate(req.operationDate());
        op.setDescription(req.description());
        op.setStatus(FxOperationStatus.POSTED);
        FxOperation saved = operationRepository.save(op);

        // Générer l'écriture comptable
        generateFxEntry(companyId, saved, req.bankAccountId());
        operationRepository.save(saved);

        LOG.info("Opération FX créée : id={} type={} {}/{} gainLoss={}",
            saved.getId(), saved.getType(), saved.getFromCurrency(), saved.getToCurrency(),
            saved.getFxGainLoss());
        return toResponse(saved);
    }

    /**
     * Génère l'écriture comptable d'une opération FX.
     *
     * <p><b>Finding #13 — Comptabilisation FX (audit batch 1)</b> : les gains/pertes de change
     * sont désormais comptabilisés sur un compte 776 "Gains de change" (PRODUITS) ou 676
     * "Pertes de change" (CHARGES) <b>distinct</b> du compte bancaire, et non plus comme un
     * simple ajustement résiduel sur le même compte 521. Les comptes 776/676 sont résolus via
     * {@link jo.accountant.chartofaccounts.service.AccountResolver#resolveOrThrow} avec les
     * taxMappingCode {@code "FX_GAIN"} / {@code "FX_LOSS"} (fallbacks SYSCOHADA 776/776000
     * et 676/676000).
     *
     * <p>Convention comptable (SYSCOHADA) :
     * <ul>
     *   <li>BUY : D 521 (toAmountFunctional, devise cible entrante) /
     *       C 521 (fromAmountFunctional, devise source sortante) /
     *       C 776 (gain) ou D 676 (perte) pour le solde.</li>
     *   <li>SELL : D 521 (fromAmountFunctional) /
     *       C 521 (toAmountFunctional) /
     *       C 776 (gain) ou D 676 (perte) pour le solde.</li>
     *   <li>REVALUATION : D 521 / C 776 (si gain latent) OU D 676 / C 521 (si perte latente).</li>
     * </ul>
     *
     * <p>Pour rester simple au MVP, le compte 521 est le même pour toutes les devises —
     * l'utilisateur qui veut tracer séparément les soldes par devise créera des sous-comptes
     * 521-USD, 521-EUR, etc. (convention SYSCOHADA recommandée, mais non imposée ici).
     */
    private void generateFxEntry(UUID companyId, FxOperation op, UUID bankAccountIdOverride) {
        Account bankAccount = resolveBankAccount(companyId, bankAccountIdOverride);
        String fxLineLabel = "FX " + op.getType() + " — " + op.getDescription();

        List<LineDto> lines = new ArrayList<>();

        if (op.getType() == FxOperationType.REVALUATION) {
            // REVALUATION — un seul montant (la différence entre solde historique et solde clôture).
            // Le gain/perte est latent et va directement sur 776/676 (pas de contrepartie banque
            // bilatérale — l'opération ne fait que constater la réévaluation).
            BigDecimal diff = op.getToAmountFunctional().subtract(op.getFromAmountFunctional());
            if (diff.compareTo(BigDecimal.ZERO) >= 0) {
                // Gain latent : D 521 / C 776
                Account gainAccount = resolveFxAccount(companyId, true);
                lines.add(new LineDto(bankAccount.getCode(), null, diff, null, fxLineLabel, List.of()));
                lines.add(new LineDto(gainAccount.getCode(), null, null, diff,
                    "Gain de change latent — " + op.getDescription(), List.of()));
            } else {
                // Perte latente : D 676 / C 521
                Account lossAccount = resolveFxAccount(companyId, false);
                BigDecimal lossAmount = diff.negate();
                lines.add(new LineDto(lossAccount.getCode(), null, lossAmount, null,
                    "Perte de change latente — " + op.getDescription(), List.of()));
                lines.add(new LineDto(bankAccount.getCode(), null, null, lossAmount, fxLineLabel, List.of()));
            }
        } else {
            // BUY / SELL — deux flux de trésorerie sur le compte 521 + 1 ligne de gain/perte
            // sur 776/676 pour équilibrer. Le compte 776/676 est OBLIGATOIRE dès que le gain/perte
            // est non nul (Finding #13) — il ne faut jamais lettrer le gain/perte sur le 521.
            BigDecimal bankDebit, bankCredit;
            if (op.getType() == FxOperationType.BUY) {
                // D 521 (toAmountFunctional) / C 521 (fromAmountFunctional)
                bankDebit = op.getToAmountFunctional();
                bankCredit = op.getFromAmountFunctional();
            } else {
                // SELL : D 521 (fromAmountFunctional) / C 521 (toAmountFunctional)
                bankDebit = op.getFromAmountFunctional();
                bankCredit = op.getToAmountFunctional();
            }
            lines.add(new LineDto(bankAccount.getCode(), null, bankDebit, null, fxLineLabel, List.of()));
            lines.add(new LineDto(bankAccount.getCode(), null, null, bankCredit, fxLineLabel, List.of()));

            // Ligne de gain/perte sur un compte DISTINCT (776/676) — Finding #13.
            BigDecimal diff = op.getFxGainLoss();
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                // Gain : crédit 776 pour équilibrer (débit banque > crédit banque)
                Account gainAccount = resolveFxAccount(companyId, true);
                lines.add(new LineDto(gainAccount.getCode(), null, null, diff,
                    "Gain de change — " + op.getDescription(), List.of()));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                // Perte : débit 676 pour équilibrer (crédit banque > débit banque)
                Account lossAccount = resolveFxAccount(companyId, false);
                lines.add(new LineDto(lossAccount.getCode(), null, diff.negate(), null,
                    "Perte de change — " + op.getDescription(), List.of()));
            }
        }

        // V8.2 Phase 3 — getOrCreateJournal retourne le journal existant ou le crée avec
        // le code/label par défaut du type (jamais d'exception pour les types standards).
        String journalCode = accountingEngineService.getOrCreateJournal(companyId,
            jo.accountant.accountingengine.entity.JournalType.OD).getCode();

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, op.getOperationDate(),
            "Opération de change " + op.getType() + " — " + op.getFromCurrency() + "/" + op.getToCurrency(),
            lines, JournalEntrySourceModule.MANUAL);

        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, "fx-operation-" + op.getId(), entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        op.setJournalEntryId(posted.id());
    }

    /**
     * Résout le compte de trésorerie (banque) pour l'opération.
     * Si bankAccountIdOverride est fourni, on l'utilise (et on valide qu'il appartient à
     * l'entreprise et est de classe ACTIF). Sinon, on cherche un compte ACTIF marqué
     * taxMappingCode="CASH" (fallback SYSCOHADA "521" / "521000").
     */
    private Account resolveBankAccount(UUID companyId, UUID bankAccountIdOverride) {
        if (bankAccountIdOverride != null) {
            Account acc = accountRepository.findById(bankAccountIdOverride)
                .orElseThrow(() -> new NotFoundException("Account", bankAccountIdOverride));
            if (!acc.getCompanyId().equals(companyId)) {
                throw new NotFoundException("Account", bankAccountIdOverride);
            }
            if (acc.getReportingClass() != ReportingClass.ACTIF) {
                throw new ValidationException("ACCOUNT_NOT_CASH",
                    "Le compte " + acc.getCode() + " n'est pas un compte d'ACTIF (trésorerie attendue).");
            }
            return acc;
        }
        // Compte de trésorerie — résolution référentiel-agnostique via AccountResolver (audit #3)
        return accountResolver.resolveOrThrow(
            companyId, ReportingClass.ACTIF, "CASH",
            "CASH_ACCOUNT_NOT_FOUND",
            "Aucun compte de trésorerie trouvé. Configurer un compte ACTIF marqué " +
            "taxMappingCode=\"CASH\" (ou nommer un compte 521/521000).",
            "521", "521000");
    }

    /**
     * Résout le compte de gain (PRODUITS + FX_GAIN, fallback 776) ou de perte de change
     * (CHARGES + FX_LOSS, fallback 676).
     *
     * @param isGain true pour gain (PRODUITS), false pour perte (CHARGES)
     */
    private Account resolveFxAccount(UUID companyId, boolean isGain) {
        ReportingClass rclass = isGain ? ReportingClass.PRODUITS : ReportingClass.CHARGES;
        String taxMapping = isGain ? "FX_GAIN" : "FX_LOSS";
        String fallback1 = isGain ? "776" : "676";
        String fallback2 = isGain ? "776000" : "676000";

        // Compte de gain/perte de change — résolution référentiel-agnostique via AccountResolver (audit #3)
        return accountResolver.resolveOrThrow(
            companyId, rclass, taxMapping,
            isGain ? "FX_GAIN_ACCOUNT_NOT_FOUND" : "FX_LOSS_ACCOUNT_NOT_FOUND",
            "Aucun compte de " + (isGain ? "gain" : "perte") + " de change trouvé. " +
            "Configurer un compte " + rclass + " marqué taxMappingCode=\"" + taxMapping +
            "\" (ou nommer un compte " + fallback1 + "/" + fallback2 + ").",
            fallback1, fallback2);
    }

    private void validateRequest(CreateFxOperationRequest req) {
        if (req.fromCurrency().equalsIgnoreCase(req.toCurrency())) {
            throw new ValidationException("SAME_CURRENCY",
                "fromCurrency et toCurrency doivent être distincts.");
        }
        if (req.fromAmount() == null || req.fromAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("FROM_AMOUNT_INVALID", "fromAmount doit être > 0");
        }
        if (req.toAmount() == null || req.toAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("TO_AMOUNT_INVALID", "toAmount doit être > 0");
        }
        if (req.rate() == null || req.rate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("RATE_INVALID", "rate doit être > 0");
        }
        // Vérifier la cohérence : toAmount ≈ fromAmount × rate
        BigDecimal expectedTo = req.fromAmount().multiply(req.rate())
            .setScale(4, RoundingMode.HALF_UP);
        if (req.toAmount().subtract(expectedTo).abs().compareTo(new BigDecimal("0.01")) > 0) {
            // Tolérance de 0.01 (arrondis) — sinon erreur
            throw new ValidationException("INCONSISTENT_RATE",
                "Incohérence : toAmount (" + req.toAmount() + ") ≠ fromAmount × rate (" + expectedTo + "). " +
                "Vérifier les valeurs fournies.");
        }
    }

    @Transactional(readOnly = true)
    public List<FxOperationResponse> list(UUID companyId) {
        return operationRepository.findByCompanyIdOrderByOperationDateDesc(companyId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public FxOperationResponse get(UUID companyId, UUID operationId) {
        FxOperation op = operationRepository.findById(operationId)
            .orElseThrow(() -> new NotFoundException("FxOperation", operationId));
        if (!op.getCompanyId().equals(companyId)) {
            throw new NotFoundException("FxOperation", operationId);
        }
        return toResponse(op);
    }

    private FxOperationResponse toResponse(FxOperation op) {
        return new FxOperationResponse(
            op.getId(), op.getCompanyId(), op.getType(),
            op.getFromCurrency(), op.getToCurrency(),
            op.getFromAmount(), op.getToAmount(), op.getRate(),
            op.getFromAmountFunctional(), op.getToAmountFunctional(),
            op.getFxGainLoss(), op.getOperationDate(), op.getDescription(),
            op.getJournalEntryId(), op.getReversalOfId(), op.getStatus(),
            op.getCreatedAt(), op.getUpdatedAt());
    }
}

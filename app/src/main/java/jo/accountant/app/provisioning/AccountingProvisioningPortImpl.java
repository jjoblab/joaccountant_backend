package jo.accountant.app.provisioning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.entity.Company;
import jo.accountant.company.port.AccountingProvisioningPort;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.tax.VatMode;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.tax.dto.CreateTaxRuleRequest;
import jo.accountant.tax.entity.TaxRule;
import jo.accountant.tax.service.TaxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.2 (audit Z.ai 2026-07-31) — Implémentation de {@link AccountingProvisioningPort}.
 *
 * <p>Définie dans {@code :app} (et non dans {@code :company}) car elle doit dépendre de
 * {@code :chart-of-accounts}, {@code :accounting-engine}, {@code :document-numbering} et
 * {@code :tax} — or de nombreux modules dépendent déjà de {@code :company}, ce qui créerait
 * des dépendances circulaires si {@code :company} dépendait de ces modules.
 *
 * <p>Inversion de dépendance : {@code :company} définit l'interface
 * {@link AccountingProvisioningPort}, {@code :app} fournit cette implémentation concrète.
 *
 * <p>Initialise en une seule transaction :
 * <ol>
 *   <li>Plan comptable (via {@link ChartOfAccountsService#initialize})</li>
 *   <li>Exercice fiscal + 12 périodes mensuelles (via {@link AccountingEngineService#createFiscalYear})</li>
 *   <li>Journaux standards VT/AC/BQ/CA/OD/PA/DP/FX (via {@link AccountingEngineService#createJournal})</li>
 *   <li>Séquences de numérotation par défaut (via {@link DocumentNumberingService#createSequence})</li>
 *   <li>Règles TVA par défaut si pays non couvert par les seeds globaux (via {@link TaxService#createTaxRule})</li>
 * </ol>
 *
 * <p><b>Idempotence</b> : chaque sous-étape catch {@link ConflictException} (et reste silencieux
 * si l'objet existe déjà). {@code provision} peut donc être rappelée sans créer de doublons.
 */
@Service
public class AccountingProvisioningPortImpl implements AccountingProvisioningPort {

    private static final Logger LOG = LoggerFactory.getLogger(AccountingProvisioningPortImpl.class);

    /**
     * Journaux standards créés pour toute nouvelle société.
     * Format : {code, label}. Le code fait office de convention de type (VT=Ventes, etc.).
     */
    private static final String[][] DEFAULT_JOURNALS = {
        {"VT", "Journal des ventes"},
        {"AC", "Journal des achats"},
        {"BQ", "Journal de banque"},
        {"CA", "Journal de caisse"},
        {"OD", "Opérations diverses"},
        {"PA", "Journal de paie"},
        {"DP", "Journal des dépenses"},
        {"FX", "Journal des opérations de change"}
    };

    private final ChartOfAccountsService chartOfAccountsService;
    private final AccountingEngineService accountingEngineService;
    private final DocumentNumberingService documentNumberingService;
    private final TaxService taxService;

    public AccountingProvisioningPortImpl(ChartOfAccountsService chartOfAccountsService,
                                          AccountingEngineService accountingEngineService,
                                          DocumentNumberingService documentNumberingService,
                                          TaxService taxService) {
        this.chartOfAccountsService = chartOfAccountsService;
        this.accountingEngineService = accountingEngineService;
        this.documentNumberingService = documentNumberingService;
        this.taxService = taxService;
    }

    @Override
    @Transactional
    public ProvisioningResult provision(Company company, String vatMode, int fiscalYearStartYear,
                                         String fiscalYearLabel, Map<String, String> numberingPrefixes) {
        UUID companyId = company.getId();
        LOG.info("Provisioning start for company {} (businessType={}, framework={}, vatMode={})",
            companyId, company.getBusinessTypeCode(), company.getAccountingFrameworkId(), vatMode);

        // 1. Plan comptable (idempotent — 409 ConflictException si déjà initialisé)
        int coaCreated = safeInitChartOfAccounts(company);

        // 2. Exercice fiscal (12 périodes mensuelles auto-générées)
        UUID fiscalYearId = createDefaultFiscalYear(companyId, company.getFiscalYearStartMonth(),
            fiscalYearStartYear, fiscalYearLabel);

        // 3. Journaux standards (idempotent)
        List<String> journalCodes = createDefaultJournals(companyId);

        // 4. Séquences de numérotation par défaut
        int sequencesCreated = createDefaultSequences(companyId, numberingPrefixes,
            fiscalYearStartYear);

        // 5. Règles TVA par défaut (0 si seeds globaux suffisent, ex: Haïti)
        int taxRulesCreated = createDefaultTaxRules(company, vatMode);

        LOG.info("Provisioning done for company {} : coa={} journals={} sequences={} taxRules={}",
            companyId, coaCreated, journalCodes.size(), sequencesCreated, taxRulesCreated);

        return new ProvisioningResult(coaCreated, fiscalYearId, journalCodes,
            sequencesCreated, taxRulesCreated);
    }

    // ── 1. Plan comptable ──────────────────────────────────────────────────────

    private int safeInitChartOfAccounts(Company company) {
        try {
            ChartOfAccountsService.InitializeResult result = chartOfAccountsService.initialize(
                company.getId(),
                company.getAccountingFrameworkId(),
                null,  // template null — requis seulement pour IFRS (FREE numbering)
                company.getBusinessTypeCode());
            LOG.info("Chart of accounts initialized: {} accounts created", result.accountsCreated());
            return result.accountsCreated();
        } catch (ConflictException ex) {
            // CHART_OF_ACCOUNTS_ALREADY_INITIALIZED — idempotent, no-op
            LOG.info("Chart of accounts already initialized for company {} — skipping", company.getId());
            return 0;
        }
    }

    // ── 2. Exercice fiscal ──────────────────────────────────────────────────────

    private UUID createDefaultFiscalYear(UUID companyId, int fiscalYearStartMonth,
                                          int fiscalYearStartYear, String fiscalYearLabel) {
        int year = fiscalYearStartYear != 0 ? fiscalYearStartYear : LocalDate.now().getYear();
        int month = fiscalYearStartMonth != 0 ? fiscalYearStartMonth : 1;
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusYears(1).minusDays(1);
        String label = fiscalYearLabel != null && !fiscalYearLabel.isBlank()
            ? fiscalYearLabel
            : "Exercice " + year + "-" + endDate.getYear();

        CreateFiscalYearRequest req = new CreateFiscalYearRequest(startDate, endDate, label);
        FiscalYear fy = accountingEngineService.createFiscalYear(companyId, req);
        LOG.info("Fiscal year created: id={} {} ({} → {})", fy.getId(), label, startDate, endDate);
        return fy.getId();
    }

    // ── 3. Journaux standards ───────────────────────────────────────────────────

    private List<String> createDefaultJournals(UUID companyId) {
        List<String> codes = new ArrayList<>();
        for (String[] jl : DEFAULT_JOURNALS) {
            String code = jl[0];
            String label = jl[1];
            try {
                accountingEngineService.createJournal(companyId, code, label);
            } catch (ConflictException ex) {
                // JOURNAL_CODE_ALREADY_EXISTS — idempotent, no-op
                LOG.debug("Journal {} already exists for company {} — skipping", code, companyId);
            }
            codes.add(code);
        }
        return codes;
    }

    // ── 4. Séquences de numérotation ────────────────────────────────────────────

    private int createDefaultSequences(UUID companyId, Map<String, String> prefixes,
                                        int fiscalYearStartYear) {
        int year = fiscalYearStartYear != 0 ? fiscalYearStartYear : LocalDate.now().getYear();
        int count = 0;

        // V8.2 — JOURNAL_ENTRY : créer une séquence par code journal (VT, AC, BQ, CA, OD, PA, DP, FX)
        // car le postage cherche DocumentNumberingService.nextNumber(companyId, JOURNAL_ENTRY, journalCode)
        // où journalCode = "VT", "AC", etc. (pas "")
        String ecrPrefix = getPrefix(prefixes, "JOURNAL_ENTRY", "ECR");
        for (String[] jl : DEFAULT_JOURNALS) {
            String journalCode = jl[0];
            count += safeCreateSequence(companyId, DocumentType.JOURNAL_ENTRY, journalCode,
                ecrPrefix + "-" + journalCode + "-" + year + "-", year);
        }

        count += safeCreateSequence(companyId, DocumentType.SALES_INVOICE, "",
            getPrefix(prefixes, "SALES_INVOICE", "INV"), year);
        count += safeCreateSequence(companyId, DocumentType.PURCHASE_INVOICE, "",
            getPrefix(prefixes, "PURCHASE_INVOICE", "ACH"), year);
        count += safeCreateSequence(companyId, DocumentType.PAYSLIP, "",
            getPrefix(prefixes, "PAYSLIP", "PAY"), year);
        count += safeCreateSequence(companyId, DocumentType.CREDIT_NOTE, "",
            getPrefix(prefixes, "CREDIT_NOTE", "AVO"), year);
        count += safeCreateSequence(companyId, DocumentType.DONATION_RECEIPT, "",
            getPrefix(prefixes, "DONATION_RECEIPT", "DON"), year);

        return count;
    }

    private String getPrefix(Map<String, String> prefixes, String key, String defaultPrefix) {
        if (prefixes == null) return defaultPrefix + "-";
        String p = prefixes.get(key);
        return (p != null && !p.isBlank()) ? p : defaultPrefix + "-";
    }

    private int safeCreateSequence(UUID companyId, DocumentType docType, String scopeKey,
                                    String prefix, int year) {
        try {
            documentNumberingService.createSequence(
                companyId, docType, scopeKey, prefix + year + "-",
                true,  // includeYear
                6,     // padding (6 digits)
                ResetPolicy.YEARLY);
            return 1;
        } catch (ConflictException ex) {
            // SEQUENCE_CONFIG_ALREADY_EXISTS — idempotent
            LOG.debug("Sequence {} already exists for company {} — skipping", docType, companyId);
            return 0;
        }
    }

    // ── 5. Règles TVA par défaut ────────────────────────────────────────────────

    private int createDefaultTaxRules(Company company, String vatModeStr) {
        // Seeds globaux V66 couvrent déjà Haïti (TVA_HT_10, TCA_HT_2/5/10) — pas besoin
        // de créer des règles pour HT. Pour la France, créer une TVA standard 20% sans
        // comptes (ils seront résolus au premier usage). Pour les autres pays, no-op.
        if (!"FR".equalsIgnoreCase(company.getCountry())) {
            LOG.info("No default tax rules created for country {} — relying on global seeds if any",
                company.getCountry());
            return 0;
        }

        VatMode vatMode;
        try {
            vatMode = VatMode.valueOf(vatModeStr != null ? vatModeStr : "DEBIT");
        } catch (IllegalArgumentException e) {
            vatMode = VatMode.DEBIT;
        }

        int count = 0;
        try {
            // TVA standard 20% (France)
            CreateTaxRuleRequest req = new CreateTaxRuleRequest(
                "TVA_FR_20",
                "TVA standard 20% (France)",
                new java.math.BigDecimal("20.00"),
                null,  // payableAccountId — résolu au premier usage
                null,  // receivableAccountId
                LocalDate.now(),
                null,  // applicableTo (pas de fin)
                vatMode);
            TaxRule rule = taxService.createTaxRule(company.getId(), req);
            count++;
            LOG.info("Tax rule created: code={} rate={}%", rule.getCode(), rule.getRate());
        } catch (ConflictException ex) {
            LOG.debug("Tax rule TVA_FR_20 already exists for company {} — skipping", company.getId());
        }
        return count;
    }
}

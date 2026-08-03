package jo.accountant.app.provisioning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.chartofaccounts.service.AccountResolver;
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
 * Implémentation de {@link AccountingProvisioningPort}.
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
 * <li>Plan comptable (via {@link ChartOfAccountsService#initialize})</li>
 * <li>Exercice fiscal + 12 périodes mensuelles (via {@link AccountingEngineService#createFiscalYear})</li>
 * <li>Journaux standards VT/AC/BQ/CA/OD/PA/DP/FX (via {@link AccountingEngineService#createJournal})</li>
 * <li>Séquences de numérotation par défaut (via {@link DocumentNumberingService#createSequence})</li>
 * <li>Règles TVA par défaut si pays non couvert par les seeds globaux (via {@link TaxService#createTaxRule})</li>
 * </ol>
 *
 * <p><b>Idempotence</b> : chaque sous-{@link ConflictException} (et reste silencieux
 * si l'objet existe déjà). {@code provision} peut donc être rappelée sans créer de doublons.
 
 *
 * @author jo@Dev


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
    private final AccountResolver accountResolver;

    public AccountingProvisioningPortImpl(ChartOfAccountsService chartOfAccountsService,
                                          AccountingEngineService accountingEngineService,
                                          DocumentNumberingService documentNumberingService,
                                          TaxService taxService,
                                          AccountResolver accountResolver) {
        this.chartOfAccountsService = chartOfAccountsService;
        this.accountingEngineService = accountingEngineService;
        this.documentNumberingService = documentNumberingService;
        this.taxService = taxService;
        this.accountResolver = accountResolver;
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
                null, // template null — requis seulement pour IFRS (FREE numbering)
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

        // JOURNAL_ENTRY : créer une séquence par code journal (VT, AC, BQ, CA, OD, PA, DP, FX)
        // car le postage cherche DocumentNumberingService.nextNumber(companyId, JOURNAL_ENTRY, journalCode)
        // où journalCode = "VT", "AC", etc. (pas "")
        String ecrPrefix = getPrefix(prefixes, "JOURNAL_ENTRY", "ECR");
        for (String[] jl : DEFAULT_JOURNALS) {
            String journalCode = jl[0];
            count += safeCreateSequence(companyId, DocumentType.JOURNAL_ENTRY, journalCode,
                ecrPrefix + "-" + journalCode + "-" + year + "-", year);
        }

        // ── v9.4 fix — scopeKey alignés sur les consommateurs ──────────────────────
        // Les services consommateurs n'utilisent PAS scopeKey="" (contrairement à ce que dit
        // l'ancien README). Ils utilisent un scopeKey = code journal :
        //   - SALES_INVOICE  → scopeKey="VT" (journal Ventes) — InvoicingService.issueInvoice
        //   - PURCHASE_INVOICE → scopeKey="AC" (journal Achats) — InvoiceDirection.java
        //   - PAYSLIP → scopeKey="PA" (journal Paie) — Payslip.java
        //   - CREDIT_NOTE → scopeKey="VT" (même journal que SALES_INVOICE) — InvoicingService
        //   - DONATION_RECEIPT → scopeKey="" (FundsGrantsService utilise "")
        //
        // Avant ce fix, le provisioning créait toutes les séquences avec scopeKey="" →
        // SEQUENCE_CONFIG_NOT_FOUND à l'émission de facture (HTTP 404) car le lookup
        // utilisait "VT" mais la config était enregistrée avec "".
        count += safeCreateSequence(companyId, DocumentType.SALES_INVOICE, "VT",
            getPrefix(prefixes, "SALES_INVOICE", "INV"), year);
        count += safeCreateSequence(companyId, DocumentType.PURCHASE_INVOICE, "AC",
            getPrefix(prefixes, "PURCHASE_INVOICE", "ACH"), year);
        count += safeCreateSequence(companyId, DocumentType.PAYSLIP, "PA",
            getPrefix(prefixes, "PAYSLIP", "PAY"), year);
        count += safeCreateSequence(companyId, DocumentType.CREDIT_NOTE, "VT",
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
                true, // includeYear
                6, // padding (6 digits)
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
                null, // payableAccountId — résolu au premier usage
                null, // receivableAccountId
                LocalDate.now(),
                null, // applicableTo (pas de fin)
                vatMode);
            TaxRule rule = taxService.createTaxRule(company.getId(), req);
            count++;
            LOG.info("Tax rule created: code={} rate={}%", rule.getCode(), rule.getRate());
        } catch (ConflictException ex) {
            LOG.debug("Tax rule TVA_FR_20 already exists for company {} — skipping", company.getId());
        }
        return count;
    }

    /**
     * Fix Dim 4 P1 (audit v9.4) — Génère une écriture OD de capital social.
     *
     * <p>Poste une écriture équilibrée : Débit 512 (Banque) / Crédit 101 (Capital social).
     * Idempotente via la clé "capital-formation-{companyId}".
     */
    @Override
    @Transactional
    public UUID postCapitalEntry(UUID companyId, java.math.BigDecimal amount) {
        if (amount == null || amount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            LOG.warn("postCapitalEntry ignoré pour company {} : montant null ou <= 0", companyId);
            return null;
        }

        // Résoudre le journal OD (opérations diverses)
        String journalCode = accountingEngineService.getOrCreateJournal(companyId,
            jo.accountant.accountingengine.entity.JournalType.OD).getCode();

        // Résoudre les comptes 512 (Banque, ACTIF, CASH) et 101 (Capital, CAPITAUX_PROPRES)
        Account bankAccount = accountResolver.resolveOrThrow(
            companyId, ReportingClass.ACTIF, "CASH",
            "CASH_ACCOUNT_NOT_FOUND",
            "Aucun compte de trésorerie (CASH) trouvé pour l'écriture de capital social. " +
            "Configurer un compte ACTIF marqué taxMappingCode=\"CASH\" (ex: 512, 521).",
            "521", "512", "57");

        Account capitalAccount = accountResolver.resolveOrThrow(
            companyId, ReportingClass.CAPITAUX_PROPRES, null,
            "CAPITAL_ACCOUNT_NOT_FOUND",
            "Aucun compte de capitaux propres trouvé pour l'écriture de capital social. " +
            "Configurer un compte CAPITAUX_PROPRES (ex: 101, 10).",
            "101", "10");

        // Construire l'écriture : Débit 512 / Crédit 101
        LocalDate entryDate = LocalDate.now();
        List<LineDto> lines = List.of(
            new LineDto(bankAccount.getCode(), null,
                amount, null,
                "Apport en capital social (constitution)", List.of()),
            new LineDto(capitalAccount.getCode(), null,
                null, amount,
                "Capital social (constitution)", List.of()));

        CreateJournalEntryRequest req = new CreateJournalEntryRequest(
            journalCode, entryDate,
            "Constitution du capital social",
            lines, JournalEntrySourceModule.MANUAL);

        try {
            // Idempotency-key "capital-formation-{companyId}" — si déjà postée, ne fait rien
            JournalEntryResponse response = accountingEngineService.createJournalEntry(
                companyId, "capital-formation-" + companyId, req);
            // Poster immédiatement (DRAFT → POSTED) — le capital social est définitif à la constitution
            accountingEngineService.postJournalEntry(companyId, response.id(), List.of());
            LOG.info("Écriture de capital social créée et postée : company={} amount={} entryId={}",
                companyId, amount, response.id());
            return response.id();
        } catch (ConflictException ex) {
            // Idempotent : l'écriture existe déjà (même idempotency-key)
            LOG.info("Écriture de capital social déjà existante pour company {} — skip", companyId);
            return null;
        }
    }
}

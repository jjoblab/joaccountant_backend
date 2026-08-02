package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.entity.TaxCreditCarriedForward;
import jo.accountant.tax.entity.TaxType;
import jo.accountant.tax.repository.TaxCreditCarriedForwardRepository;
import jo.accountant.tax.service.TaxService;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.LettrageMatchRepository;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests d'intégration de la persistance du crédit RS (retenue à la source) reporté —
 * Task 2 du prompt {@code PROMPT_AGENT_IA_CORRECTIONS-1.md}.
 *
 * <p>Avant la correction, {@link TaxService#getWithholdingDeclaration} calculait
 * {@code taxCreditToCarryForward} mais ne le persistait jamais — la valeur était hardcodée
 * à {@code BigDecimal.ZERO} à la lecture (cf. commentaire "TODO v6.3" dans le code d'origine).
 * Le crédit était silencieusement perdu d'une période à l'autre.
 *
 * <p>Scénarios testés :
 * <ul>
 *   <li><b>Règle 1 — Lecture</b> : un crédit RS persisté manuellement pour la période M-1
 *       est lu par {@code getWithholdingDeclaration} sur la période M et présent dans
 *       {@code taxCreditCarriedForward}. Sans factures sur M, le crédit est reporté vers M+1.</li>
 *   <li><b>Règle 2 — Persistance</b> : sur une période sans factures mais avec crédit reporté
 *       de M-1, le crédit est persisté pour la période courante M (pour être lu en M+1).</li>
 *   <li><b>Règle 3 — Idempotence</b> : appeler 2x {@code getWithholdingDeclaration} sur la
 *       même période ne crée qu'une seule ligne de crédit (mise à jour via
 *       {@code uc_tax_credit_period}).</li>
 *   <li><b>Règle 4 — Non-régression TVA</b> : la persistance RS n'impacte pas le crédit TVA
 *       (types distincts dans {@code tax_credit_carried_forward}).</li>
 * </ul>
 *
 * <p>Note : la génération d'un crédit RS <em>à partir de factures/avoirs</em> n'est pas testée
 * ici directement car le {@code InvoicingService} empêche les avoirs de dépasser le total de
 * la facture originale (contrainte métier). Le test se concentre donc sur la persistance et
 * la lecture du crédit, qui est le cœur du correctif.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, WithholdingCreditIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class WithholdingCreditIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private TaxService taxService;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TaxCreditCarriedForwardRepository taxCreditRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            taxCreditRepo.deleteAllInBatch();
            lmRepo.deleteAllInBatch();
            tpRepo.deleteAllInBatch();
            jlRepo.deleteAllInBatch();
            jeRepo.deleteAllInBatch();
            journalRepo.deleteAllInBatch();
            fpRepo.deleteAllInBatch();
            fyRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
            docSeqCounterRepo.deleteAll();
            docSeqConfigRepo.deleteAllInBatch();
            companyRepository.deleteById(COMPANY_A);
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    /** Persiste la Company. */
    private void persistCompany() {
        Company company = new Company();
        company.setId(COMPANY_A);
        company.setName("Test RS Company SARL");
        company.setLegalForm(LegalForm.SARL);
        company.setCountry("HT");
        company.setFunctionalCurrency("HTG");
        company.setSector(Sector.SERVICE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setBusinessTypeCode("CUSTOM");
        company.setPrimaryActivityLabel("Prestations de services Haïti");
        company.setFiscalYearStartMonth(1);
        company.setWizardStep(9);
        company.setWizardCompleted(false);
        company.setCreatedAt(Instant.now());
        company.setUpdatedAt(Instant.now());
        companyRepository.save(company);
    }

    /** Crée un journal en idempotent. */
    private void safeCreateJournal(String code, String label) {
        try {
            accountingService.createJournal(COMPANY_A, code, label);
        } catch (jo.accountant.core.exception.ConflictException ex) {
            // JOURNAL_CODE_ALREADY_EXISTS — idempotent
        }
    }

    /** Crée une séquence en idempotent. */
    private void safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType type,
                                       String scopeKey, String prefix) {
        try {
            docNumberingService.createSequence(COMPANY_A, type, scopeKey, prefix, true, 6, ResetPolicy.YEARLY);
        } catch (jo.accountant.core.exception.ConflictException ex) {
            // SEQUENCE_ALREADY_EXISTS — idempotent
        }
    }

    /** Initialise le fixture (Company + COA + journaux + exercice + tiers client). */
    private UUID initFixture() {
        persistCompany();
        asTenant(COMPANY_A);
        try {
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        } catch (jo.accountant.core.exception.ConflictException ex) {
            // CHART_OF_ACCOUNTS_ALREADY_INITIALIZED — idempotent
        }

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveClient = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));

        safeCreateJournal("VT", "Journal des ventes");
        safeCreateJournal("OD", "Opérations diverses");
        try {
            accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));
        } catch (jo.accountant.core.exception.ConflictException ex) {
            // FISCAL_YEAR_ALREADY_EXISTS — idempotent
        }

        safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY, "VT", "VT");
        safeCreateSequence(jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY, "OD", "OD");

        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.CLIENT, "Client Haïti SARL",
            collectiveClient.id(), "client@ht.dev", null));
        return tp.id();
    }

    /** Insère directement un crédit RS reporté en base pour la période (year, month). */
    private void insertCredit(int year, int month, BigDecimal amount, TaxType taxType) {
        TaxCreditCarriedForward c = new TaxCreditCarriedForward();
        c.setId(UUID.randomUUID());
        c.setCompanyId(COMPANY_A);
        c.setTaxType(taxType);
        c.setPeriodYear(year);
        c.setPeriodMonth(month);
        c.setCreditAmount(amount);
        c.setCarriedToNext(true);
        c.setCreatedAt(Instant.now());
        taxCreditRepo.save(c);
    }

    @Nested
    @DisplayName("Règle 1 — Lecture : getWithholdingDeclaration lit le crédit RS de la période précédente")
    class ReadCredit {

        @Test
        @DisplayName("Période M sans factures + crédit RS en M-1 → taxCreditCarriedForward non nul")
        void readsCreditFromPreviousPeriod() {
            initFixture();

            // Insère un crédit RS de 150 HTG pour juin 2026 (M-1 par rapport à juillet)
            insertCredit(2026, 6, new BigDecimal("150.00"), TaxType.WITHHOLDING);

            // Appelle la déclaration RS pour juillet 2026 (M)
            TaxDeclaration decl = taxService.getWithholdingDeclaration(COMPANY_A,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            // Le crédit reporté doit être lu depuis tax_credit_carried_forward
            assertThat(decl.taxCreditCarriedForward()).isEqualByComparingTo("150.00");
            // Pas de factures → totalTaxCollected = 0
            assertThat(decl.totalTaxCollected()).isEqualByComparingTo("0");
            // taxDue = max(0, 0 - 150) = 0
            assertThat(decl.taxDue()).isEqualByComparingTo("0");
            // Le crédit est reporté vers M+1 (puisque totalWithholding = 0 < 150)
            assertThat(decl.taxCreditToCarryForward()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("Période M sans crédit en M-1 → taxCreditCarriedForward = 0")
        void noCreditInPreviousPeriod_returnsZero() {
            initFixture();

            TaxDeclaration decl = taxService.getWithholdingDeclaration(COMPANY_A,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            assertThat(decl.taxCreditCarriedForward()).isEqualByComparingTo("0");
            assertThat(decl.taxDue()).isEqualByComparingTo("0");
            assertThat(decl.taxCreditToCarryForward()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Persistance : le crédit à reporter est persisté pour la période courante")
    class PersistCredit {

        @Test
        @DisplayName("Période M avec crédit à reporter > 0 → ligne persistée dans tax_credit_carried_forward")
        void persistsCreditToCarryForward() {
            initFixture();

            // Insère un crédit RS en M-1 = juin pour qu'il soit lu en M = juillet
            insertCredit(2026, 6, new BigDecimal("200.00"), TaxType.WITHHOLDING);

            // Appelle la déclaration pour M — totalWithholding = 0, crédit reporté = 200
            // → taxCreditToCarryForward = 200 (puisque 0 - 200 = -200)
            taxService.getWithholdingDeclaration(COMPANY_A,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            // Vérifie la persistance en base pour juillet (M)
            var persistedOpt = taxCreditRepo.findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(
                COMPANY_A, TaxType.WITHHOLDING, 2026, 7);
            assertThat(persistedOpt).isPresent();
            TaxCreditCarriedForward persisted = persistedOpt.get();
            assertThat(persisted.getCreditAmount()).isEqualByComparingTo("200.00");
            assertThat(persisted.isCarriedToNext()).isTrue();
            assertThat(persisted.getTaxType()).isEqualTo(TaxType.WITHHOLDING);
        }

        @Test
        @DisplayName("Période M sans crédit à reporter → aucune ligne persistée pour M")
        void noCreditToCarryForward_noPersist() {
            initFixture();

            // Pas de crédit en M-1, pas de factures en M → totalWithholding = 0, crédit = 0
            taxService.getWithholdingDeclaration(COMPANY_A,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            // Aucune ligne ne doit exister pour juillet (pas de crédit à reporter)
            var persistedOpt = taxCreditRepo.findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(
                COMPANY_A, TaxType.WITHHOLDING, 2026, 7);
            assertThat(persistedOpt).isEmpty();
        }
    }

    @Nested
    @DisplayName("Règle 3 — Idempotence : recalcul de la même déclaration met à jour le crédit")
    class Idempotence {

        @Test
        @DisplayName("Appeler getWithholdingDeclaration 2x sur la même période ne crée qu'un seul crédit")
        void callingTwice_updatesSameRow() {
            initFixture();
            insertCredit(2026, 6, new BigDecimal("100.00"), TaxType.WITHHOLDING);

            // 1er appel
            taxService.getWithholdingDeclaration(COMPANY_A,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            // 2e appel — doit mettre à jour la même ligne, pas en créer une nouvelle
            taxService.getWithholdingDeclaration(COMPANY_A,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            // Compte des lignes pour (company, WITHHOLDING, 2026, 7) — doit être 1
            long count = taxCreditRepo.findAll().stream()
                .filter(c -> c.getCompanyId().equals(COMPANY_A)
                    && c.getTaxType() == TaxType.WITHHOLDING
                    && c.getPeriodYear() == 2026
                    && c.getPeriodMonth() == 7)
                .count();
            assertThat(count).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Règle 4 — Non-régression TVA : la persistance RS n'impacte pas le crédit TVA")
    class NoRegressionVat {

        @Test
        @DisplayName("Crédit RS persisté ne rentre pas en collision avec un crédit TVA pour la même période")
        void witholdingCreditDoesNotCollideWithVatCredit() {
            initFixture();

            // Insère un crédit TVA pour juillet 2026
            insertCredit(2026, 7, new BigDecimal("500.00"), TaxType.VAT);

            // Insère un crédit RS pour la même période juillet 2026 — doit réussir
            // (la contrainte unique est sur (company, tax_type, year, month) — les 2 types
            // sont distincts donc pas de collision)
            insertCredit(2026, 7, new BigDecimal("250.00"), TaxType.WITHHOLDING);

            // Les 2 lignes doivent exister indépendamment
            var vatCredit = taxCreditRepo.findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(
                COMPANY_A, TaxType.VAT, 2026, 7);
            var whCredit = taxCreditRepo.findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(
                COMPANY_A, TaxType.WITHHOLDING, 2026, 7);

            assertThat(vatCredit).isPresent();
            assertThat(vatCredit.get().getCreditAmount()).isEqualByComparingTo("500.00");
            assertThat(whCredit).isPresent();
            assertThat(whCredit.get().getCreditAmount()).isEqualByComparingTo("250.00");
        }
    }
}

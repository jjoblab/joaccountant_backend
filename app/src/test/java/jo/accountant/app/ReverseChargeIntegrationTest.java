package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.JournalLine;
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
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.InvoiceRepository;
import jo.accountant.invoicing.service.InvoicingService;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdParty;
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
 * Tests d'intégration de l'autoliquidation / reverse-charge (Finding #7).
 *
 * <p>Vérifie que lorsqu'un tiers client ET l'entreprise émettrice disposent tous deux d'un
 * numéro de TVA intracommunautaire, l'opération est qualifiée d'autoliquidation (Article 283,
 * 2 nonies du CGI) : la TVA n'est pas collectée par l'émetteur — c'est le client qui l'auto-liquide.
 *
 * <p>Scénario :
 * <ol>
 *   <li>Persiste une {@link Company} avec {@code vatNumber="FR12345678901"}.</li>
 *   <li>Crée un tiers client avec {@code vatNumber="DE987654321"} (via le repository — le
 *       service ne expose pas ce champ).</li>
 *   <li>Émet une facture avec TVA > 0 → vérifie que {@code isReverseCharge=true} et que le
 *       crédit va sur 447 (TDA autoliquidation), pas sur 443 (TVA collectée).</li>
 * </ol>
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ReverseChargeIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ReverseChargeIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private InvoicingService invoicingService;
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
    @Autowired private InvoiceRepository siRepo;
    @Autowired private InvoiceLineRepository ilRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            ilRepo.deleteAllInBatch();
            siRepo.deleteAllInBatch();
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

    /** Persiste une Company avec un VAT number pour activer le reverse-charge. */
    private void persistCompanyWithVat() {
        Company company = new Company();
        company.setId(COMPANY_A);
        company.setName("Test Company SARL");
        company.setLegalForm(LegalForm.SARL);
        company.setCountry("FR");
        company.setFunctionalCurrency("EUR");
        company.setSector(Sector.SERVICE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setBusinessTypeCode("CUSTOM");
        company.setPrimaryActivityLabel("Test reverse-charge");
        company.setFiscalYearStartMonth(1);
        company.setVatNumber("FR12345678901");  // VAT number requis pour le reverse-charge
        company.setWizardStep(9);
        company.setWizardCompleted(true);
        company.setCreatedAt(Instant.now());
        company.setUpdatedAt(Instant.now());
        companyRepository.save(company);
    }

    /** Initialise le fixture : Company avec VAT + plan comptable + comptes 447/443 + journaux + exercice. */
    private UUID initFixture() {
        // 1. Persister la Company avec VAT number (le reverse-charge nécessite company.vatNumber).
        persistCompanyWithVat();

        // 2. Setup tenant + plan comptable
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveClient = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701000", "Ventes de marchandises", ReportingClass.PRODUITS,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        // 443 — TVA collectée (ne doit PAS être créditée en reverse-charge)
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "443000", "TVA collectée", ReportingClass.PASSIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));
        // 447 — TDA autoliquidation (doit être créditée en reverse-charge)
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "447", "TVA autoliquidation (reverse charge)", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false,
            "VAT_REVERSE_CHARGE", List.of()));

        accountingService.createJournal(COMPANY_A, "VT", "Journal des ventes");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.SALES_INVOICE,
            "VT", "FAC", true, 6, ResetPolicy.YEARLY);

        // 3. Créer un tiers client avec un VAT number (le service ne l'expose pas — on set directement via le repository)
        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.CLIENT, "Client Intra-UE GmbH",
            collectiveClient.id(), "client@eu.dev", null));
        ThirdParty tpEntity = tpRepo.findById(tp.id()).orElseThrow();
        tpEntity.setVatNumber("DE987654321");  // VAT number requis pour le reverse-charge
        tpRepo.save(tpEntity);

        return tp.id();
    }

    private CreateInvoiceRequest standardInvoice(UUID thirdPartyId) {
        // Ligne : 10 × 1000 = 10000 HT, TVA 20% = 2000, TTC = 12000
        return new CreateInvoiceRequest(
            thirdPartyId, InvoiceType.STANDARD,
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
            "EUR",
            List.of(new CreateInvoiceRequest.LineDto(
                "Prestation de services B2B intra-UE", new BigDecimal("10"),
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("20"),
                null, null)),
            null);
    }

    @Nested
    @DisplayName("Règle 1 — Reverse-charge : isReverseCharge=true + crédit sur 447")
    class ReverseChargeActivation {
        @Test
        @DisplayName("Émettre une facture B2B intra-UE → isReverseCharge=true, crédit 447 (pas 443)")
        void reverseChargeCredits447() {
            UUID tpId = initFixture();
            InvoiceResponse inv = invoicingService.createInvoice(COMPANY_A, standardInvoice(tpId));
            InvoiceResponse issued = invoicingService.issueInvoice(COMPANY_A, inv.id());

            // (1) isReverseCharge doit être true (les 2 parties ont un VAT number)
            assertThat(issued.reverseCharge()).isTrue();

            // (2) Le crédit TVA doit aller sur 447 (TDA autoliquidation), PAS sur 443 (TVA collectée)
            UUID entryId = issued.journalEntryId();
            assertThat(entryId).isNotNull();
            List<JournalLine> lines = jlRepo.findByJournalEntryIdOrderByLineNumber(entryId);

            BigDecimal credit447 = lines.stream()
                .filter(l -> l.getAccountCode() != null && l.getAccountCode().startsWith("447"))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit443 = lines.stream()
                .filter(l -> l.getAccountCode() != null && l.getAccountCode().startsWith("443"))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // TVA = 2000 → doit être sur 447
            assertThat(credit447).isEqualByComparingTo("2000.0000");
            // Rien sur 443 (le reverse-charge désactive la TVA collectée par l'émetteur)
            // Note : on vérifie que 4430xx n'a pas de crédit (4438 commence par 443 aussi,
            // mais le 4438 n'est pas créé dans ce test — pas de TVA encaissement ici).
            assertThat(credit443).isEqualByComparingTo("0");

            // (3) Équilibre débit/crédit
            BigDecimal totalDebit = lines.stream().map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = lines.stream().map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit).isEqualByComparingTo(totalCredit);
            // Total TTC = 12000 (10000 HT + 2000 TVA) — débit client
            assertThat(totalDebit).isEqualByComparingTo("12000.0000");
        }
    }
}

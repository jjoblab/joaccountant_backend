package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.JournalEntry;
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
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tax.VatMode;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.invoicing.dto.CreateInvoiceRequest;
import jo.accountant.invoicing.dto.InvoiceResponse;
import jo.accountant.invoicing.dto.RecordPaymentRequest;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.invoicing.service.InvoicingService;
import jo.accountant.tax.dto.CreateTaxRuleRequest;
import jo.accountant.tax.entity.TaxRule;
import jo.accountant.tax.repository.TaxRuleRepository;
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
 * Tests d'intégration de la TVA sur encaissement (Finding #6 — VatMode.ENCAISSEMENT).
 *
 * <p>Vérifie que lorsqu'un {@code TaxRule} est en mode {@link VatMode#ENCAISSEMENT}, la TVA
 * d'une facture émise est stockée dans le compte d'attente 4438 (TVA sur factures émises non
 * encaissées) à l'émission, puis basculée vers le compte 443 (TVA collectée) au règlement.
 *
 * <p>Scénario :
 * <ol>
 *   <li>Crée un {@code TaxRule} avec {@code vatMode=ENCAISSEMENT} (taux 15%).</li>
 *   <li>Émet une facture avec une ligne à TVA 15% → vérifie que le crédit va sur 4438
 *       (pas sur 443).</li>
 *   <li>Enregistre un paiement → vérifie la bascule 4438 → 443 (débit 4438, crédit 443).</li>
 * </ol>
 */
@SpringBootTest(classes = {JoAccountantApplication.class, VatEncaissementIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class VatEncaissementIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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
    @Autowired private TaxService taxService;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private SalesInvoiceRepository siRepo;
    @Autowired private InvoiceLineRepository ilRepo;
    @Autowired private TaxRuleRepository taxRuleRepo;
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
            taxRuleRepo.deleteAll();
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
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    /** Initialise le fixture : plan comptable + comptes 443/4438 + journaux + exercice + séquences. */
    private UUID initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

        // Compte collectif Clients (411000)
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveClient = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        // Compte de ventes (701000)
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701000", "Ventes de marchandises", ReportingClass.PRODUITS,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        // Compte TVA collectée (443000) — reçu en crédit à l'émission d'une facture en mode DEBIT.
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "443000", "TVA collectée", ReportingClass.PASSIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));
        // Compte TVA différée (4438) — reçu en crédit à l'émission d'une facture en mode ENCAISSEMENT.
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "4438", "TVA sur factures émises non encaissées", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false,
            "VAT_DEFERRED_UNCOLLECTED", List.of()));

        // Journaux
        accountingService.createJournal(COMPANY_A, "VT", "Journal des ventes");
        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");

        // Exercice fiscal 2026
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        // Séquences de numérotation
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.SALES_INVOICE,
            "VT", "FAC", true, 6, ResetPolicy.YEARLY);

        // Créer la règle TVA en mode ENCAISSEMENT (taux 15%)
        TaxRule rule = taxService.createTaxRule(COMPANY_A, new CreateTaxRuleRequest(
            "TVA-ENC-15", "TVA sur encaissement 15%", new BigDecimal("15"),
            null, null, LocalDate.of(2026, 1, 1), null, VatMode.ENCAISSEMENT));
        assertThat(rule.getVatMode()).isEqualTo(VatMode.ENCAISSEMENT);

        // Créer un tiers client
        ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.CLIENT, "Client Test SARL",
            collectiveClient.id(), "client@test.dev", null));
        return tp.id();
    }

    private CreateInvoiceRequest standardInvoice(UUID thirdPartyId) {
        // Ligne : 10 × 1000 = 10000 HT, TVA 15% = 1500, TTC = 11500
        return new CreateInvoiceRequest(
            thirdPartyId, InvoiceType.STANDARD,
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
            "HTG",
            List.of(new CreateInvoiceRequest.LineDto(
                "Vente de marchandises", new BigDecimal("10"), new BigDecimal("1000"),
                BigDecimal.ZERO, new BigDecimal("15"), null, null)),
            null);
    }

    private BigDecimal sumCreditByAccount(UUID journalEntryId, String accountCodePrefix) {
        return jlRepo.findByJournalEntryIdOrderByLineNumber(journalEntryId).stream()
            .filter(l -> l.getAccountCode() != null && l.getAccountCode().startsWith(accountCodePrefix))
            .map(JournalLine::getCredit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumDebitByAccount(UUID journalEntryId, String accountCodePrefix) {
        return jlRepo.findByJournalEntryIdOrderByLineNumber(journalEntryId).stream()
            .filter(l -> l.getAccountCode() != null && l.getAccountCode().startsWith(accountCodePrefix))
            .map(JournalLine::getDebit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    @DisplayName("Règle 1 — TVA sur encaissement : crédit sur 4438 à l'émission")
    class EmissionEncaissement {
        @Test
        @DisplayName("Émettre une facture avec TaxRule ENCAISSEMENT → crédit 4438 (pas 443)")
        void vatDeferredTo4438OnIssue() {
            UUID tpId = initFixture();
            InvoiceResponse inv = invoicingService.createInvoice(COMPANY_A, standardInvoice(tpId));
            InvoiceResponse issued = invoicingService.issueInvoice(COMPANY_A, inv.id());

            // La TVA (1500) doit être créditée au 4438 (différée), PAS au 443 (collectée).
            UUID entryId = issued.journalEntryId();
            assertThat(entryId).isNotNull();
            BigDecimal credit4438 = sumCreditByAccount(entryId, "4438");
            BigDecimal credit443 = sumCreditByAccount(entryId, "443");
            assertThat(credit4438).isEqualByComparingTo("1500.0000");
            assertThat(credit443).isEqualByComparingTo("0");  // rien sur 443 (mais 4438 commence par 443 → filtre strict)

            // Vérifier que vatDeferredAmount a été mémorisé sur la facture (pour la bascule au règlement).
            SalesInvoice reloaded = siRepo.findById(inv.id()).orElseThrow();
            assertThat(reloaded.getVatDeferredAmount()).isEqualByComparingTo("1500.0000");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Bascule 4438 → 443 au règlement")
    class BasculeReglement {
        @Test
        @DisplayName("Règlement total → écriture de bascule (Débit 4438 / Crédit 443)")
        void vatBascule4438To443OnPayment() {
            UUID tpId = initFixture();
            InvoiceResponse inv = invoicingService.createInvoice(COMPANY_A, standardInvoice(tpId));
            invoicingService.issueInvoice(COMPANY_A, inv.id());

            // Avant règlement : aucun settlement entry
            SalesInvoice before = siRepo.findById(inv.id()).orElseThrow();
            assertThat(before.getVatSettlementEntryId()).isNull();

            // Règlement total (11500 TTC) → bascule intégrale de la TVA différée (1500)
            InvoiceResponse paid = invoicingService.recordPayment(COMPANY_A, inv.id(),
                new RecordPaymentRequest(new BigDecimal("11500")));
            assertThat(paid.status()).isIn("PAID");

            // Après règlement : vatSettlementEntryId est renseigné
            SalesInvoice after = siRepo.findById(inv.id()).orElseThrow();
            assertThat(after.getVatSettlementEntryId()).isNotNull();
            // vatDeferredAmount est décrémenté (ramené à 0 après règlement total)
            assertThat(after.getVatDeferredAmount()).isEqualByComparingTo("0");

            // L'écriture de bascule doit avoir : Débit 4438 (sortie attente) + Crédit 443 (entrée exigible)
            UUID settlementEntryId = after.getVatSettlementEntryId();
            JournalEntry settlementEntry = jeRepo.findById(settlementEntryId).orElseThrow();
            List<JournalLine> settlementLines = jlRepo
                .findByJournalEntryIdOrderByLineNumber(settlementEntryId);

            BigDecimal debit4438 = settlementLines.stream()
                .filter(l -> l.getAccountCode() != null && l.getAccountCode().startsWith("4438"))
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit443 = settlementLines.stream()
                .filter(l -> l.getAccountCode() != null && l.getAccountCode().equals("443000"))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(debit4438).isEqualByComparingTo("1500.0000");  // sortie du compte d'attente
            assertThat(credit443).isEqualByComparingTo("1500.0000");  // entrée dans le compte exigible

            // Équilibre débit/crédit
            BigDecimal totalDebit = settlementLines.stream().map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = settlementLines.stream().map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        }
    }
}

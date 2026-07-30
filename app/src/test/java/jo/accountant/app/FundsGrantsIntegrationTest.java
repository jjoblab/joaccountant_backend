package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.repository.ApprovalRuleRepository;
import jo.accountant.approvalworkflow.repository.ApprovalRequestRepository;
import jo.accountant.approvalworkflow.service.ApprovalWorkflowService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.fundsgrants.dto.CloseFiscalYearResult;
import jo.accountant.fundsgrants.dto.CreateDonationReceiptRequest;
import jo.accountant.fundsgrants.dto.CreateGrantRequest;
import jo.accountant.fundsgrants.dto.DonorReport;
import jo.accountant.fundsgrants.dto.GrantResponse;
import jo.accountant.fundsgrants.entity.DonationReceipt;
import jo.accountant.fundsgrants.entity.RestrictionType;
import jo.accountant.fundsgrants.repository.DonationReceiptRepository;
import jo.accountant.fundsgrants.repository.GrantRepository;
import jo.accountant.fundsgrants.service.FundsGrantsService;
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
 * Tests d'intégration du module {@code funds-grants} — Phase 14.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, FundsGrantsIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class FundsGrantsIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private FundsGrantsService service;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private ApprovalWorkflowService approvalService;
    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private ModuleAccessGuard moduleAccessGuard;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private GrantRepository grantRepo;
    @Autowired private DonationReceiptRepository receiptRepo;
    @Autowired private ApprovalRuleRepository approvalRuleRepo;
    @Autowired private ApprovalRequestRepository approvalRequestRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cleanupFor(COMPANY_A);
        cleanupFor(COMPANY_B);
        TenantContext.clear();
    }

    private void cleanupFor(UUID companyId) {
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_X);
            receiptRepo.deleteAllInBatch();
            grantRepo.deleteAllInBatch();
            approvalRequestRepo.deleteAllInBatch();
            approvalRuleRepo.deleteAllInBatch();
            lmRepo.deleteAllInBatch();
            tpRepo.deleteAllInBatch();
            jlatRepo.deleteAllInBatch();
            jlRepo.deleteAllInBatch();
            jeRepo.deleteAllInBatch();
            journalRepo.deleteAllInBatch();
            fpRepo.deleteAllInBatch();
            fyRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
            docSeqCounterRepo.deleteAll();
            docSeqConfigRepo.deleteAllInBatch();
        });
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 6 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si FUNDS_GRANTS désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.FUNDS_GRANTS))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    private UUID initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveDonor = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "480", "Donateurs", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));

        accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
        accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "OD", "OD", true, 5, ResetPolicy.YEARLY);
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.DONATION_RECEIPT,
            "", "DON", true, 7, ResetPolicy.YEARLY);

        ThirdPartyResponse donor = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
            ThirdPartyType.DONOR, "CRS Haïti", collectiveDonor.id(), null, null));
        return donor.id();
    }

    @Nested
    @DisplayName("Règle 1 — Création de subvention")
    class CreationSubvention {
        @Test
        @DisplayName("Créer une subvention RESTRICTED OK")
        void createRestrictedGrant() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS 2026 — Eau potable",
                new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.RESTRICTED, null));
            assertThat(grant.id()).isNotNull();
            assertThat(grant.code()).isEqualTo("CRS-2026");
            assertThat(grant.restrictionType()).isEqualTo(RestrictionType.RESTRICTED);
        }

        @Test
        @DisplayName("Créer une subvention avec un tiers non DONOR → 422")
        void nonDonorRejected() {
            asTenant(COMPANY_A);
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
            var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
            var collectiveClient = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
                "411000", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
                NormalBalance.DEBIT, true, null, List.of()));
            accountingService.createJournal(COMPANY_A, "OD", "OD");
            accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Ex 2026"));
            docNumberingService.createSequence(COMPANY_A,
                jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
                "OD", "OD", true, 5, ResetPolicy.YEARLY);

            ThirdPartyResponse client = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.CLIENT, "Client Test", collectiveClient.id(), null, null));

            assertThatThrownBy(() -> service.createGrant(COMPANY_A, new CreateGrantRequest(
                client.id(), "TEST", "Test", BigDecimal.ONE, "HTG",
                LocalDate.of(2026, 1, 1), null, RestrictionType.RESTRICTED, null)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("NOT_A_DONOR");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Reçu de don avec numéro via document-numbering")
    class ReçuDon {
        @Test
        @DisplayName("Créer un reçu → receiptNumber généré")
        void createDonationReceipt() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS", new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.RESTRICTED, null));

            DonationReceipt receipt = service.createDonationReceipt(COMPANY_A,
                new CreateDonationReceiptRequest(grant.id(), donorId,
                    new BigDecimal("500000"), LocalDate.of(2026, 6, 15), "Don en espèces"));

            assertThat(receipt.getId()).isNotNull();
            assertThat(receipt.getReceiptNumber()).isNotNull().startsWith("DON-2026-");
            assertThat(receipt.getAmount()).isEqualByComparingTo("500000");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Rapport bailleur")
    class RapportBailleur {
        @Test
        @DisplayName("Rapport affiche montant reçu et solde")
        void donorReport() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS", new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.RESTRICTED, null));

            service.createDonationReceipt(COMPANY_A,
                new CreateDonationReceiptRequest(grant.id(), donorId,
                    new BigDecimal("300000"), LocalDate.of(2026, 3, 1), "Tranche 1"));
            service.createDonationReceipt(COMPANY_A,
                new CreateDonationReceiptRequest(grant.id(), donorId,
                    new BigDecimal("200000"), LocalDate.of(2026, 6, 1), "Tranche 2"));

            DonorReport report = service.getDonorReport(COMPANY_A, grant.id());
            assertThat(report.totalReceived()).isEqualByComparingTo("500000");
            assertThat(report.balanceRemaining()).isEqualByComparingTo("500000");
            assertThat(report.donorName()).isEqualTo("CRS Haïti");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Clôture d'exercice : fonds dédiés")
    class ClotureFondsDédiés {
        @Test
        @DisplayName("RESTRICTED avec solde positif + règle d'approbation → ApprovalRequest créée")
        void closeFiscalYearWithFundsDedicated() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS", new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.RESTRICTED, null));

            service.createDonationReceipt(COMPANY_A,
                new CreateDonationReceiptRequest(grant.id(), donorId,
                    new BigDecimal("500000"), LocalDate.of(2026, 6, 1), "Don"));

            // Créer une règle d'approbation pour GRANT_DISBURSEMENT_PROPOSAL
            approvalService.createRule(COMPANY_A, ApprovalActionType.GRANT_DISBURSEMENT_PROPOSAL,
                new BigDecimal("1"), List.of("ADMIN"), 1);

            CloseFiscalYearResult result = service.closeFiscalYear(COMPANY_A, grant.id());

            assertThat(result.fundsDedicatedProposed()).isTrue();
            assertThat(result.approvalRequestId()).isNotNull();
            assertThat(result.balance()).isEqualByComparingTo("500000");
        }

        @Test
        @DisplayName("RESTRICTED sans règle d'approbation → message informatif")
        void closeFiscalYearWithoutRule() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS", new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.RESTRICTED, null));

            service.createDonationReceipt(COMPANY_A,
                new CreateDonationReceiptRequest(grant.id(), donorId,
                    new BigDecimal("500000"), LocalDate.of(2026, 6, 1), "Don"));

            CloseFiscalYearResult result = service.closeFiscalYear(COMPANY_A, grant.id());

            assertThat(result.fundsDedicatedProposed()).isFalse();
            assertThat(result.message()).contains("aucune règle d'approbation active");
        }

        @Test
        @DisplayName("UNRESTRICTED → pas de fonds dédiés")
        void unrestrictedNoFundsDedicated() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS", new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.UNRESTRICTED, null));

            CloseFiscalYearResult result = service.closeFiscalYear(COMPANY_A, grant.id());

            assertThat(result.fundsDedicatedProposed()).isFalse();
            assertThat(result.message()).contains("non restreinte");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Isolation multi-tenant")
    class IsolationTenant {
        @Test
        @DisplayName("Company B ne peut pas voir la subvention de A → 404")
        void companyBCannotSeeCompanyAGrant() {
            UUID donorId = initFixture();
            GrantResponse grant = service.createGrant(COMPANY_A, new CreateGrantRequest(
                donorId, "CRS-2026", "Subvention CRS", new BigDecimal("1000000"), "HTG",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                RestrictionType.RESTRICTED, null));

            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getDonorReport(COMPANY_B, grant.id()))
                .isInstanceOf(NotFoundException.class);
        }
    }
}

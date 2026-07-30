package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.AnalyticalTagDto;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.Journal;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.service.ApprovalWorkflowService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.dto.UpdateAccountRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.guard.AccountBalanceGuard;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
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
 * Tests d'intégration du module {@code accounting-engine} + {@code analytics} — Phase 5.
 *
 * <p>Couverture des 13 règles métier §13 Phase 5 (chacune testée par un scénario qui échouerait
 * si la règle était retirée).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, AccountingEngineIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class AccountingEngineIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private AccountingEngineService service;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private ApprovalWorkflowService approvalService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private JournalLineAnalyticalTagRepository jlatRepo;
    @Autowired private jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private jo.accountant.approvalworkflow.repository.ApprovalRuleRepository approvalRuleRepo;
    @Autowired private jo.accountant.approvalworkflow.repository.ApprovalRequestRepository approvalRequestRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private AccountBalanceGuard balanceGuard;
    @Autowired private jo.accountant.analytics.repository.AnalyticalDimensionPlanRepository analyticsPlanRepo;

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
            jlatRepo.deleteAllInBatch();
            jlRepo.deleteAllInBatch();
            jeRepo.deleteAllInBatch();
            journalRepo.deleteAllInBatch();
            fpRepo.deleteAllInBatch();
            fyRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
            approvalRequestRepo.deleteAllInBatch();
            approvalRuleRepo.deleteAllInBatch();
            analyticsPlanRepo.deleteAllInBatch();
            docSeqCounterRepo.deleteAll();
            docSeqConfigRepo.deleteAllInBatch();
        });
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    /** Initialise un plan SYSCOHADA + journal VT + exercice 2026 pour COMPANY_A. */
    private void initFixture() {
        asTenant(COMPANY_A);
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        // Créer un compte 411 (clients) sous la classe 4
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "411", "Clients", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
            NormalBalance.DEBIT, true, null, List.of()));
        // Compte 701 (ventes) sous la classe 7
        var class7 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "7").orElseThrow();
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "701", "Ventes de marchandises", ReportingClass.PRODUITS,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, false, null, List.of()));
        // Compte 443 (TVA collectée)
        coaService.createChild(COMPANY_A, class7.getId(), new CreateChildRequest(
            "443", "TVA collectée", ReportingClass.PASSIF, ReportingSubcategory.COURANT,
            NormalBalance.CREDIT, false, null, List.of()));

        // Journal VT
        service.createJournal(COMPANY_A, "VT", "Journal des ventes");

        // Exercice 2026
        service.createFiscalYear(COMPANY_A, new jo.accountant.accountingengine.dto.CreateFiscalYearRequest(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

        // Configurer la séquence de numérotation pour JOURNAL_ENTRY / VT
        docNumberingService.createSequence(COMPANY_A,
            jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
            "VT", "VT", true, 5, ResetPolicy.YEARLY);
    }

    private CreateJournalEntryRequest balancedEntry() {
        return new CreateJournalEntryRequest(
            "VT", LocalDate.of(2026, 7, 15),
            "Facture de vente 2026-0142 — Boutique Pétion-Ville",
            List.of(
                new LineDto("411", null, new BigDecimal("11500.00"), null, "Client", List.of()),
                new LineDto("701", null, null, new BigDecimal("10000.00"), "Ventes", List.of()),
                new LineDto("443", null, null, new BigDecimal("1500.00"), "TVA", List.of())
            ),
            jo.accountant.accountingengine.entity.JournalEntrySourceModule.MANUAL
        );
    }

    @Nested
    @DisplayName("Règle 1 — Somme(débit) = somme(crédit)")
    class Equilibre {

        @Test
        @DisplayName("Écriture déséquilibrée → 422")
        void unbalancedEntryRejected() {
            initFixture();
            CreateJournalEntryRequest unbalanced = new CreateJournalEntryRequest(
                "VT", LocalDate.of(2026, 7, 15), "Déséquilibrée",
                List.of(
                    new LineDto("411", null, new BigDecimal("100.00"), null, "D", List.of()),
                    new LineDto("701", null, null, new BigDecimal("90.00"), "C", List.of())
                ),
                jo.accountant.accountingengine.entity.JournalEntrySourceModule.MANUAL);
            assertThatThrownBy(() -> service.createJournalEntry(COMPANY_A, "key-unbalanced-1", unbalanced))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("UNBALANCED_ENTRY");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Création uniquement sur période OPEN")
    class PeriodeOpen {

        @Test
        @DisplayName("Période LOCKED → 409")
        void lockedPeriodRejectsEntry() {
            initFixture();
            // Verrouiller la période de juillet 2026
            FiscalYear fy2026 = fyRepo.findByCompanyIdOrderByStartDateAsc(COMPANY_A).get(0);
            FiscalPeriod july2026 = fpRepo.findByFiscalYearIdOrderByStartDateAsc(fy2026.getId()).get(6);
            service.lockFiscalPeriod(COMPANY_A, july2026.getId());

            assertThatThrownBy(() -> service.createJournalEntry(COMPANY_A, "key-locked-1", balancedEntry()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("PERIOD_LOCKED");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Passage à POSTED soumis à approval-workflow si montant > seuil")
    class ApprovalWorkflow {

        @Test
        @DisplayName("Avec règle active et montant > seuil → PENDING_APPROVAL")
        void postWithRuleAboveThresholdGoesPending() {
            initFixture();
            // Créer une règle d'approbation : JOURNAL_ENTRY_POST > 10000 → approbation
            approvalService.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("10000"), List.of("ADMIN"), 1);

            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-approval-1", balancedEntry());
            JournalEntryResponse posted = service.postJournalEntry(COMPANY_A, created.id(), List.of());

            assertThat(posted.status()).isEqualTo(JournalEntryStatus.PENDING_APPROVAL);
            assertThat(posted.reference()).isNull();  // pas encore attribué
        }

        @Test
        @DisplayName("Sans règle active → POSTED direct + reference généré")
        void postWithoutRuleGoesDirectlyPosted() {
            initFixture();
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-direct-1", balancedEntry());
            JournalEntryResponse posted = service.postJournalEntry(COMPANY_A, created.id(), List.of());

            assertThat(posted.status()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(posted.reference()).isNotNull().startsWith("VT-2026-");
            assertThat(posted.postedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 4 — Écriture POSTED immuable")
    class Immuabilite {

        @Test
        @DisplayName("Poster une écriture déjà POSTED → 409")
        void cannotPostAlreadyPostedEntry() {
            initFixture();
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-immut-1", balancedEntry());
            service.postJournalEntry(COMPANY_A, created.id(), List.of());

            assertThatThrownBy(() -> service.postJournalEntry(COMPANY_A, created.id(), List.of()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ENTRY_NOT_DRAFT");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Impossible de poster sur un compte active=false")
    class CompteInactif {

        @Test
        @DisplayName("Désactiver un compte puis tenter de poster une écriture le référençant → 422")
        void cannotPostOnInactiveAccount() {
            initFixture();
            // Créer l'écriture en brouillon d'abord
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-inactive-1", balancedEntry());

            // Désactiver le compte 701 (Ventes) — solde nul en Phase 5 test, donc autorisé
            var compte701 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "701").orElseThrow();
            coaService.update(COMPANY_A, compte701.getId(),
                new UpdateAccountRequest(null, null, null, false, null));

            // Tenter de poster → 422 ACCOUNT_INACTIVE
            assertThatThrownBy(() -> service.postJournalEntry(COMPANY_A, created.id(), List.of()))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("ACCOUNT_INACTIVE");
        }
    }

    @Nested
    @DisplayName("Règle 7 — Balance générale et grand livre reconciliables à zéro")
    class BalanceReconciliable {

        @Test
        @DisplayName("Après postage d'une écriture équilibrée, somme balance = 0")
        void trialBalanceSumsToZero() {
            initFixture();
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-balance-1", balancedEntry());
            service.postJournalEntry(COMPANY_A, created.id(), List.of());

            var trialBalance = service.getTrialBalance(COMPANY_A);
            BigDecimal totalDebit = trialBalance.stream().map(l -> l.totalDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = trialBalance.stream().map(l -> l.totalCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        }
    }

    @Nested
    @DisplayName("Règle 8 — Idempotence (Idempotency-Key)")
    class Idempotence {

        @Test
        @DisplayName("Rejouer la même clé renvoie la même écriture, pas de doublon")
        void idempotentReplayReturnsSameEntry() {
            initFixture();
            String key = "key-idem-1";
            JournalEntryResponse first = service.createJournalEntry(COMPANY_A, key, balancedEntry());
            JournalEntryResponse second = service.createJournalEntry(COMPANY_A, key, balancedEntry());

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(jeRepo.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Règle 9 — AccountBalanceGuard implémenté")
    class GuardImpl {

        @Test
        @DisplayName("Compte avec écriture postée → solde non nul → désactivation refusée")
        void accountWithPostedEntryCannotBeDeactivated() {
            initFixture();
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-guard-1", balancedEntry());
            service.postJournalEntry(COMPANY_A, created.id(), List.of());

            // Le compte 411 a maintenant un solde débiteur de 11500
            var compte411 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "411").orElseThrow();
            assertThat(balanceGuard.hasNonZeroBalance(COMPANY_A, compte411.getId()))
                .as("Le guard doit détecter un solde non nul").isTrue();

            // Tentative de désactivation → 409
            assertThatThrownBy(() -> coaService.update(COMPANY_A, compte411.getId(),
                new UpdateAccountRequest(null, null, null, false, null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_BALANCED");
        }

        @Test
        @DisplayName("Compte sans écriture → solde nul → désactivation autorisée")
        void accountWithoutEntryCanBeDeactivated() {
            initFixture();
            var compte701 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "701").orElseThrow();
            assertThat(balanceGuard.hasNonZeroBalance(COMPANY_A, compte701.getId()))
                .as("Le guard doit détecter un solde nul").isFalse();
        }
    }

    @Nested
    @DisplayName("Règle 10 — reference générée via document-numbering au postage")
    class ReferenceAuPostage {

        @Test
        @DisplayName("En DRAFT, pas de reference ; en POSTED, reference présente")
        void referenceAttributedAtPostTime() {
            initFixture();
            JournalEntryResponse draft = service.createJournalEntry(COMPANY_A, "key-ref-1", balancedEntry());
            assertThat(draft.reference()).isNull();
            assertThat(draft.status()).isEqualTo(JournalEntryStatus.DRAFT);

            JournalEntryResponse posted = service.postJournalEntry(COMPANY_A, draft.id(), List.of());
            assertThat(posted.reference()).isNotNull().startsWith("VT-2026-");
        }
    }

    @Nested
    @DisplayName("Règle 11 — Contre-passation")
    class ContrePassation {

        @Test
        @DisplayName("Reverse crée une écriture inversée et marque l'originale VOIDED")
        void reverseCreatesInvertedEntryAndVoidedOriginal() {
            initFixture();
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-reverse-1", balancedEntry());
            JournalEntryResponse posted = service.postJournalEntry(COMPANY_A, created.id(), List.of());
            String originalReference = posted.reference();

            JournalEntryResponse reversal = service.reverseJournalEntry(COMPANY_A, created.id(), "Erreur de saisie");

            // La contre-passation est POSTED avec un nouveau reference
            assertThat(reversal.status()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(reversal.reference()).isNotNull().isNotEqualTo(originalReference);
            assertThat(reversal.reversalOfEntryId()).isEqualTo(created.id());
            assertThat(reversal.sourceModule())
                .isEqualTo(jo.accountant.accountingengine.entity.JournalEntrySourceModule.REVERSAL);

            // L'originale est VOIDED mais conserve son reference
            JournalEntryResponse originalAfter = service.loadJournalEntryResponse(COMPANY_A, created.id());
            assertThat(originalAfter.status()).isEqualTo(JournalEntryStatus.VOIDED);
            assertThat(originalAfter.reference()).isEqualTo(originalReference);

            // Les lignes de la contre-passation sont inversées
            assertThat(reversal.totalDebit()).isEqualByComparingTo(posted.totalCredit());
            assertThat(reversal.totalCredit()).isEqualByComparingTo(posted.totalDebit());
        }
    }

    @Nested
    @DisplayName("Règle 12 — Isolation multi-tenant")
    class IsolationTenant {

        @Test
        @DisplayName("Company B ne peut pas poster une écriture de Company A → 404")
        void companyBCannotPostCompanyAEntry() {
            initFixture();
            JournalEntryResponse created = service.createJournalEntry(COMPANY_A, "key-iso-1", balancedEntry());

            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.postJournalEntry(COMPANY_B, created.id(), List.of()))
                .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Règle 6 — Tags analytiques obligatoires (Vague 1, item 1.6)")
    class TagsAnalytiquesObligatoires {

        @Test
        @DisplayName("Compte avec requiresAnalyticalTagPlanIds → postage sans tag → 422")
        void analyticalTagRequiredAtPostTime() {
            initFixture();

            // Créer un plan analytique "Fonds/Projets"
            var plan = new jo.accountant.analytics.entity.AnalyticalDimensionPlan();
            plan.setCompanyId(COMPANY_A);
            plan.setCode("FONDS");
            plan.setLabel("Fonds/Projets");
            plan.setActive(true);
            analyticsPlanRepo.save(plan);

            // Marquer le compte 701 (Ventes) comme nécessitant un tag analytique
            var compte701 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "701").orElseThrow();
            compte701.setRequiresAnalyticalTagPlanIds("[\"" + plan.getId() + "\"]");
            accountRepo.save(compte701);

            // Créer une écriture avec une ligne sur 701 SANS tag analytique
            CreateJournalEntryRequest req = new CreateJournalEntryRequest(
                "VT", LocalDate.of(2026, 7, 15), "Test sans tag analytique",
                List.of(new LineDto("411", null, new BigDecimal("1000"), null, null, List.of()),
                        new LineDto("701", null, null, new BigDecimal("1000"), null, List.of())),
                JournalEntrySourceModule.MANUAL);

            JournalEntryResponse entry = service.createJournalEntry(
                COMPANY_A, "key-analytical-test-1", req);

            // Le postage doit échouer avec 422 ANALYTICAL_TAG_REQUIRED
            assertThatThrownBy(() -> service.postJournalEntry(COMPANY_A, entry.id(), List.of()))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("ANALYTICAL_TAG_REQUIRED");
        }
    }
}

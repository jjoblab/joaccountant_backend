package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jo.accountant.approvalworkflow.dto.EvaluateResult;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.approvalworkflow.entity.ApprovalRule;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import jo.accountant.approvalworkflow.repository.ApprovalRequestRepository;
import jo.accountant.approvalworkflow.repository.ApprovalRuleRepository;
import jo.accountant.approvalworkflow.service.ApprovalWorkflowService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
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
 * Tests d'intégration du module {@code approval-workflow} — Phase 4.
 *
 * <p>Couverture des 12 règles métier §7 (chacune testée par un scénario qui échouerait si la
 * règle était retirée) :
 * <ol>
 *   <li>Création de règle OK ; doublon actionType active=true → 409</li>
 *   <li>evaluate sous le seuil → auto-approved, aucune request créée</li>
 *   <li>evaluate au-dessus du seuil → request PENDING créée + notification</li>
 *   <li>evaluate sans règle active → auto-approved (pas de blocage surprise)</li>
 *   <li>Auto-approbation refusée : requestedBy == decidedBy → 403</li>
 *   <li>Approbation OK par un autre utilisateur → APPROVED</li>
 *   <li>Rejet → REJECTED + commentaire obligatoire</li>
 *   <li>Annulation → CANCELLED (même par le demandeur lui-même)</li>
 *   <li>Approbation d'une demande déjà décidée → 409</li>
 *   <li>Isolation multi-tenant</li>
 *   <li>Notification envoyée aux emails fournis (spy NotificationChannelPort)</li>
 *   <li>minApprovals > 1 → 422 (non supporté en Phase 4)</li>
 * </ol>
 *
 * <p>PostgreSQL réel via Zonky embedded-postgres (pas H2 — §3.7).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ApprovalWorkflowIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ApprovalWorkflowIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_REQUESTER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID USER_APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private ApprovalWorkflowService service;
    @Autowired private ApprovalRuleRepository ruleRepo;
    @Autowired private ApprovalRequestRepository requestRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private RecordingNotificationChannel notificationSpy;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cleanupFor(COMPANY_A);
        cleanupFor(COMPANY_B);
        TenantContext.clear();
        notificationSpy.reset();
    }

    private void cleanupFor(UUID companyId) {
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_REQUESTER);
            requestRepo.deleteAllInBatch();
            ruleRepo.deleteAllInBatch();
        });
    }

    private void asTenant(UUID companyId, UUID userId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(userId);
    }

    @Nested
    @DisplayName("Règle 1 — Création de règle + doublon → 409")
    class CreationRegle {

        @Test
        @DisplayName("Créer une règle OK ; recréer avec même actionType active=true → 409")
        void duplicateActiveRuleThrows409() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN", "OWNER"), 1);

            assertThatThrownBy(() ->
                service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                    new BigDecimal("75000"), List.of("ADMIN"), 1))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("APPROVAL_RULE_ALREADY_EXISTS");
        }
    }

    @Nested
    @DisplayName("Règle 2 — evaluate sous le seuil → auto-approved")
    class SousSeuil {

        @Test
        @DisplayName("Montant égal au seuil → auto-approved (≤ seuil)")
        void amountEqualThresholdAutoApproved() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("50000"), List.of("admin@jo.dev"));

            assertThat(result.autoApproved()).isTrue();
            assertThat(result.requestId()).isNull();
            assertThat(requestRepo.count()).isZero();
        }

        @Test
        @DisplayName("Montant strictement inférieur au seuil → auto-approved")
        void amountBelowThresholdAutoApproved() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("49999.99"), List.of());

            assertThat(result.autoApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("Règle 3 — evaluate au-dessus du seuil → request PENDING + notification")
    class DessusSeuil {

        @Test
        @DisplayName("Montant > seuil → request PENDING créée + notification envoyée")
        void amountAboveThresholdCreatesPendingRequest() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN", "OWNER"), 1);

            UUID resourceId = UUID.randomUUID();
            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", resourceId,
                new BigDecimal("75000"), List.of("admin1@jo.dev", "admin2@jo.dev"));

            assertThat(result.autoApproved()).isFalse();
            assertThat(result.requestId()).isNotNull();

            ApprovalRequest saved = requestRepo.findById(result.requestId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ApprovalStatus.PENDING);
            assertThat(saved.getRequestedBy()).isEqualTo(USER_REQUESTER);
            assertThat(saved.getAmount()).isEqualByComparingTo("75000");
            assertThat(saved.getResourceType()).isEqualTo("JournalEntry");
            assertThat(saved.getResourceId()).isEqualTo(resourceId);

            // Vérifier que 2 notifications ont été envoyées (une par email d'approbateur)
            assertThat(notificationSpy.sendCount.get()).isEqualTo(2);
            assertThat(notificationSpy.lastTemplateCode).isEqualTo("approval-requested");
        }
    }

    @Nested
    @DisplayName("Règle 4 — evaluate sans règle active → auto-approved")
    class SansRegle {

        @Test
        @DisplayName("Aucune règle pour ce actionType → auto-approved, aucune request")
        void noRuleMeansAutoApproved() {
            asTenant(COMPANY_A, USER_REQUESTER);
            // Pas de règle créée pour JOURNAL_ENTRY_POST
            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("1000000"), List.of());

            assertThat(result.autoApproved()).isTrue();
            assertThat(requestRepo.count()).isZero();
        }
    }

    @Nested
    @DisplayName("Règle 5 — Auto-approbation refusée (quatre yeux)")
    class QuatreYeux {

        @Test
        @DisplayName("requestedBy == decidedBy sur approve → 403")
        void selfApprovalForbidden() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            // USER_REQUESTER tente d'approuver sa propre demande → 403
            assertThatThrownBy(() -> service.approve(COMPANY_A, result.requestId(),
                USER_REQUESTER, "J'approuve ma propre demande"))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("SELF_APPROVAL_FORBIDDEN");
        }

        @Test
        @DisplayName("requestedBy == decidedBy sur reject → 403")
        void selfRejectForbidden() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            assertThatThrownBy(() -> service.reject(COMPANY_A, result.requestId(),
                USER_REQUESTER, "Je rejette ma propre demande"))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("SELF_APPROVAL_FORBIDDEN");
        }
    }

    @Nested
    @DisplayName("Règle 6 — Approbation OK par un autre utilisateur")
    class ApprobationOK {

        @Test
        @DisplayName("USER_APPROVER approuve la demande de USER_REQUESTER → APPROVED")
        void approveByOtherUserSucceeds() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            ApprovalRequest approved = service.approve(COMPANY_A, result.requestId(),
                USER_APPROVER, "OK");

            assertThat(approved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(approved.getDecidedBy()).isEqualTo(USER_APPROVER);
            assertThat(approved.getDecidedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 7 — Rejet → REJECTED + commentaire obligatoire")
    class Rejet {

        @Test
        @DisplayName("Rejet sans commentaire → 422")
        void rejectWithoutCommentThrows422() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            assertThatThrownBy(() -> service.reject(COMPANY_A, result.requestId(),
                USER_APPROVER, null))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("REJECT_COMMENT_REQUIRED");
        }

        @Test
        @DisplayName("Rejet avec commentaire → REJECTED")
        void rejectWithCommentSucceeds() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            ApprovalRequest rejected = service.reject(COMPANY_A, result.requestId(),
                USER_APPROVER, "Montant excessif, justificatif insuffisant");

            assertThat(rejected.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
            assertThat(rejected.getComment()).isEqualTo("Montant excessif, justificatif insuffisant");
        }
    }

    @Nested
    @DisplayName("Règle 8 — Annulation → CANCELLED (même par le demandeur)")
    class Annulation {

        @Test
        @DisplayName("Le demandeur peut annuler sa propre demande")
        void requesterCanCancelOwnRequest() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            // Le demandeur annule lui-même — autorisé (annulation ≠ approbation/rejet)
            ApprovalRequest cancelled = service.cancel(COMPANY_A, result.requestId(),
                USER_REQUESTER, "Finalement je modifie l'écriture");

            assertThat(cancelled.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("Règle 9 — Re-décision d'une demande déjà décidée → 409")
    class Redecision {

        @Test
        @DisplayName("Approuver une demande déjà APPROVED → 409")
        void approveAlreadyApprovedThrows409() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            service.approve(COMPANY_A, result.requestId(), USER_APPROVER, null);

            assertThatThrownBy(() -> service.approve(COMPANY_A, result.requestId(),
                USER_APPROVER, "encore"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("APPROVAL_REQUEST_ALREADY_DECIDED");
        }
    }

    @Nested
    @DisplayName("Règle 10 — Isolation multi-tenant")
    class IsolationTenant {

        @Test
        @DisplayName("Company B ne peut pas approuver une demande de Company A → 404")
        void companyBCannotApproveCompanyARequest() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            asTenant(COMPANY_B, USER_APPROVER);
            assertThatThrownBy(() -> service.approve(COMPANY_B, result.requestId(),
                USER_APPROVER, "OK"))
                .isInstanceOf(NotFoundException.class);  // §3.9 — 404 pas 403
        }
    }

    @Nested
    @DisplayName("Règle 11 — Notification aux approbateurs éligibles")
    class Notification {

        @Test
        @DisplayName("La notification contient les variables attendues")
        void notificationContainsExpectedVariables() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            notificationSpy.reset();
            service.evaluate(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of("admin@jo.dev"));

            assertThat(notificationSpy.lastTemplateCode).isEqualTo("approval-requested");
            assertThat(notificationSpy.lastTo).isEqualTo("admin@jo.dev");
            assertThat(notificationSpy.lastVariables).containsKeys(
                "actionType", "resourceType", "resourceId", "amount", "requestId", "requestedBy");
            assertThat(notificationSpy.lastVariables.get("actionType")).isEqualTo("JOURNAL_ENTRY_POST");
        }

        @Test
        @DisplayName("Aucun email approbateur → pas de notification, mais pas d'erreur")
        void noApproversNoNotification() {
            asTenant(COMPANY_A, USER_REQUESTER);
            service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 1);

            notificationSpy.reset();
            EvaluateResult result = service.evaluate(COMPANY_A,
                ApprovalActionType.JOURNAL_ENTRY_POST, "JournalEntry", UUID.randomUUID(),
                new BigDecimal("75000"), List.of());

            assertThat(result.autoApproved()).isFalse();
            assertThat(notificationSpy.sendCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Règle 12 — minApprovals > 1 → 422 (non supporté en Phase 4)")
    class MinApprovals {

        @Test
        @DisplayName("Créer une règle avec minApprovals = 2 → OK (Vague 3, item 3.1)")
        void minApprovalsGreaterThanOneAccepted() {
            asTenant(COMPANY_A, USER_REQUESTER);
            var rule = service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                new BigDecimal("50000"), List.of("ADMIN"), 2);
            org.assertj.core.api.Assertions.assertThat(rule.getMinApprovals()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Règle supplémentaire — Validation des rôles approbateurs")
    class ValidationRoles {

        @Test
        @DisplayName("Rôle inconnu → 422")
        void unknownRoleRejected() {
            asTenant(COMPANY_A, USER_REQUESTER);
            assertThatThrownBy(() ->
                service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                    new BigDecimal("50000"), List.of("SUPERHERO"), 1))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("UNKNOWN_ROLE");
        }

        @Test
        @DisplayName("Liste vide de rôles → 422")
        void emptyRolesListRejected() {
            asTenant(COMPANY_A, USER_REQUESTER);
            assertThatThrownBy(() ->
                service.createRule(COMPANY_A, ApprovalActionType.JOURNAL_ENTRY_POST,
                    new BigDecimal("50000"), List.of(), 1))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("REQUIRED_APPROVER_ROLES_REQUIRED");
        }
    }
}

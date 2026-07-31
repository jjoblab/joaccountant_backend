package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.notifications.dto.CreateAlertRuleRequest;
import jo.accountant.notifications.dto.NotificationResponse;
import jo.accountant.notifications.dto.UpdatePreferencesRequest;
import jo.accountant.notifications.entity.AlertRule;
import jo.accountant.notifications.entity.AlertType;
import jo.accountant.notifications.entity.NotificationPreference;
import jo.accountant.notifications.repository.AlertRuleRepository;
import jo.accountant.notifications.repository.NotificationPreferenceRepository;
import jo.accountant.notifications.repository.NotificationRepository;
import jo.accountant.notifications.service.NotificationsService;
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
 * Tests d'intégration du module {@code notifications} — Phase 15.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, NotificationsIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@java.lang.SuppressWarnings("deprecation")  // v2.5.2 — markAsRead déprécié, test valide la rétro-compat
class NotificationsIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @TestConfiguration
    static class TestConfig {
        // Ne PAS override NotificationChannelPort — on veut tester l'impl de NotificationsService
    }

    @Autowired private NotificationsService service;
    @Autowired private NotificationRepository notifRepo;
    @Autowired private NotificationPreferenceRepository prefRepo;
    @Autowired private AlertRuleRepository alertRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            notifRepo.deleteAll();
            prefRepo.deleteAll();
            alertRepo.deleteAll();
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 1 — sendEmail crée une notification persistée")
    class SendEmail {
        @Test
        @DisplayName("sendEmail → notification EMAIL créée avec status SENT")
        void sendEmailCreatesNotification() {
            asTenant(COMPANY_A);
            service.sendEmail("test@jo.dev", "approval-requested",
                Map.of("actionType", "JOURNAL_ENTRY_POST", "amount", "75000"));

            List<NotificationResponse> notifs = service.listNotifications(USER_X);
            assertThat(notifs).hasSize(1);
            assertThat(notifs.get(0).type()).isEqualTo("approval-requested");
            assertThat(notifs.get(0).channel().name()).isEqualTo("EMAIL");
            assertThat(notifs.get(0).status().name()).isEqualTo("SENT");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Notification in-app")
    class InAppNotification {
        @Test
        @DisplayName("createInAppNotification → notification PENDING créée")
        void createInAppNotification() {
            asTenant(COMPANY_A);
            service.createInAppNotification(COMPANY_A, USER_X, "low-stock",
                Map.of("sku", "SKU-001", "currentStock", "10", "reorderThreshold", "50"));

            List<NotificationResponse> notifs = service.listNotifications(USER_X);
            assertThat(notifs).hasSize(1);
            assertThat(notifs.get(0).type()).isEqualTo("low-stock");
            assertThat(notifs.get(0).status().name()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("markAsRead → status READ + readAt non null")
        void markAsRead() {
            asTenant(COMPANY_A);
            var notif = service.createInAppNotification(COMPANY_A, USER_X, "test", null);
            service.markAsRead(notif.getId(), USER_X);

            List<NotificationResponse> notifs = service.listNotifications(USER_X);
            assertThat(notifs.get(0).status().name()).isEqualTo("READ");
            assertThat(notifs.get(0).readAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 3 — Préférences")
    class Preferences {
        @Test
        @DisplayName("updatePreferences → emailEnabled=false")
        void updatePreferences() {
            asTenant(COMPANY_A);
            service.updatePreferences(USER_X, COMPANY_A,
                new UpdatePreferencesRequest("approval-requested", false, null));

            List<NotificationPreference> prefs = service.getPreferences(USER_X, COMPANY_A);
            assertThat(prefs).hasSize(1);
            assertThat(prefs.get(0).isEmailEnabled()).isFalse();
            assertThat(prefs.get(0).isInAppEnabled()).isTrue();  // non modifié → reste true
        }
    }

    @Nested
    @DisplayName("Règle 4 — Règles d'alerte")
    class AlertRules {
        @Test
        @DisplayName("Créer une règle LOW_STOCK + vérifier isAlertActive")
        void createAlertRule() {
            asTenant(COMPANY_A);
            AlertRule rule = service.createAlertRule(COMPANY_A,
                new CreateAlertRuleRequest(AlertType.LOW_STOCK, null, true));
            assertThat(rule.getId()).isNotNull();
            assertThat(rule.getType()).isEqualTo(AlertType.LOW_STOCK);
            assertThat(rule.isActive()).isTrue();

            assertThat(service.isAlertActive(COMPANY_A, AlertType.LOW_STOCK)).isTrue();
            assertThat(service.isAlertActive(COMPANY_A, AlertType.INVOICE_OVERDUE)).isFalse();
        }
    }

    @Nested
    @DisplayName("Règle 5 — NotificationsService implémente NotificationChannelPort")
    class PortImpl {
        @Test
        @DisplayName("NotificationsService est bien le bean NotificationChannelPort")
        void isNotificationChannelPort() {
            // Si le contexte Spring démarre, c'est que NotificationsService est bien injecté
            // partout où NotificationChannelPort est attendu (AuthService, CompanyService, etc.)
            assertThat(service).isInstanceOf(NotificationChannelPort.class);
        }
    }
}

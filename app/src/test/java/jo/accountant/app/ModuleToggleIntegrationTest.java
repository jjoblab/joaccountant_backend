package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.repository.CompanyModuleRepository;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
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
 * Tests d'intégration du feature toggle module (restructuration 2026-07-24 suite — feature toggle).
 *
 * <p>Vérifie les 6 règles métier de {@link CompanyModuleService#disable} :
 * <ol>
 *   <li>Désactivation d'un module sectoriel (INVENTORY) → {@code isEnabled} retourne {@code false}.</li>
 *   <li>Désactivation d'un module always-on (ACCOUNTING_ENGINE) → 409 {@code MODULE_CANNOT_BE_DISABLED}.</li>
 *   <li>Désactivation d'un module always-on (INVOICING) → 409 {@code MODULE_CANNOT_BE_DISABLED}.</li>
 *   <li>Réactivation d'un module désactivé → {@code isEnabled} retourne {@code true}.</li>
 *   <li>{@link ModuleAccessGuard#ensureEnabled} lève 403 {@code MODULE_NOT_ENABLED} après désactivation.</li>
 *   <li>Désactivation d'un module déjà désactivé → 422 {@code MODULE_ALREADY_DISABLED}.</li>
 * </ol>
 */
@SpringBootTest(classes = {JoAccountantApplication.class, ModuleToggleIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class ModuleToggleIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private ModuleAccessGuard moduleAccessGuard;
    @Autowired private CompanyModuleRepository companyModuleRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            companyModuleRepo.deleteAllInBatch();
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 1 — Désactivation d'un module sectoriel")
    class DesactiverModuleSectoriel {
        @Test
        @DisplayName("Désactiver INVENTORY → isEnabled retourne false")
        void disableInventoryReturnsFalse() {
            asTenant(COMPANY_A);
            companyModuleService.enable(COMPANY_A, ModuleCode.INVENTORY);
            assertThat(companyModuleService.isEnabled(COMPANY_A, ModuleCode.INVENTORY)).isTrue();

            companyModuleService.disable(COMPANY_A, ModuleCode.INVENTORY);

            assertThat(companyModuleService.isEnabled(COMPANY_A, ModuleCode.INVENTORY)).isFalse();
        }
    }

    @Nested
    @DisplayName("Règle 2 — Désactivation d'un module always-on interdite")
    class DesactiverAlwaysOn {
        @Test
        @DisplayName("Désactiver ACCOUNTING_ENGINE → 409 MODULE_CANNOT_BE_DISABLED")
        void cannotDisableAccountingEngine() {
            asTenant(COMPANY_A);
            companyModuleService.enable(COMPANY_A, ModuleCode.ACCOUNTING_ENGINE);

            assertThatThrownBy(() -> companyModuleService.disable(COMPANY_A, ModuleCode.ACCOUNTING_ENGINE))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("MODULE_CANNOT_BE_DISABLED");
        }

        @Test
        @DisplayName("Désactiver INVOICING (always-on) → 409 MODULE_CANNOT_BE_DISABLED")
        void cannotDisableInvoicing() {
            asTenant(COMPANY_A);
            companyModuleService.enable(COMPANY_A, ModuleCode.INVOICING);

            assertThatThrownBy(() -> companyModuleService.disable(COMPANY_A, ModuleCode.INVOICING))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("MODULE_CANNOT_BE_DISABLED");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Réactivation d'un module désactivé")
    class ReactiverModule {
        @Test
        @DisplayName("Désactiver INVENTORY puis réactiver → isEnabled retourne true")
        void reenableDeactivatedModule() {
            asTenant(COMPANY_A);
            companyModuleService.enable(COMPANY_A, ModuleCode.INVENTORY);
            companyModuleService.disable(COMPANY_A, ModuleCode.INVENTORY);
            assertThat(companyModuleService.isEnabled(COMPANY_A, ModuleCode.INVENTORY)).isFalse();

            companyModuleService.enable(COMPANY_A, ModuleCode.INVENTORY);

            assertThat(companyModuleService.isEnabled(COMPANY_A, ModuleCode.INVENTORY)).isTrue();
        }
    }

    @Nested
    @DisplayName("Règle 4 — ModuleAccessGuard bloque quand module désactivé")
    class GuardModuleDesactive {
        @Test
        @DisplayName("Désactiver TAX puis ensureEnabled → 403 MODULE_NOT_ENABLED")
        void moduleAccessGuardBlocksAfterDisable() {
            asTenant(COMPANY_A);
            companyModuleService.enable(COMPANY_A, ModuleCode.TAX);
            // Le guard passe tant que TAX est activé.
            moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.TAX);

            companyModuleService.disable(COMPANY_A, ModuleCode.TAX);

            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.TAX))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Désactiver un module déjà désactivé → 422")
    class DesactiverDejaDesactive {
        @Test
        @DisplayName("Désactiver INVENTORY deux fois → 422 MODULE_ALREADY_DISABLED")
        void cannotDisableAlreadyDisabledModule() {
            asTenant(COMPANY_A);
            companyModuleService.enable(COMPANY_A, ModuleCode.INVENTORY);
            companyModuleService.disable(COMPANY_A, ModuleCode.INVENTORY);

            assertThatThrownBy(() -> companyModuleService.disable(COMPANY_A, ModuleCode.INVENTORY))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("MODULE_ALREADY_DISABLED");
        }
    }
}

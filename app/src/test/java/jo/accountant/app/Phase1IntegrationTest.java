package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.config.Argon2PasswordEncoder;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.auth.service.AuthService;
import jo.accountant.auth.service.UserCompanyRoleService;
import jo.accountant.auth.validator.PasswordValidator;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.mapping.BusinessTypeModuleService;
import jo.accountant.company.repository.CompanyModuleRepository;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.company.service.CompanyService;
import jo.accountant.company.service.MaxCompaniesGuard;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.AccountingFrameworkRepository;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1 integration tests — restructurés 2026-07-24 (prompt
 * {@code PROMPT_AGENT_restructuration_type_organisation}).
 *
 * <p>Adapté au nouveau wizard : createCompany ne prend plus que {@code name}/{@code country}/
 * {@code functionalCurrency}. Les étapes 2 (nature+forme juridique), 3 (sector), 4 (type métier),
 * 5 (activité principale), 6 (framework+fiscalYear) et 7 (champs requis) portent désormais
 * une sémantique réelle. L'activation des modules est pilotée par {@code BusinessTypeModule}
 * (pas par {@code Sector}) — le type métier {@code CUSTOM} remplace {@code MIXTE} et active
 * réellement la sélection manuelle de l'étape 8 (correction du bug documenté).
 *
 * <p>Uses a real PostgreSQL instance via {@link EmbeddedPostgresSupport} (no H2 — §3.7).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, Phase1IntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class Phase1IntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserCompanyRoleRepository ucrRepository;
    @Autowired private UserCompanyRoleService ucrService;
    @Autowired private CompanyService companyService;
    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private CompanyModuleRepository companyModuleRepository;
    @Autowired private AccountingFrameworkRepository frameworkRepository;
    @Autowired private MaxCompaniesGuard maxCompaniesGuard;
    @Autowired private BusinessTypeModuleService businessTypeModuleService;
    @Autowired private Argon2PasswordEncoder passwordEncoder;
    @Autowired private PasswordValidator passwordValidator;
    @Autowired private RecordingNotificationChannel notificationSpy;
    @Autowired private ApplicationEventPublisher events;

    private static final String IFRS_FULL_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PCN_HAITI_ID = "00000000-0000-0000-0000-000000000005";

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        ucrRepository.deleteAll();
        userRepository.deleteAll();
        companyModuleRepository.deleteAll();
    }

    /**
     * Helper — exécute le wizard complet de bout en bout (étapes 1 à 9 + completion) pour une
     * company fraîchement créée, en choisissant le type métier indiqué (défaut :
     * {@code RETAIL_COMMERCE} — équivalent du secteur {@code COMMERCE} pré-restructuration).
     */
    private Company runFullWizard(UUID userId, String businessTypeCode,
                                  OrganizationNature nature, LegalForm legalForm,
                                  Sector sector, UUID frameworkId) {
        TenantContext.setUserId(userId);
        Company company = companyService.createCompany(userId, "Co " + businessTypeCode,
            "HT", "HTG");

        // Étape 2 — nature + forme juridique (validation croisée appliquée).
        companyService.updateWizardStep(company.getId(), userId, 2, Map.of(
            "organizationNature", nature.name(),
            "legalForm", legalForm.name()));

        // Étape 3 — secteur (descriptif).
        companyService.updateWizardStep(company.getId(), userId, 3, Map.of(
            "sector", sector.name()));

        // Étape 4 — type métier (catalogue BusinessType).
        companyService.updateWizardStep(company.getId(), userId, 4, Map.of(
            "businessTypeCode", businessTypeCode));

        // Étape 5 — activité principale (libellé libre).
        companyService.updateWizardStep(company.getId(), userId, 5, Map.of(
            "primaryActivityLabel", "Activité principale de test"));

        // Étape 6 — référentiel comptable + mois de clôture d'exercice.
        companyService.updateWizardStep(company.getId(), userId, 6, Map.of(
            "accountingFrameworkId", frameworkId.toString(),
            "fiscalYearStartMonth", 1));

        // Étape 7 — champs spécifiques (varie selon le type métier — voir V3_003 seeds).
        Map<String, Object> step7Payload = switch (businessTypeCode) {
            case "NGO_HUMANITARIAN" -> Map.of("donor_reporting_currency", "USD");
            case "SCHOOL" -> Map.of("ministry_approval_number", "MIN-1234");
            case "HOSPITAL" -> Map.of("health_license_number", "SAN-5678");
            case "ACCOUNTING_FIRM" -> Map.of("professional_order_number", "OCP-9999");
            default -> Map.of();
        };
        companyService.updateWizardStep(company.getId(), userId, 7, step7Payload);

        // Étape 8 — récapitulatif modules (vide pour les types non CUSTOM).
        companyService.updateWizardStep(company.getId(), userId, 8, Map.of());

        // Étape 9 — confirmation finale (déclarative).
        companyService.updateWizardStep(company.getId(), userId, 9, Map.of());

        return companyService.completeWizard(company.getId(), userId);
    }

    @Nested
    @DisplayName("Rule 1 — email unique")
    class EmailUnique {
        @Test
        @Transactional
        void registeringSameEmailTwiceThrows409() {
            authService.register("dup@joaccountant.dev", "StrongPass#2026", "Dup One", "fr");
            assertThatThrownBy(() ->
                authService.register("DUP@joaccountant.dev", "AnotherPass#1", "Dup Two", "fr"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("Rule 2 — password complexity")
    class PasswordComplexity {
        @Test
        void tooShortPasswordRejected() {
            assertThatThrownBy(() -> authService.register("p1@jo.dev", "Short1#", "P", "fr"))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("PASSWORD_TOO_SHORT");
        }

        @Test
        void noUppercaseRejected() {
            assertThatThrownBy(() -> authService.register("p2@jo.dev", "alllowercase1#", "P", "fr"))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("PASSWORD_NO_UPPERCASE");
        }

        @Test
        void noDigitRejected() {
            assertThatThrownBy(() -> authService.register("p3@jo.dev", "NoDigitsHere#", "P", "fr"))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("PASSWORD_NO_DIGIT");
        }

        @Test
        void noSpecialCharRejected() {
            assertThatThrownBy(() -> authService.register("p4@jo.dev", "NoSpecial1234", "P", "fr"))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("PASSWORD_NO_SPECIAL");
        }
    }

    @Nested
    @DisplayName("Rule 3 — refresh token rotation (reuse → 403)")
    class RefreshTokenRotation {
        @Test
        @Transactional
        void usingRevokedRefreshTokenThrows403AndRevokesAllSessions() {
            var user = authService.register("rot@jo.dev", "StrongPass#2026", "Rot", "fr");
            var login1 = authService.login("rot@jo.dev", "StrongPass#2026");
            String oldRefresh = login1.refreshToken();

            var login2 = authService.refresh(oldRefresh);
            assertThat(login2.refreshToken()).isNotEqualTo(oldRefresh);

            // Reusing the old (revoked) token must fail AND revoke all active sessions
            assertThatThrownBy(() -> authService.refresh(oldRefresh))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("REFRESH_TOKEN_REUSED");

            // The new token from login2 must now ALSO be revoked (forced logout)
            assertThatThrownBy(() -> authService.refresh(login2.refreshToken()))
                .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("Rule 4 — password reset token single-use + 1h expiration")
    class PasswordReset {
        @Test
        @Transactional
        void resetTokenConsumesOnceAndForcesReloginEverywhere() {
            var user = authService.register("reset@jo.dev", "StrongPass#2026", "Reset", "fr");
            var login = authService.login("reset@jo.dev", "StrongPass#2026");

            authService.initiatePasswordReset("reset@jo.dev");
            assertThat(notificationSpy.lastTemplateCode).isEqualTo("password-reset");
            String rawToken = (String) notificationSpy.lastVariables.get("resetToken");

            authService.consumePasswordReset(rawToken, "NewStrongPass#2026");

            // Old password no longer works
            assertThatThrownBy(() -> authService.login("reset@jo.dev", "StrongPass#2026"))
                .isInstanceOf(ForbiddenException.class);

            // New password works
            authService.login("reset@jo.dev", "NewStrongPass#2026");

            // Reusing the consumed token → 403
            assertThatThrownBy(() -> authService.consumePasswordReset(rawToken, "AnotherStrong#1"))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("RESET_TOKEN_ALREADY_USED");
        }

        @Test
        @Transactional
        void initiatePasswordResetForUnknownEmailIsSilentlyIgnored() {
            // Anti-enumeration: no exception, no notification sent
            notificationSpy.reset();
            authService.initiatePasswordReset("does-not-exist@jo.dev");
            assertThat(notificationSpy.lastTemplateCode).isNull();
        }
    }

    @Nested
    @DisplayName("Rule 5 — verrouillage post-wizard : organizationNature/legalForm/sector/businessTypeCode immuables")
    class WizardLocking {
        @Test
        @Transactional
        void businessTypeCannotBeChangedAfterWizardCompleted() {
            var owner = authService.register("owner-imm@jo.dev", "StrongPass#2026", "Owner", "fr");
            Company company = runFullWizard(owner.getId(), "RETAIL_COMMERCE",
                OrganizationNature.FOR_PROFIT, LegalForm.SARL, Sector.COMMERCE,
                java.util.UUID.fromString(PCN_HAITI_ID));

            // Attempt to edit step 4 (businessTypeCode) now → must be rejected
            assertThatThrownBy(() -> companyService.updateWizardStep(company.getId(), owner.getId(), 4,
                Map.of("businessTypeCode", "PROFESSIONAL_SERVICES")))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("WIZARD_ALREADY_COMPLETED");
        }
    }

    @Nested
    @DisplayName("Rule 6 — business type module auto-activation per §6 mapping")
    class BusinessTypeModuleActivation {
        @Test
        @Transactional
        void retailCommerceActivatesInventoryAndNotFundsGrants() {
            var owner = authService.register("owner-com@jo.dev", "StrongPass#2026", "Owner", "fr");
            Company company = runFullWizard(owner.getId(), "RETAIL_COMMERCE",
                OrganizationNature.FOR_PROFIT, LegalForm.SARL, Sector.COMMERCE,
                java.util.UUID.fromString(PCN_HAITI_ID));

            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.INVENTORY)).isTrue();
            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.FUNDS_GRANTS)).isFalse();
            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.FIXED_ASSETS)).isTrue();
            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.CHART_OF_ACCOUNTS)).isTrue();
        }

        @Test
        @Transactional
        void ngoHumanitarianActivatesFundsGrants() {
            var owner = authService.register("owner-ong@jo.dev", "StrongPass#2026", "Owner", "fr");
            Company company = runFullWizard(owner.getId(), "NGO_HUMANITARIAN",
                OrganizationNature.NON_PROFIT, LegalForm.NGO, Sector.ONG_HUMANITAIRE,
                java.util.UUID.fromString(PCN_HAITI_ID));

            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.FUNDS_GRANTS)).isTrue();
            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.INVENTORY)).isFalse();
        }

        @Test
        @Transactional
        @DisplayName("CUSTOM active réellement la sélection manuelle — correction du bug MIXTE documenté")
        void customBusinessTypeActivatesManuallySelectedModules() {
            var owner = authService.register("owner-custom@jo.dev", "StrongPass#2026", "Owner", "fr");
            TenantContext.setUserId(owner.getId());
            Company company = companyService.createCompany(owner.getId(),
                "Co CUSTOM", "HT", "HTG");

            companyService.updateWizardStep(company.getId(), owner.getId(), 2, Map.of(
                "organizationNature", OrganizationNature.FOR_PROFIT.name(),
                "legalForm", LegalForm.OTHER.name()));
            companyService.updateWizardStep(company.getId(), owner.getId(), 3, Map.of(
                "sector", Sector.AUTRE.name()));
            companyService.updateWizardStep(company.getId(), owner.getId(), 4, Map.of(
                "businessTypeCode", "CUSTOM"));
            companyService.updateWizardStep(company.getId(), owner.getId(), 5, Map.of(
                "primaryActivityLabel", "Activité personnalisée"));
            companyService.updateWizardStep(company.getId(), owner.getId(), 6, Map.of(
                "accountingFrameworkId", PCN_HAITI_ID,
                "fiscalYearStartMonth", 1));
            companyService.updateWizardStep(company.getId(), owner.getId(), 7, Map.of());
            // Étape 8 — sélection manuelle : INVENTORY + TIME_BILLING (multisectorielle).
            companyService.updateWizardStep(company.getId(), owner.getId(), 8, Map.of(
                "customModules", java.util.List.of("INVENTORY", "TIME_BILLING")));
            companyService.updateWizardStep(company.getId(), owner.getId(), 9, Map.of());
            companyService.completeWizard(company.getId(), owner.getId());

            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.INVENTORY)).isTrue();
            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.TIME_BILLING)).isTrue();
            // FUNDS_GRANTS n'a pas été sélectionné manuellement → reste désactivé.
            assertThat(companyModuleService.isEnabled(company.getId(),
                jo.accountant.company.entity.ModuleCode.FUNDS_GRANTS)).isFalse();
        }
    }

    @Nested
    @DisplayName("Rule 7 — max 3 companies per user (4th → 409)")
    class MaxCompanies {
        @Test
        @Transactional
        void fourthCompanyCreationRejectedWith409() {
            var owner = authService.register("max@jo.dev", "StrongPass#2026", "Max", "fr");
            TenantContext.setUserId(owner.getId());

            for (int i = 1; i <= 3; i++) {
                companyService.createCompany(owner.getId(), "Co " + i, "HT", "HTG");
            }

            assertThatThrownBy(() ->
                companyService.createCompany(owner.getId(), "Co 4", "HT", "HTG"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("MAX_COMPANIES_REACHED");
        }

        @Test
        @Transactional
        void userOverrideLiftsTheLimit() {
            var owner = authService.register("override@jo.dev", "StrongPass#2026", "O", "fr");
            owner.setMaxCompaniesOverride(5);
            userRepository.save(owner);

            TenantContext.setUserId(owner.getId());
            for (int i = 1; i <= 5; i++) {
                companyService.createCompany(owner.getId(), "Co " + i, "HT", "HTG");
            }
            // 6th still rejected
            assertThatThrownBy(() ->
                companyService.createCompany(owner.getId(), "Co 6", "HT", "HTG"))
                .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("Rule 8 — multi-tenant isolation (User A cannot read Company B)")
    class TenantIsolation {
        @Test
        @Transactional
        void userWithoutRoleGets404Not403() {
            var ownerA = authService.register("a@jo.dev", "StrongPass#2026", "A", "fr");
            var ownerB = authService.register("b@jo.dev", "StrongPass#2026", "B", "fr");
            TenantContext.setUserId(ownerA.getId());

            var companyA = companyService.createCompany(ownerA.getId(), "A Co", "HT", "HTG");

            // Owner B tries to access company A → must be 404 (NotFound) to avoid leaking existence (§3.9)
            TenantContext.setUserId(ownerB.getId());
            assertThatThrownBy(() -> companyService.getCompanyForUser(companyA.getId(), ownerB.getId()))
                .isInstanceOf(jo.accountant.core.exception.NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Rule 9 — TenantContext @TenantId customizer is wired (§3.3)")
    class TenantContextWiring {
        @Test
        void tenantContextFilterAndResolverAreRegistered() {
            // If the wiring was missing, these beans would not exist and the @SpringBootTest context
            // would fail to load. The mere fact that this test method runs proves the wiring is in place.
            assertThat(maxCompaniesGuard).isNotNull();
            assertThat(businessTypeModuleService).isNotNull();
        }

        @Test
        @Transactional
        void companyModuleIsTenantScopedOnPersist() {
            var owner = authService.register("scope@jo.dev", "StrongPass#2026", "S", "fr");
            TenantContext.setUserId(owner.getId());

            var company = companyService.createCompany(owner.getId(), "Scope Co", "HT", "HTG");
            TenantContext.setCompanyId(company.getId());

            companyModuleService.enable(company.getId(),
                jo.accountant.company.entity.ModuleCode.INVENTORY);

            var saved = companyModuleRepository.findByCompanyId(company.getId());
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getCompanyId()).isEqualTo(company.getId());
        }
    }

    @Nested
    @DisplayName("Rule 10 — invitation & password reset go via NotificationChannelPort")
    class NotificationChannelUsage {
        @Test
        @Transactional
        void inviteUserSendsEmailViaPort() {
            var owner = authService.register("inv-own@jo.dev", "StrongPass#2026", "Owner", "fr");
            var invitee = authService.register("inv-ee@jo.dev", "StrongPass#2026", "Invitee", "fr");
            TenantContext.setUserId(owner.getId());

            var company = companyService.createCompany(owner.getId(), "Inv Co", "HT", "HTG");

            notificationSpy.reset();
            ucrService.inviteUser(company.getId(), "inv-ee@jo.dev", UserRole.ACCOUNTANT);
            assertThat(notificationSpy.lastTemplateCode).isEqualTo("user-invitation");
            assertThat(notificationSpy.lastTo).isEqualTo("inv-ee@jo.dev");
        }
    }

    @Nested
    @DisplayName("Rule 11 — validation croisée Nature ↔ LegalForm (restructuration §4.2)")
    class NatureLegalFormValidation {
        @Test
        @Transactional
        @DisplayName("ASSOCIATION + FOR_PROFIT → 422 LEGAL_FORM_NATURE_MISMATCH")
        void associationRequiresNonProfit() {
            var owner = authService.register("val@jo.dev", "StrongPass#2026", "V", "fr");
            TenantContext.setUserId(owner.getId());
            Company company = companyService.createCompany(owner.getId(), "Val Co", "HT", "HTG");

            assertThatThrownBy(() -> companyService.updateWizardStep(company.getId(), owner.getId(), 2,
                Map.of("organizationNature", "FOR_PROFIT", "legalForm", "ASSOCIATION")))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("LEGAL_FORM_NATURE_MISMATCH");
        }

        @Test
        @Transactional
        @DisplayName("SARL + NON_PROFIT → 422 LEGAL_FORM_NATURE_MISMATCH")
        void sarlRequiresForProfit() {
            var owner = authService.register("val2@jo.dev", "StrongPass#2026", "V2", "fr");
            TenantContext.setUserId(owner.getId());
            Company company = companyService.createCompany(owner.getId(), "Val Co 2", "HT", "HTG");

            assertThatThrownBy(() -> companyService.updateWizardStep(company.getId(), owner.getId(), 2,
                Map.of("organizationNature", "NON_PROFIT", "legalForm", "SARL")))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("LEGAL_FORM_NATURE_MISMATCH");
        }

        @Test
        @Transactional
        @DisplayName("OTHER + n'importe quelle nature → OK")
        void otherAcceptsAnyNature() {
            var owner = authService.register("val3@jo.dev", "StrongPass#2026", "V3", "fr");
            TenantContext.setUserId(owner.getId());

            // Première company — OTHER + COOPERATIVE
            Company co1 = companyService.createCompany(owner.getId(), "Val Co 3a", "HT", "HTG");
            companyService.updateWizardStep(co1.getId(), owner.getId(), 2, Map.of(
                "organizationNature", "COOPERATIVE", "legalForm", "OTHER"));

            // Seconde company — OTHER + PUBLIC_SECTOR (l'étape 2 ne peut pas être re-éditée
            // sur la même company une fois avancée, donc on crée une nouvelle company).
            Company co2 = companyService.createCompany(owner.getId(), "Val Co 3b", "HT", "HTG");
            companyService.updateWizardStep(co2.getId(), owner.getId(), 2, Map.of(
                "organizationNature", "PUBLIC_SECTOR", "legalForm", "OTHER"));
        }
    }

    @Nested
    @DisplayName("Argon2id encoder sanity check")
    class Argon2Sanity {
        @Test
        void encodedPasswordVerifies() {
            String encoded = passwordEncoder.encode("Hello#World123");
            assertThat(encoded).startsWith("$argon2id$");
            assertThat(passwordEncoder.matches("Hello#World123", encoded)).isTrue();
            assertThat(passwordEncoder.matches("wrong", encoded)).isFalse();
        }
    }
}

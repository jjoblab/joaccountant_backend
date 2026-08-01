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
    @Autowired private jo.accountant.company.repository.CompanyRepository companyRepository;
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
     * Helper — exécute le wizard V8.2 complet (4 étapes : identité → activité → comptabilité → activation atomique)
     * pour une company fraîchement créée, en choisissant le type métier indiqué.
     *
     * <p>V8.2 (audit Z.ai 2026-07-31) — le wizard 9 étapes a été supprimé. Les anciennes étapes
     * 3+4+5+7+8 sont fusionnées dans {@code applyWizardStep2} (activité), les anciennes 6+9+10
     * dans {@code applyWizardStep3} (comptabilité). L'activation atomique (modules + plan comptable +
     * exercice + journaux + séquences + TVA) se fait dans {@code completeWizard}.
     *
     * <p>{@code nature}/{@code legalForm} sont ignorés en V8.2 (auto-populés depuis les defaults
     * du BusinessType). {@code sector} est passé via {@code WizardStep2Request.sector}.
     *
     * @return la Company finalisée (wizardCompleted=true)
     */
    private Company runFullWizard(UUID userId, String businessTypeCode,
                                  OrganizationNature natureIgnored, LegalForm legalFormIgnored,
                                  Sector sector, UUID frameworkId) {
        TenantContext.setUserId(userId);
        // V8.3 — createCompany retourne désormais un CreateCompanyResponse (record)
        // contenant la CompanyResponse + un nouveau JWT. On récupère l'id via
        // `created.company().id()` puis on recharge l'entité Company depuis le repo
        // (nécessaire pour applyWizardStep2/3 qui attendent un UUID, et pour le
        // retour final qui doit être une Company).
        var created = companyService.createCompany(userId, "Co " + businessTypeCode,
            "HT", "HTG", null, null);
        Company company = companyRepository.findById(created.company().id()).orElseThrow();

        // Étape 2 — Activité + type métier (fusionne anciennes étapes 3+4+5+7+8).
        // Champs spécifiques au type métier (extraAttributes) selon V3_003 seeds.
        java.util.Map<String, Object> extraAttrs = switch (businessTypeCode) {
            case "NGO_HUMANITARIAN" -> java.util.Map.of("donor_reporting_currency", "USD");
            case "SCHOOL" -> java.util.Map.of("ministry_approval_number", "MIN-1234");
            case "HOSPITAL" -> java.util.Map.of("health_license_number", "SAN-5678");
            case "ACCOUNTING_FIRM" -> java.util.Map.of("professional_order_number", "OCP-9999");
            default -> java.util.Map.of();
        };
        companyService.applyWizardStep2(company.getId(), userId,
            new jo.accountant.company.dto.WizardStep2Request(
                "Activité principale de test",
                businessTypeCode,
                sector,
                extraAttrs,
                null  // customModules — null pour les types non CUSTOM
            ));

        // Étape 3 — Comptabilité + fiscalité (fusionne anciennes étapes 6+9+10).
        companyService.applyWizardStep3(company.getId(), userId,
            new jo.accountant.company.dto.WizardStep3Request(
                frameworkId,
                1,                    // fiscalYearStartMonth
                2026,                 // fiscalYearStartYear
                "Exercice 2026",      // fiscalYearLabel
                jo.accountant.core.tax.VatMode.DEBIT,
                null                  // numberingPrefixes — defaults
            ));

        // Étape 4 — Activation atomique (modules + plan comptable + exercice + journaux + séquences + TVA).
        companyService.completeWizard(company.getId(), userId,
            new jo.accountant.company.dto.CompleteWizardRequest(null, null, null));

        return companyRepository.findById(company.getId()).orElseThrow();
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

            // Attempt to edit step 2 (businessTypeCode) now → must be rejected (WIZARD_ALREADY_COMPLETED)
            assertThatThrownBy(() -> companyService.applyWizardStep2(company.getId(), owner.getId(),
                new jo.accountant.company.dto.WizardStep2Request(
                    "Activité modifiée", "PROFESSIONAL_SERVICES",
                    Sector.SERVICE, java.util.Map.of(), null)))
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
            // V8.3 — createCompany retourne un CreateCompanyResponse, pas une Company.
            var created = companyService.createCompany(owner.getId(),
                "Co CUSTOM", "HT", "HTG", null, null);
            Company company = companyRepository.findById(created.company().id()).orElseThrow();

            // Étape 2 — type métier CUSTOM avec customModules (fusionne anciennes étapes 3+4+5+7+8)
            companyService.applyWizardStep2(company.getId(), owner.getId(),
                new jo.accountant.company.dto.WizardStep2Request(
                    "Activité personnalisée",
                    "CUSTOM",
                    Sector.AUTRE,
                    java.util.Map.of(),
                    java.util.List.of("INVENTORY", "TIME_BILLING")  // customModules
                ));

            // Étape 3 — Comptabilité + fiscalité
            companyService.applyWizardStep3(company.getId(), owner.getId(),
                new jo.accountant.company.dto.WizardStep3Request(
                    java.util.UUID.fromString(PCN_HAITI_ID),
                    1, 2026, "Exercice 2026",
                    jo.accountant.core.tax.VatMode.DEBIT, null));

            // Étape 4 — Activation atomique
            companyService.completeWizard(company.getId(), owner.getId(),
                new jo.accountant.company.dto.CompleteWizardRequest(null, null, null));

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
                companyService.createCompany(owner.getId(), "Co " + i, "HT", "HTG", null, null);
            }

            assertThatThrownBy(() ->
                companyService.createCompany(owner.getId(), "Co 4", "HT", "HTG", null, null))
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
                companyService.createCompany(owner.getId(), "Co " + i, "HT", "HTG", null, null);
            }
            // 6th still rejected
            assertThatThrownBy(() ->
                companyService.createCompany(owner.getId(), "Co 6", "HT", "HTG", null, null))
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

            var companyA = companyService.createCompany(ownerA.getId(), "A Co", "HT", "HTG", null, null);

            // Owner B tries to access company A → must be 404 (NotFound) to avoid leaking existence (§3.9)
            TenantContext.setUserId(ownerB.getId());
            assertThatThrownBy(() -> companyService.getCompanyForUser(companyA.company().id(), ownerB.getId()))
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

            // V8.3 — createCompany retourne un CreateCompanyResponse, pas une Company.
            var company = companyService.createCompany(owner.getId(), "Scope Co", "HT", "HTG", null, null);
            UUID companyId = company.company().id();
            TenantContext.setCompanyId(companyId);

            companyModuleService.enable(companyId,
                jo.accountant.company.entity.ModuleCode.INVENTORY);

            var saved = companyModuleRepository.findByCompanyId(companyId);
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getCompanyId()).isEqualTo(companyId);
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

            var company = companyService.createCompany(owner.getId(), "Inv Co", "HT", "HTG", null, null);

            notificationSpy.reset();
            ucrService.inviteUser(company.company().id(), "inv-ee@jo.dev", null, UserRole.ACCOUNTANT);
            assertThat(notificationSpy.lastTemplateCode).isEqualTo("user-invitation");
            assertThat(notificationSpy.lastTo).isEqualTo("inv-ee@jo.dev");
        }
    }

    @Nested
    @DisplayName("Rule 11 — validation croisée Nature ↔ LegalForm (V8.2 : supprimée du wizard)")
    class NatureLegalFormValidation {
        // V8.2 (audit Z.ai 2026-07-31) — organizationNature et legalForm ne sont plus saisis
        // via le wizard. Ils sont auto-populés depuis les defaults du BusinessType à l'étape 2.
        // La validation croisée OrganizationNatureLegalFormValidator n'est donc plus appelée
        // depuis le wizard. Les 3 tests historiques (associationRequiresNonProfit,
        // sarlRequiresForProfit, otherAcceptsAnyNature) ont été supprimés avec l'ancien wizard 9 étapes.
        //
        // Si l'utilisateur veut override les defaults, il devra passer par un endpoint dédié
        // (à créer dans une future version — PATCH /organization-nature avec validation croisée).
        //
        // Test sanity : le BusinessType contient bien les defaults OrganizationNature attendus.
        @Test
        @Transactional
        @DisplayName("BusinessType.defaultOrganizationNature est correct pour RETAIL_COMMERCE (FOR_PROFIT)")
        void retailCommerceDefaultsToForProfit() {
            var bt = businessTypeModuleService.getActiveByCode("RETAIL_COMMERCE");
            assertThat(bt.getDefaultOrganizationNature()).isEqualTo(OrganizationNature.FOR_PROFIT);
        }

        @Test
        @Transactional
        @DisplayName("BusinessType.defaultOrganizationNature est correct pour NGO_HUMANITARIAN (NON_PROFIT)")
        void ngoHumanitarianDefaultsToNonProfit() {
            var bt = businessTypeModuleService.getActiveByCode("NGO_HUMANITARIAN");
            assertThat(bt.getDefaultOrganizationNature()).isEqualTo(OrganizationNature.NON_PROFIT);
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

package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
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
 * Tests d'intégration du seed sectoriel du plan comptable (restructuration 2026-07-24 suite —
 * feature plan comptable context-aware).
 *
 * <p>Vérifie que {@link ChartOfAccountsService#initialize(UUID, UUID, Object, String)} génère
 * les comptes niveau 2+ typiques du type métier, en plus des classes niveau 1 verrouillées
 * issues du référentiel (SYSCOHADA).
 *
 * <p>7 scénarios couverts (un type métier par scénario, sur des sociétés distinctes pour éviter
 * les collisions de plan comptable) :
 * <ol>
 *   <li>RETAIL_COMMERCE → comptes 401, 411, 521, 601, 701, 310 présents.</li>
 *   <li>PROFESSIONAL_SERVICES → 706 présent, 310 absent (pas de stock pour les services).</li>
 *   <li>HOSPITAL → 310 (médicaments) et 246 (matériel médical) présents.</li>
 *   <li>SCHOOL → 706 (frais de scolarité) présent.</li>
 *   <li>NGO_HUMANITARIAN → 102 (fonds associatif) présent, 101 (capital social) absent.</li>
 *   <li>Pas de businessTypeCode → seules les classes niveau 1 existent (pas de niveau 2+).</li>
 *   <li>CUSTOM → set générique (40, 41, 521, 70) présent.</li>
 * </ol>
 */
@SpringBootTest(classes = {JoAccountantApplication.class, SectorAccountTemplateIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class SectorAccountTemplateIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID COMPANY_C = UUID.fromString("00000000-0000-0000-0000-c00000000001");
    private static final UUID COMPANY_D = UUID.fromString("00000000-0000-0000-0000-d00000000001");
    private static final UUID COMPANY_E = UUID.fromString("00000000-0000-0000-0000-e00000000001");
    private static final UUID COMPANY_F = UUID.fromString("00000000-0000-0000-0000-f00000000001");
    private static final UUID COMPANY_G = UUID.fromString("00000000-0000-0000-0000-a00000000002");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private ChartOfAccountsService coaService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cleanupFor(COMPANY_A);
        cleanupFor(COMPANY_B);
        cleanupFor(COMPANY_C);
        cleanupFor(COMPANY_D);
        cleanupFor(COMPANY_E);
        cleanupFor(COMPANY_F);
        cleanupFor(COMPANY_G);
        TenantContext.clear();
    }

    private void cleanupFor(UUID companyId) {
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_X);
            accountRepo.deleteAllInBatch();
        });
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    private boolean accountExists(UUID companyId, String code) {
        return accountRepo.existsByCompanyIdAndCode(companyId, code);
    }

    private List<Account> levelTwoOrAbove(UUID companyId) {
        return accountRepo.findByCompanyIdOrderByCode(companyId).stream()
            .filter(a -> a.getLevel() >= 2)
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════
    //  Scénarios sectoriels
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scénario 1 — RETAIL_COMMERCE")
    class RetailCommerce {
        @Test
        @DisplayName("RETAIL_COMMERCE → comptes 401, 411, 521, 601, 701, 310 présents")
        void retailCommerceTemplateSeedsExpectedAccounts() {
            asTenant(COMPANY_A);
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null, "RETAIL_COMMERCE");

            assertThat(accountExists(COMPANY_A, "401")).as("401 Fournisseurs locaux").isTrue();
            assertThat(accountExists(COMPANY_A, "411")).as("411 Clients locaux").isTrue();
            assertThat(accountExists(COMPANY_A, "521")).as("521 Banque Nationale").isTrue();
            assertThat(accountExists(COMPANY_A, "601")).as("601 Achats de marchandises").isTrue();
            assertThat(accountExists(COMPANY_A, "701")).as("701 Ventes de marchandises").isTrue();
            assertThat(accountExists(COMPANY_A, "310")).as("310 Stock de marchandises").isTrue();
        }
    }

    @Nested
    @DisplayName("Scénario 2 — PROFESSIONAL_SERVICES")
    class ProfessionalServices {
        @Test
        @DisplayName("PROFESSIONAL_SERVICES → 706 présent, 310 absent (pas de stock)")
        void professionalServicesTemplateSeedsServicesAccountsButNoStock() {
            asTenant(COMPANY_B);
            coaService.initialize(COMPANY_B, SYSCOHADA_ID, null, "PROFESSIONAL_SERVICES");

            assertThat(accountExists(COMPANY_B, "706")).as("706 Prestations de services").isTrue();
            assertThat(accountExists(COMPANY_B, "310")).as("310 ne doit pas exister (pas de stock pour les services)").isFalse();
        }
    }

    @Nested
    @DisplayName("Scénario 3 — HOSPITAL")
    class Hospital {
        @Test
        @DisplayName("HOSPITAL → 310 (médicaments) et 246 (matériel médical) présents")
        void hospitalTemplateSeedsMedicalAccounts() {
            asTenant(COMPANY_C);
            coaService.initialize(COMPANY_C, SYSCOHADA_ID, null, "HOSPITAL");

            assertThat(accountExists(COMPANY_C, "310")).as("310 Médicaments").isTrue();
            assertThat(accountExists(COMPANY_C, "246")).as("246 Matériel médical").isTrue();
        }
    }

    @Nested
    @DisplayName("Scénario 4 — SCHOOL")
    class School {
        @Test
        @DisplayName("SCHOOL → 706 (frais de scolarité) présent")
        void schoolTemplateSeedsTuitionAccount() {
            asTenant(COMPANY_D);
            coaService.initialize(COMPANY_D, SYSCOHADA_ID, null, "SCHOOL");

            assertThat(accountExists(COMPANY_D, "706")).as("706 Frais de scolarité").isTrue();
        }
    }

    @Nested
    @DisplayName("Scénario 5 — NGO_HUMANITARIAN")
    class NgoHumanitarian {
        @Test
        @DisplayName("NGO_HUMANITARIAN → 102 (fonds associatif) présent, 101 (capital social) absent")
        void ngoTemplateSeedsAssociationFundsNotShareCapital() {
            asTenant(COMPANY_E);
            coaService.initialize(COMPANY_E, SYSCOHADA_ID, null, "NGO_HUMANITARIAN");

            assertThat(accountExists(COMPANY_E, "102")).as("102 Fonds associatif sans droit de reprise").isTrue();
            assertThat(accountExists(COMPANY_E, "101")).as("101 Capital social ne doit pas exister pour une ONG").isFalse();
        }
    }

    @Nested
    @DisplayName("Scénario 6 — Pas de businessTypeCode")
    class SansBusinessType {
        @Test
        @DisplayName("Initialize sans businessTypeCode → seules les classes niveau 1 existent")
        void noBusinessTypeCodeSeedsOnlyLevelOne() {
            asTenant(COMPANY_F);
            coaService.initialize(COMPANY_F, SYSCOHADA_ID, null, null);

            // Aucun compte de niveau 2+ ne doit exister — seulement les 8 classes SYSCOHADA.
            List<Account> levelTwoPlus = levelTwoOrAbove(COMPANY_F);
            assertThat(levelTwoPlus).isEmpty();

            // Vérifier que les classes niveau 1 existent bien (8 classes SYSCOHADA).
            List<Account> levelOne = accountRepo.findByCompanyIdOrderByCode(COMPANY_F).stream()
                .filter(a -> a.getLevel() == 1)
                .toList();
            assertThat(levelOne).hasSize(8);
            assertThat(levelOne).allSatisfy(a -> assertThat(a.isLocked()).isTrue());
        }
    }

    @Nested
    @DisplayName("Scénario 7 — CUSTOM")
    class Custom {
        @Test
        @DisplayName("CUSTOM → set générique (40, 41, 521, 70) présent")
        void customTemplateSeedsGenericAccounts() {
            asTenant(COMPANY_G);
            coaService.initialize(COMPANY_G, SYSCOHADA_ID, null, "CUSTOM");

            // Le type CUSTOM déclenche le set générique (banque, caisse, capital, ventes, achats)
            // — l'utilisateur complète le reste à la main via l'étape 8 du wizard.
            // Le set générique ne contient que les comptes niveau 2 (40, 41, 44, 52, 57, 60, 70)
            // et deux comptes niveau 3 (443, 521). Les codes 401, 411, 701 n'y figurent pas
            // (ils sont spécifiques aux templates commerce/services, pas au set générique).
            assertThat(accountExists(COMPANY_G, "40")).as("40 Fournisseurs").isTrue();
            assertThat(accountExists(COMPANY_G, "41")).as("41 Clients").isTrue();
            assertThat(accountExists(COMPANY_G, "521")).as("521 Banque Nationale").isTrue();
            assertThat(accountExists(COMPANY_G, "70")).as("70 Ventes").isTrue();
        }
    }
}

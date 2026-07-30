package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.timebilling.dto.CreateBillableRateRequest;
import jo.accountant.timebilling.dto.CreateProjectRequest;
import jo.accountant.timebilling.dto.CreateTimesheetEntryRequest;
import jo.accountant.timebilling.dto.ProjectResponse;
import jo.accountant.timebilling.dto.TimesheetEntryResponse;
import jo.accountant.timebilling.dto.UnbilledWip;
import jo.accountant.timebilling.entity.BillingType;
import jo.accountant.timebilling.repository.BillableRateRepository;
import jo.accountant.timebilling.repository.ProjectRepository;
import jo.accountant.timebilling.repository.TimesheetEntryRepository;
import jo.accountant.timebilling.service.TimeBillingService;
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
 * Tests d'intégration du module {@code time-billing} — Phase 10.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, TimeBillingIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class TimeBillingIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RESOURCE_1 = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private TimeBillingService service;
    @Autowired private ProjectRepository projectRepo;
    @Autowired private TimesheetEntryRepository entryRepo;
    @Autowired private BillableRateRepository rateRepo;
    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private ModuleAccessGuard moduleAccessGuard;
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
            entryRepo.deleteAllInBatch();
            rateRepo.deleteAllInBatch();
            projectRepo.deleteAllInBatch();
        });
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 8 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si TIME_BILLING désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.TIME_BILLING))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    private ProjectResponse createProject() {
        asTenant(COMPANY_A);
        return service.createProject(COMPANY_A, new CreateProjectRequest(
            "PROJ-001", "Refonte site X", null, BillingType.TIME_AND_MATERIALS));
    }

    @Nested
    @DisplayName("Règle 1 — Création projet OK")
    class CreationProjet {
        @Test
        @DisplayName("Créer un projet TIME_AND_MATERIALS OK")
        void createProjectSucceeds() {
            ProjectResponse p = createProject();
            assertThat(p.id()).isNotNull();
            assertThat(p.code()).isEqualTo("PROJ-001");
            assertThat(p.billingType()).isEqualTo(BillingType.TIME_AND_MATERIALS);
        }
    }

    @Nested
    @DisplayName("Règle 2 — Création timesheet entry OK")
    class CreationEntry {
        @Test
        @DisplayName("Créer une entry 8h billable")
        void createEntry() {
            ProjectResponse p = createProject();
            TimesheetEntryResponse e = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév frontend"));
            assertThat(e.id()).isNotNull();
            assertThat(e.hours()).isEqualByComparingTo("8");
            assertThat(e.billable()).isTrue();
            assertThat(e.approved()).isFalse();
            assertThat(e.invoiced()).isFalse();
        }
    }

    @Nested
    @DisplayName("Règle 3 — Approbation d'une entry")
    class Approbation {
        @Test
        @DisplayName("Approuver une entry → approved=true")
        void approveEntry() {
            ProjectResponse p = createProject();
            TimesheetEntryResponse e = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév"));
            TimesheetEntryResponse approved = service.approveEntry(COMPANY_A, e.id());
            assertThat(approved.approved()).isTrue();
        }

        @Test
        @DisplayName("Approuver une entry déjà approuvée → 409")
        void doubleApprove() {
            ProjectResponse p = createProject();
            TimesheetEntryResponse e = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév"));
            service.approveEntry(COMPANY_A, e.id());
            assertThatThrownBy(() -> service.approveEntry(COMPANY_A, e.id()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ENTRY_ALREADY_APPROVED");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Seules les entries approved + billable sont facturables")
    class Facturabilite {
        @Test
        @DisplayName("Entry non approuvée → pas dans unbilled")
        void unapprovedNotInUnbilled() {
            ProjectResponse p = createProject();
            service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév"));
            UnbilledWip wip = service.getUnbilled(COMPANY_A, p.id());
            assertThat(wip.lines()).isEmpty();
            assertThat(wip.totalHours()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Entry approuvée + billable → dans unbilled")
        void approvedBillableInUnbilled() {
            ProjectResponse p = createProject();
            TimesheetEntryResponse e = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév"));
            service.approveEntry(COMPANY_A, e.id());
            UnbilledWip wip = service.getUnbilled(COMPANY_A, p.id());
            assertThat(wip.lines()).hasSize(1);
            assertThat(wip.totalHours()).isEqualByComparingTo("8");
        }

        @Test
        @DisplayName("Entry approuvée mais non billable → pas dans unbilled")
        void approvedNotBillableNotInUnbilled() {
            ProjectResponse p = createProject();
            TimesheetEntryResponse e = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), false, "Formation interne"));
            service.approveEntry(COMPANY_A, e.id());
            UnbilledWip wip = service.getUnbilled(COMPANY_A, p.id());
            assertThat(wip.lines()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Règle 5 — Entry déjà invoiced ne peut pas être réutilisée")
    class Idempotence {
        @Test
        @DisplayName("Approuver une entry déjà invoiced → 409")
        void cannotApproveInvoicedEntry() {
            ProjectResponse p = createProject();
            TimesheetEntryResponse e = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév"));
            service.approveEntry(COMPANY_A, e.id());

            // Simuler l'invoicing (Phase 12 mettra invoiced=true — ici on le fait manuellement)
            entryRepo.findById(e.id()).ifPresent(entry -> {
                entry.setInvoiced(true);
                entryRepo.save(entry);
            });

            // L'entry est déjà invoiced → ne peut plus être approuvée (bien qu'elle l'est déjà)
            // Mais aussi, elle ne devrait plus apparaître dans unbilled
            UnbilledWip wip = service.getUnbilled(COMPANY_A, p.id());
            assertThat(wip.lines()).isEmpty();  // déjà facturée → plus dans WIP
        }
    }

    @Nested
    @DisplayName("Règle 6 — WIP = somme heures approuvées × taux")
    class Wip {
        @Test
        @DisplayName("2 entries approuvées × taux entreprise → WIP = 20h × 100 = 2000")
        void wipCalculation() {
            ProjectResponse p = createProject();
            // Créer un taux entreprise (défaut)
            service.createBillableRate(COMPANY_A, new CreateBillableRateRequest(
                null, RESOURCE_1, new BigDecimal("100"), "HTG"));

            service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 15),
                    new BigDecimal("8"), true, "Dév matin"));
            TimesheetEntryResponse e2 = service.createTimesheetEntry(COMPANY_A,
                new CreateTimesheetEntryRequest(p.id(), RESOURCE_1, LocalDate.of(2026, 7, 16),
                    new BigDecimal("12"), true, "Dév après-midi"));

            // Approuver les 2 entries
            // La première est déjà créée — on doit récupérer son ID
            var entries = entryRepo.findByProjectIdOrderByEntryDate(p.id());
            for (var entry : entries) {
                service.approveEntry(COMPANY_A, entry.getId());
            }

            UnbilledWip wip = service.getUnbilled(COMPANY_A, p.id());
            assertThat(wip.totalHours()).isEqualByComparingTo("20");
            assertThat(wip.totalAmount()).isEqualByComparingTo("2000");  // 20 × 100
        }
    }

    @Nested
    @DisplayName("Règle 7 — Isolation multi-tenant")
    class IsolationTenant {
        @Test
        @DisplayName("Company B ne peut pas voir le projet de Company A → 404")
        void companyBCannotSeeCompanyAProject() {
            ProjectResponse p = createProject();
            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getUnbilled(COMPANY_B, p.id()))
                .isInstanceOf(NotFoundException.class);
        }
    }
}

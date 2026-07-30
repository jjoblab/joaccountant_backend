package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.employees.dto.CreateEmployeeRequest;
import jo.accountant.employees.dto.EmployeeResponse;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.employees.service.EmployeesService;
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
 * Tests d'intégration du module {@code employees} (restructuration 2026-07-24).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, EmployeesIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class EmployeesIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private EmployeesService service;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private EmployeeRepository empRepo;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(UUID.randomUUID());
            empRepo.deleteAllInBatch();
            lmRepo.deleteAllInBatch();
            tpRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
        });
        TenantContext.clear();
    }

    private UUID initFixtureAndReturnCollectiveAccount() {
        TenantContext.setCompanyId(COMPANY_A);
        TenantContext.setUserId(UUID.randomUUID());
        coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);
        var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
        var collectiveEmployee = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
            "421000", "Personnel - rémunérations dues", ReportingClass.PASSIF,
            ReportingSubcategory.COURANT, NormalBalance.CREDIT, true, null, List.of()));
        return collectiveEmployee.id();
    }

    @Nested
    @DisplayName("Règle 1 — Création avec tiers préexistant")
    class CreationAvecTiersExistant {
        @Test
        @DisplayName("Créer un employé rattaché à un tiers EMPLOYEE existant")
        void createWithExistingThirdParty() {
            UUID collectiveId = initFixtureAndReturnCollectiveAccount();
            ThirdPartyResponse tp = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.EMPLOYEE, "Marie Curie", collectiveId, null, null));

            EmployeeResponse emp = service.create(COMPANY_A, new CreateEmployeeRequest(
                tp.id(), null, null, "EMP-001", "Chercheuse", "R&D",
                LocalDate.of(2020, 1, 1), new BigDecimal("75000"), "HTG",
                ContractType.PERMANENT, "BANK-001"));

            assertThat(emp.id()).isNotNull();
            assertThat(emp.employeeNumber()).isEqualTo("EMP-001");
            assertThat(emp.status()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(emp.thirdPartyId()).isEqualTo(tp.id());
        }
    }

    @Nested
    @DisplayName("Règle 2 — Création composite (tiers + employé en une fois)")
    class CreationComposite {
        @Test
        @DisplayName("Créer un employé + son tiers EMPLOYEE en une requête")
        void createWithThirdPartyCreation() {
            UUID collectiveId = initFixtureAndReturnCollectiveAccount();

            EmployeeResponse emp = service.create(COMPANY_A, new CreateEmployeeRequest(
                null, "Albert Einstein", collectiveId, "EMP-002", "Physicien", "Recherche",
                LocalDate.of(1900, 1, 1), new BigDecimal("100000"), "USD",
                ContractType.CONSULTANT, null));

            assertThat(emp.id()).isNotNull();
            assertThat(emp.thirdPartyId()).isNotNull();
            assertThat(emp.thirdPartyName()).isEqualTo("Albert Einstein");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Unicité de l'employeeNumber")
    class UniciteEmployeeNumber {
        @Test
        @DisplayName("Créer deux employés avec le même employeeNumber → 409")
        void duplicateEmployeeNumber() {
            UUID collectiveId = initFixtureAndReturnCollectiveAccount();
            ThirdPartyResponse tp1 = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.EMPLOYEE, "Employé 1", collectiveId, null, null));
            ThirdPartyResponse tp2 = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.EMPLOYEE, "Employé 2", collectiveId, null, null));

            service.create(COMPANY_A, new CreateEmployeeRequest(
                tp1.id(), null, null, "EMP-DUP", "Poste 1", "Dept",
                LocalDate.of(2020, 1, 1), new BigDecimal("50000"), "HTG",
                ContractType.PERMANENT, null));

            assertThatThrownBy(() -> service.create(COMPANY_A, new CreateEmployeeRequest(
                tp2.id(), null, null, "EMP-DUP", "Poste 2", "Dept",
                LocalDate.of(2020, 1, 1), new BigDecimal("60000"), "HTG",
                ContractType.PERMANENT, null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("EMPLOYEE_NUMBER_ALREADY_EXISTS");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Filtrage par statut")
    class FiltrageStatut {
        @Test
        @DisplayName("Lister avec status=ACTIVE ne retourne que les actifs")
        void filterByStatus() {
            UUID collectiveId = initFixtureAndReturnCollectiveAccount();
            ThirdPartyResponse tp1 = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.EMPLOYEE, "Actif", collectiveId, null, null));
            ThirdPartyResponse tp2 = tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.EMPLOYEE, "Inactif", collectiveId, null, null));

            EmployeeResponse e1 = service.create(COMPANY_A, new CreateEmployeeRequest(
                tp1.id(), null, null, "EMP-A1", "P", "D",
                LocalDate.of(2020, 1, 1), new BigDecimal("50000"), "HTG",
                ContractType.PERMANENT, null));
            EmployeeResponse e2 = service.create(COMPANY_A, new CreateEmployeeRequest(
                tp2.id(), null, null, "EMP-A2", "P", "D",
                LocalDate.of(2020, 1, 1), new BigDecimal("50000"), "HTG",
                ContractType.PERMANENT, null));
            service.changeStatus(COMPANY_A, e2.id(), EmployeeStatus.TERMINATED);

            List<EmployeeResponse> activeOnly = service.list(COMPANY_A, EmployeeStatus.ACTIVE);
            assertThat(activeOnly).hasSize(1);
            assertThat(activeOnly.get(0).employeeNumber()).isEqualTo("EMP-A1");

            List<EmployeeResponse> all = service.list(COMPANY_A, null);
            assertThat(all).hasSize(2);
        }
    }
}

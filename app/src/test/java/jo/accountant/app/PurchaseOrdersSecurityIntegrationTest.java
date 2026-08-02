package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;
import jo.accountant.purchaseorders.repository.PurchaseOrderLineRepository;
import jo.accountant.purchaseorders.repository.PurchaseOrderRepository;
import jo.accountant.purchaseorders.service.PurchaseOrdersService;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.LettrageMatchRepository;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests d'intégration HTTP de sécurité du module {@code purchase-orders}.
 *
 * <p>Vérifie que {@link jo.accountant.purchaseorders.controller.PurchaseOrdersController}
 * applique bien les deux guards (roleChecker + moduleAccessGuard) sur tous ses endpoints,
 * après la correction de l'anomalie critique (Task 1 du prompt
 * {@code PROMPT_AGENT_IA_CORRECTIONS-1.md}).
 *
 * <p>Scénarios couverts (cf. critères d'acceptation Task 1) :
 * <ul>
 *   <li><b>Règle 1</b> — JWT valide mais <em>sans accès</em> à la company ciblée → 404
 *       {@code NOT_FOUND}. L'accès company est vérifié par {@code TenantClaimFilter} au niveau
 *       de la requête (filtre Spring Security), qui retourne intentionnellement 404 (et non 403)
 *       pour éviter la fuite d'information sur l'existence d'une company — cf. §3.9. C'est
 *       l'équivalent fonctionnel du 403 {@code NO_COMPANY_ACCESS} mentionné dans le prompt.</li>
 *   <li><b>Règle 2</b> — JWT valide, accès à la company OK, mais rôle insuffisant
 *       (VIEWER sur un endpoint écriture qui exige BOOKKEEPER) → 403
 *       {@code INSUFFICIENT_ROLE}. Cas spécifique que le correctif de la Task 1 protège :
 *       avant le correctif, le contrôleur n'appelait pas {@code roleChecker.ensureRole}.</li>
 *   <li><b>Règle 3</b> — JWT valide, accès + rôle OK, mais module PURCHASING désactivé
 *       pour la company (par défaut en test, aucun wizard n'a activé le module) → 403
 *       {@code MODULE_NOT_ENABLED}. Cas spécifique que le correctif de la Task 1 protège :
 *       avant le correctif, le contrôleur n'appelait pas {@code moduleAccessGuard.ensureEnabled}.</li>
 * </ul>
 *
 * <p>Les tests utilisent {@link AutoConfigureMockMvc} + {@code SecurityMockMvcRequestPostProcessors.jwt()}
 * pour mocker un JWT valide avec un claim {@code companies} paramétrable — pattern identique
 * à {@code ReportingPdfIntegrationTest}.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, PurchaseOrdersSecurityIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PurchaseOrdersSecurityIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-a00000000002");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PurchaseOrdersService poService;
    @Autowired private AccountingEngineService accountingService;
    @Autowired private ChartOfAccountsService coaService;
    @Autowired private ThirdPartiesService tpService;
    @Autowired private DocumentNumberingService docNumberingService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private FiscalYearRepository fyRepo;
    @Autowired private FiscalPeriodRepository fpRepo;
    @Autowired private JournalRepository journalRepo;
    @Autowired private JournalEntryRepository jeRepo;
    @Autowired private JournalLineRepository jlRepo;
    @Autowired private ThirdPartyRepository tpRepo;
    @Autowired private LettrageMatchRepository lmRepo;
    @Autowired private PurchaseOrderRepository poRepo;
    @Autowired private PurchaseOrderLineRepository polRepo;
    @Autowired private DocumentSequenceConfigRepository docSeqConfigRepo;
    @Autowired private DocumentSequenceCounterRepository docSeqCounterRepo;
    @Autowired private TransactionTemplate txTemplate;

    /**
     * Construit un mock de JWT avec un claim {@code companies} paramétrable.
     * Pattern identique à {@code ReportingPdfIntegrationTest.jwtFor(...)}.
     */
    private static RequestPostProcessor jwtFor(UUID companyId, String role) {
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", companyId.toString(), "role", role))));
    }

    /** JWT valide mais dont le claim {@code companies} ne contient PAS la company ciblée. */
    private static RequestPostProcessor jwtWithoutCompanyAccess() {
        // L'utilisateur n'a accès qu'à COMPANY_B, mais on appelle un endpoint de COMPANY_A
        return jwt().jwt(jwt -> jwt
            .claim("sub", USER_X.toString())
            .claim("companies", List.of(Map.of("companyId", COMPANY_B.toString(), "role", "OWNER"))));
    }

    @BeforeEach
    void seedFixture() {
        // Initialise le plan comptable + journaux + exercice + séquences + tiers fournisseur
        // pour COMPANY_A, comme dans ThreeWayMatchIntegrationTest.initFixture().
        // Note : on n'active volontairement PAS le module PURCHASING — c'est le scénario
        // testé par la Règle 3 (MODULE_NOT_ENABLED). Les autres règles (1 et 2) s'appliquent
        // AVANT la vérification du module, donc l'état d'activation n'importe pas pour elles.
        TenantContext.setCompanyId(COMPANY_A);
        TenantContext.setUserId(USER_X);
        try {
            coaService.initialize(COMPANY_A, SYSCOHADA_ID, null);

            var class4 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "4").orElseThrow();
            var collectiveSupplier = coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
                "401000", "Fournisseurs", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
                NormalBalance.CREDIT, true, null, List.of()));
            var class6 = accountRepo.findByCompanyIdAndCode(COMPANY_A, "6").orElseThrow();
            coaService.createChild(COMPANY_A, class6.getId(), new CreateChildRequest(
                "601000", "Achats de marchandises", ReportingClass.CHARGES,
                ReportingSubcategory.COURANT, NormalBalance.DEBIT, false, null, List.of()));
            coaService.createChild(COMPANY_A, class4.getId(), new CreateChildRequest(
                "445000", "TVA déductible", ReportingClass.ACTIF, ReportingSubcategory.COURANT,
                NormalBalance.DEBIT, false, null, List.of()));

            accountingService.createJournal(COMPANY_A, "AC", "Journal des achats");
            accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
            accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));

            docNumberingService.createSequence(COMPANY_A,
                jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
                "AC", "AC", true, 5, ResetPolicy.YEARLY);
            docNumberingService.createSequence(COMPANY_A,
                jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
                "OD", "OD", true, 5, ResetPolicy.YEARLY);

            tpService.createThirdParty(COMPANY_A, new CreateThirdPartyRequest(
                ThirdPartyType.SUPPLIER, "Fournisseur Test SARL",
                collectiveSupplier.id(), "supplier@test.dev", null));
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(COMPANY_A);
            TenantContext.setUserId(USER_X);
            polRepo.deleteAllInBatch();
            poRepo.deleteAllInBatch();
            lmRepo.deleteAllInBatch();
            tpRepo.deleteAllInBatch();
            jlRepo.deleteAllInBatch();
            jeRepo.deleteAllInBatch();
            journalRepo.deleteAllInBatch();
            fpRepo.deleteAllInBatch();
            fyRepo.deleteAllInBatch();
            accountRepo.deleteAllInBatch();
            docSeqCounterRepo.deleteAll();
            docSeqConfigRepo.deleteAllInBatch();
        });
        TenantContext.clear();
    }

    private static final String CREATE_PO_BODY = """
        {
          "supplierId": "%s",
          "orderNumber": "PO-TEST-001",
          "orderDate": "2026-07-01",
          "currency": "HTG",
          "status": "SUBMITTED",
          "lines": [
            {"description": "Article A", "quantity": 10, "unitPrice": 100.00}
          ]
        }
        """.formatted("00000000-0000-0000-0000-000000000000");

    // ======================================================================
    // Règle 1 — Pas d'accès à la company ciblée → 404 NOT_FOUND
    // (TenantClaimFilter retourne 404 pour ne pas leak l'existence de la company — §3.9)
    // ======================================================================

    @Nested
    @DisplayName("Règle 1 — JWT valide mais sans accès à la company ciblée → 404 NOT_FOUND (équivalent NO_COMPANY_ACCESS)")
    class NoCompanyAccess {

        @Test
        @DisplayName("GET /purchase-orders sans accès company → 404 NOT_FOUND")
        void listWithoutCompanyAccess() throws Exception {
            mockMvc.perform(get("/api/v1/companies/{cid}/purchase-orders", COMPANY_A)
                    .with(jwtWithoutCompanyAccess()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("POST /purchase-orders sans accès company → 404 NOT_FOUND")
        void createWithoutCompanyAccess() throws Exception {
            mockMvc.perform(post("/api/v1/companies/{cid}/purchase-orders", COMPANY_A)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE_PO_BODY)
                    .with(jwtWithoutCompanyAccess()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    // ======================================================================
    // Règle 2 — Rôle insuffisant → 403 INSUFFICIENT_ROLE
    // ======================================================================

    @Nested
    @DisplayName("Règle 2 — Rôle insuffisant (VIEWER sur écriture) → 403 INSUFFICIENT_ROLE")
    class InsufficientRole {

        @Test
        @DisplayName("POST /purchase-orders avec VIEWER (BOOKKEEPER requis) → 403 INSUFFICIENT_ROLE")
        void createWithViewerRole() throws Exception {
            mockMvc.perform(post("/api/v1/companies/{cid}/purchase-orders", COMPANY_A)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE_PO_BODY)
                    .with(jwtFor(COMPANY_A, "VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
        }

        @Test
        @DisplayName("POST /purchase-orders/{poId}/change-status avec VIEWER → 403 INSUFFICIENT_ROLE")
        void changeStatusWithViewerRole() throws Exception {
            mockMvc.perform(post("/api/v1/companies/{cid}/purchase-orders/{poId}/change-status",
                        COMPANY_A, UUID.randomUUID())
                    .param("status", PurchaseOrderStatus.SUBMITTED.name())
                    .with(jwtFor(COMPANY_A, "VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
        }
    }

    // ======================================================================
    // Règle 3 — Module PURCHASING désactivé → 403 MODULE_NOT_ENABLED
    // ======================================================================

    @Nested
    @DisplayName("Règle 3 — Module PURCHASING désactivé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {

        @Test
        @DisplayName("GET /purchase-orders avec module désactivé → 403 MODULE_NOT_ENABLED")
        void listWithModuleDisabled() throws Exception {
            // COMPANY_A a un rôle OWNER dans le JWT, mais le module PURCHASING n'a pas été
            // activé pour cette company (aucun wizard n'a tourné dans le seed @BeforeEach).
            mockMvc.perform(get("/api/v1/companies/{cid}/purchase-orders", COMPANY_A)
                    .with(jwtFor(COMPANY_A, "OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }

        @Test
        @DisplayName("GET /purchase-orders/{poId} avec module désactivé → 403 MODULE_NOT_ENABLED")
        void getWithModuleDisabled() throws Exception {
            mockMvc.perform(get("/api/v1/companies/{cid}/purchase-orders/{poId}",
                        COMPANY_A, UUID.randomUUID())
                    .with(jwtFor(COMPANY_A, "OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }

        @Test
        @DisplayName("POST /purchase-orders avec module désactivé → 403 MODULE_NOT_ENABLED")
        void createWithModuleDisabled() throws Exception {
            mockMvc.perform(post("/api/v1/companies/{cid}/purchase-orders", COMPANY_A)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE_PO_BODY)
                    .with(jwtFor(COMPANY_A, "OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }
    }

    // ======================================================================
    // Sanity check — la couche security est bien appliquée même sur 3-way match
    // ======================================================================

    @Test
    @DisplayName("POST /purchase-orders/3-way-match sans accès company → 404 NOT_FOUND (TenantClaimFilter)")
    void threeWayMatchWithoutCompanyAccess() throws Exception {
        mockMvc.perform(post("/api/v1/companies/{cid}/purchase-orders/3-way-match", COMPANY_A)
                .param("invoiceId", UUID.randomUUID().toString())
                .with(jwtWithoutCompanyAccess()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}

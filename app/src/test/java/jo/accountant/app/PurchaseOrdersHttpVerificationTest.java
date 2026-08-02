package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import jo.accountant.purchaseorders.repository.PurchaseOrderLineRepository;
import jo.accountant.purchaseorders.repository.PurchaseOrderRepository;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.LettrageMatchRepository;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vérification "backend réellement démarré" — Task 1 du prompt
 * {@code PROMPT_AGENT_IA_CORRECTIONS-1.md}.
 *
 * <p>Contrairement à {@link PurchaseOrdersSecurityIntegrationTest} (qui utilise MockMvc), ce test
 * démarre un <em>vrai</em> serveur HTTP Tomcat embarqué sur un port aléatoire et envoie de vraies
 * requêtes HTTP via {@link TestRestTemplate}. Cela satisfait la règle générale 5 du prompt :
 * "démarre effectivement le backend et vérifie par un appel que le comportement corrigé fonctionne".
 *
 * <p>JWT mocké via l'en-tête {@code Authorization: Bearer <token>} — un faux token signé avec la
 * clé de test {@code app.jwt.secret} du profil test, qui produit un vrai JWT valide décodable par
 * le {@code NimbusJwtDecoder} configuré.
 *
 * <p>Scénarios testés (vérification manuelle "équivalent curl") :
 * <ul>
 *   <li>Requête sans rôle suffisant → 403 {@code INSUFFICIENT_ROLE}.</li>
 *   <li>Requête avec rôle OK mais module PURCHASING désactivé → 403 {@code MODULE_NOT_ENABLED}.</li>
 *   <li>Requête sans accès à la company ciblée → 404 {@code NOT_FOUND} (TenantClaimFilter).</li>
 * </ul>
 */
@SpringBootTest(classes = {JoAccountantApplication.class, PurchaseOrdersHttpVerificationTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PurchaseOrdersHttpVerificationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-a00000000002");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SYSCOHADA_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
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

    @Value("${app.jwt.secret:test-secret-please-do-not-use-in-production-256-bits-minimum-1234567890}")
    private String jwtSecret;

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    /**
     * Génère un vrai JWT signé en HS256 avec le secret partagé du profil test — décodable
     * par le {@code NimbusJwtDecoder} configuré dans {@code SecurityConfig}. Cela produit un
     * vrai jeton valide côté serveur, équivalent à ce qu'un client enverrait via
     * {@code Authorization: Bearer <token>}.
     */
    private String jwt(UUID companyId, String role) {
        try {
            byte[] keyBytes = jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // Pad à 256 bits minimum pour HS256 (exigence Nimbus)
            if (keyBytes.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
                keyBytes = padded;
            }
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256");

            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject(USER_X.toString())
                .claim("companies", java.util.List.of(java.util.Map.of(
                    "companyId", companyId.toString(),
                    "role", role)))
                .issueTime(new java.util.Date())
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 900_000))
                .build();

            var jws = new com.nimbusds.jose.JWSObject(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256)
                    .keyID("test-key").build(),
                new com.nimbusds.jose.Payload(claims.toJSONObject()));
            jws.sign(new com.nimbusds.jose.crypto.MACSigner(key));
            return jws.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    /** JWT valide mais sans accès à la company ciblée (claim sur COMPANY_B, endpoint sur COMPANY_A). */
    private String jwtWithoutAccessToCompanyA() {
        return jwt(COMPANY_B, "OWNER");
    }

    @BeforeEach
    void seedFixture() {
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
            accountingService.createJournal(COMPANY_A, "AC", "Journal des achats");
            accountingService.createJournal(COMPANY_A, "OD", "Opérations diverses");
            accountingService.createFiscalYear(COMPANY_A, new CreateFiscalYearRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Exercice 2026"));
            docNumberingService.createSequence(COMPANY_A,
                jo.accountant.documentnumbering.entity.DocumentType.JOURNAL_ENTRY,
                "AC", "AC", true, 5, ResetPolicy.YEARLY);
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

    private ResponseEntity<String> callList(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/companies/" + COMPANY_A + "/purchase-orders",
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> callCreate(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String body = """
            {
              "supplierId": "00000000-0000-0000-0000-000000000000",
              "orderNumber": "PO-VERIFY-001",
              "orderDate": "2026-07-01",
              "currency": "HTG",
              "status": "SUBMITTED",
              "lines": [
                {"description": "Article A", "quantity": 10, "unitPrice": 100.00}
              ]
            }""";
        return restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/companies/" + COMPANY_A + "/purchase-orders",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("HTTP réel — GET /purchase-orders sans accès company → 404 NOT_FOUND")
    void http_listWithoutCompanyAccess_returns404() {
        ResponseEntity<String> resp = callList(jwtWithoutAccessToCompanyA());
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("NOT_FOUND");
    }

    @Test
    @DisplayName("HTTP réel — GET /purchase-orders avec OWNER mais module désactivé → 403 MODULE_NOT_ENABLED")
    void http_listWithModuleDisabled_returns403() {
        ResponseEntity<String> resp = callList(jwt(COMPANY_A, "OWNER"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).contains("MODULE_NOT_ENABLED");
    }

    @Test
    @DisplayName("HTTP réel — POST /purchase-orders avec VIEWER (BOOKKEEPER requis) → 403 INSUFFICIENT_ROLE")
    void http_createWithViewerRole_returns403() {
        ResponseEntity<String> resp = callCreate(jwt(COMPANY_A, "VIEWER"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).contains("INSUFFICIENT_ROLE");
    }

    @Test
    @DisplayName("HTTP réel — POST /purchase-orders avec OWNER mais module désactivé → 403 MODULE_NOT_ENABLED")
    void http_createWithModuleDisabled_returns403() {
        ResponseEntity<String> resp = callCreate(jwt(COMPANY_A, "OWNER"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).contains("MODULE_NOT_ENABLED");
    }
}

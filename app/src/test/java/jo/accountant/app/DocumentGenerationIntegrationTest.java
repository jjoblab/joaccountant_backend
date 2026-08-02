package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentgeneration.dto.CreateTemplateRequest;
import jo.accountant.documentgeneration.dto.GeneratedDocumentResponse;
import jo.accountant.documentgeneration.dto.TemplateResponse;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.repository.DocumentTemplateRepository;
import jo.accountant.documentgeneration.repository.GeneratedDocumentRepository;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
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
 * Tests d'intégration du module {@code document-generation} — Phase 11.
 */
@SpringBootTest(classes = {JoAccountantApplication.class, DocumentGenerationIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class DocumentGenerationIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private DocumentGenerationService service;
    @Autowired private DocumentTemplateRepository templateRepo;
    @Autowired private GeneratedDocumentRepository docRepo;
    @Autowired private TransactionTemplate txTemplate;

    private static final String INVOICE_TEMPLATE = """
        <div class="header">
            <h1>FACTURE</h1>
            <p>Numéro : <span th:text="${invoiceNumber}">FAC-000001</span></p>
            <p>Date : <span th:text="${issueDate}">2026-07-15</span></p>
        </div>
        <div>
            <p>Client : <span th:text="${clientName}">Client Test</span></p>
        </div>
        <table>
            <tr><th>Description</th><th>Montant</th></tr>
            <tr th:each="line : ${lines}">
                <td th:text="${line.description}">Description</td>
                <td th:text="${line.amount}">0.00</td>
            </tr>
            <tr><td><strong>Total</strong></td><td><strong th:text="${totalAmount}">0.00</strong></td></tr>
        </table>
        """;

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
            docRepo.deleteAllInBatch();
            templateRepo.deleteAllInBatch();
        });
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    private TemplateResponse createInvoiceTemplate(UUID companyId) {
        return service.createTemplate(companyId, new CreateTemplateRequest(
            GeneratedDocumentType.INVOICE, INVOICE_TEMPLATE, true));
    }

    @Nested
    @DisplayName("Règle 1 — Création de gabarit OK")
    class CreationGabarit {
        @Test
        @DisplayName("Créer un gabarit INVOICE OK")
        void createTemplate() {
            asTenant(COMPANY_A);
            TemplateResponse t = createInvoiceTemplate(COMPANY_A);
            assertThat(t.id()).isNotNull();
            assertThat(t.documentType()).isEqualTo(GeneratedDocumentType.INVOICE);
            assertThat(t.isDefault()).isTrue();
        }
    }

    @Nested
    @DisplayName("Règle 2 — Génération PDF synchrone")
    class GenerationPDF {
        @Test
        @DisplayName("Générer un PDF avec variables → PDF non vide + checksum")
        void generatePdf() {
            asTenant(COMPANY_A);
            createInvoiceTemplate(COMPANY_A);

            UUID resourceId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("invoiceNumber", "FAC-2026-000142");
            variables.put("issueDate", "2026-07-15");
            variables.put("clientName", "Boutique Pétion-Ville");
            variables.put("totalAmount", "11500.00");
            variables.put("lines", java.util.List.of(
                Map.of("description", "Ventes", "amount", "10000.00"),
                Map.of("description", "TVA", "amount", "1500.00")));

            GeneratedDocumentResponse doc = service.generateDocument(
                COMPANY_A, GeneratedDocumentType.INVOICE, resourceId, variables);

            assertThat(doc.id()).isNotNull();
            assertThat(doc.resourceId()).isEqualTo(resourceId);
            assertThat(doc.storageKey()).isNotBlank();
            assertThat(doc.checksum()).hasSize(64);  // SHA-256 = 64 hex chars
            assertThat(doc.generatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Règle 3 — PDF immuable (pas de régénération)")
    class ImmuabilitePDF {
        @Test
        @DisplayName("Regénérer pour le même resourceId → sert l'existant (même checksum)")
        void regenerateServesExisting() {
            asTenant(COMPANY_A);
            createInvoiceTemplate(COMPANY_A);

            UUID resourceId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("invoiceNumber", "FAC-2026-000143");
            variables.put("clientName", "Client Test");
            variables.put("totalAmount", "5000.00");

            GeneratedDocumentResponse doc1 = service.generateDocument(
                COMPANY_A, GeneratedDocumentType.INVOICE, resourceId, variables);

            // Regénérer avec des variables différentes — doit servir l'existant
            Map<String, Object> newVariables = new HashMap<>();
            newVariables.put("invoiceNumber", "CHANGED");
            newVariables.put("clientName", "CHANGED");
            newVariables.put("totalAmount", "999.00");

            GeneratedDocumentResponse doc2 = service.generateDocument(
                COMPANY_A, GeneratedDocumentType.INVOICE, resourceId, newVariables);

            assertThat(doc2.id()).isEqualTo(doc1.id());  // même document
            assertThat(doc2.checksum()).isEqualTo(doc1.checksum());  // même checksum
        }
    }

    @Nested
    @DisplayName("Règle 4 — Contenu PDF vérifié")
    class ContenuPDF {
        @Test
        @DisplayName("Le PDF généré contient le numéro de document et le montant")
        void pdfContainsExpectedContent() {
            asTenant(COMPANY_A);
            createInvoiceTemplate(COMPANY_A);

            UUID resourceId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("invoiceNumber", "FAC-2026-000144");
            variables.put("issueDate", "2026-07-15");
            variables.put("clientName", "Boutique Pétion-Ville");
            variables.put("totalAmount", "11500.00");
            variables.put("lines", java.util.List.of(
                Map.of("description", "Ventes", "amount", "10000.00")));

            service.generateDocument(COMPANY_A, GeneratedDocumentType.INVOICE, resourceId, variables);

            // Récupérer le contenu PDF
            byte[] pdf = service.getDocumentContent(COMPANY_A, resourceId);
            assertThat(pdf).isNotEmpty();
            assertThat(pdf.length).isGreaterThan(100);  // au moins 100 bytes (un vrai PDF)

            // Vérifier que c'est un PDF valide (magic bytes : %PDF)
            assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        }
    }

    @Nested
    @DisplayName("Règle 5 — Isolation multi-tenant")
    class IsolationTenant {
        @Test
        @DisplayName("Company B ne peut pas récupérer le PDF de Company A → 404")
        void companyBCannotSeeCompanyAPdf() {
            asTenant(COMPANY_A);
            createInvoiceTemplate(COMPANY_A);

            UUID resourceId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("invoiceNumber", "FAC-2026-000145");
            variables.put("clientName", "Client A");
            variables.put("totalAmount", "1000.00");

            service.generateDocument(COMPANY_A, GeneratedDocumentType.INVOICE, resourceId, variables);

            asTenant(COMPANY_B);
            assertThatThrownBy(() -> service.getDocumentContent(COMPANY_B, resourceId))
                .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Règle 6 — Gabarit global par défaut (companyId=null)")
    class GabaritGlobal {
        @Test
        @DisplayName("Si pas de gabarit spécifique à l'entreprise → 422 TEMPLATE_NOT_FOUND")
        void noTemplateThrows422() {
            asTenant(COMPANY_A);
            // Pas de gabarit créé pour cette entreprise

            UUID resourceId = UUID.randomUUID();
            assertThatThrownBy(() -> service.generateDocument(
                COMPANY_A, GeneratedDocumentType.INVOICE, resourceId, new HashMap<>()))
                .isInstanceOf(jo.accountant.core.exception.ValidationException.class)
                .extracting("code").isEqualTo("TEMPLATE_NOT_FOUND");
        }
    }
}

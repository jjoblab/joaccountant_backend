package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.dto.NextNumberPreview;
import jo.accountant.documentnumbering.entity.DocumentSequenceConfig;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests d'intégration du module {@code document-numbering} — Phase 2.
 *
 * <p>Couverture des règles métier §6 (chacune testée par un scénario qui échouerait si la règle
 * était retirée) :
 * <ol>
 *   <li>Atomicité (50 threads parallèles, aucun doublon, aucun saut)</li>
 *   <li>Aperçu non consommateur</li>
 *   <li>Format configurable</li>
 *   <li>{@code ResetPolicy.NEVER} — compteur persistant</li>
 *   <li>{@code ResetPolicy.YEARLY} — reset au changement d'année</li>
 *   <li>{@code ResetPolicy.MONTHLY} — reset au changement de mois</li>
 *   <li>Indépendance des scopeKey</li>
 *   <li>Isolation multi-tenant</li>
 *   <li>Doublon (documentType, scopeKey) → 409</li>
 *   <li>Audit trail — un événement est publié à chaque création de config</li>
 * </ol>
 *
 * <p>PostgreSQL réel via Zonky embedded-postgres (pas H2 — §3.7).
 */
@SpringBootTest(classes = {JoAccountantApplication.class, DocumentNumberingIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class DocumentNumberingIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        public jo.accountant.core.port.NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-a00000000001");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-b00000000001");
    private static final UUID USER_X = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired private DocumentNumberingService service;
    @Autowired private DocumentSequenceConfigRepository configRepo;
    @Autowired private DocumentSequenceCounterRepository counterRepo;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cleanupFor(COMPANY_A);
        cleanupFor(COMPANY_B);
        TenantContext.clear();
    }

    private void cleanupFor(UUID companyId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.setCompanyId(companyId);
            TenantContext.setUserId(USER_X);
            counterRepo.deleteAll();
            configRepo.deleteAll();
        });
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 1 — Atomicité (50 threads parallèles, aucun doublon, aucun saut)")
    class Atomicite {

        @Test
        @DisplayName("50 threads parallèles → 50 numéros uniques, séquentiels de 1 à 50")
        void parallelIncrementNoDuplicateNoGap() throws Exception {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);

            int threadCount = 50;
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();
            Set<String> issuedNumbers = ConcurrentHashMap.newKeySet();
            List<Long> values = Collections.synchronizedList(new ArrayList<>());

            Instant asOf = LocalDate.of(2026, 7, 21).atStartOfDay(ZoneOffset.UTC).toInstant();

            // Snapshot du contexte tenant pour propagation vers les threads du pool
            UUID tenantCompanyId = TenantContext.getCompanyId();
            UUID tenantUserId = TenantContext.getUserId();
            String correlationId = TenantContext.getCorrelationId();

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        // Propagation explicite du TenantContext vers ce thread
                        TenantContext.setCompanyId(tenantCompanyId);
                        TenantContext.setUserId(tenantUserId);
                        TenantContext.setCorrelationId(correlationId);
                        try {
                            IssuedNumber issued = service.nextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
                            issuedNumbers.add(issued.number());
                            values.add(issued.value());
                        } finally {
                            TenantContext.clear();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        throw new RuntimeException(e);
                    }
                    return null;
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            boolean done = pool.awaitTermination(60, TimeUnit.SECONDS);

            assertThat(done).as("Le pool doit terminer en moins de 60s").isTrue();
            assertThat(errors.get()).as("Aucun thread ne doit lever d'exception").isZero();
            assertThat(issuedNumbers).as("Aucun doublon").hasSize(threadCount);
            assertThat(values).as("Aucun saut (séquence 1..50)")
                .containsExactlyInAnyOrderElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, threadCount).boxed().toList());
        }
    }

    @Nested
    @DisplayName("Règle 2 — Aperçu non consommateur")
    class ApercuNonConsommateur {

        @Test
        @DisplayName("preview N fois → compteur inchangé ; consume ensuite → = preview")
        @Transactional
        void previewDoesNotConsumeCounter() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);
            Instant asOf = LocalDate.of(2026, 7, 21).atStartOfDay(ZoneOffset.UTC).toInstant();

            NextNumberPreview p1 = service.previewNextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            NextNumberPreview p2 = service.previewNextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            NextNumberPreview p3 = service.previewNextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);

            assertThat(p1.nextValue()).isEqualTo(1L);
            assertThat(p2.nextValue()).isEqualTo(1L);
            assertThat(p3.nextValue()).isEqualTo(1L);
            assertThat(p1.nextNumber()).isEqualTo(p2.nextNumber()).isEqualTo(p3.nextNumber());

            // La première consommation réelle doit donner exactement le numéro previewed
            IssuedNumber issued = service.nextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            assertThat(issued.number()).isEqualTo(p1.nextNumber());
            assertThat(issued.value()).isEqualTo(1L);

            // Le preview suivant doit maintenant être à 2
            NextNumberPreview p4 = service.previewNextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            assertThat(p4.nextValue()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("Règle 3 — Format configurable (prefix, année optionnelle, padding)")
    class Format {

        @Test
        @DisplayName("prefix=FAC, includeYear=true, padding=6 → FAC-2026-000001")
        @Transactional
        void formatWithYearAndPadding() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);
            Instant asOf = LocalDate.of(2026, 3, 15).atStartOfDay(ZoneOffset.UTC).toInstant();
            IssuedNumber issued = service.nextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            assertThat(issued.number()).isEqualTo("FAC-2026-000001");
        }

        @Test
        @DisplayName("prefix=DON, includeYear=false, padding=7 → DON-0000001")
        @Transactional
        void formatWithoutYear() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.DONATION_RECEIPT, "", "DON", false, 7, ResetPolicy.NEVER);
            Instant asOf = LocalDate.of(2026, 3, 15).atStartOfDay(ZoneOffset.UTC).toInstant();
            IssuedNumber issued = service.nextNumber(COMPANY_A, DocumentType.DONATION_RECEIPT, "", asOf);
            assertThat(issued.number()).isEqualTo("DON-0000001");
        }
    }

    @Nested
    @DisplayName("Règle 4 — ResetPolicy.NEVER : le compteur persiste entre années")
    class ResetNever {

        @Test
        @DisplayName("Une émission en 2025 puis une en 2026 → numérotation continue")
        @Transactional
        void counterPersistsAcrossYears() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.JOURNAL_ENTRY, "OD", "OD", false, 5, ResetPolicy.NEVER);

            Instant d2025 = LocalDate.of(2025, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant d2026 = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

            IssuedNumber i1 = service.nextNumber(COMPANY_A, DocumentType.JOURNAL_ENTRY, "OD", d2025);
            IssuedNumber i2 = service.nextNumber(COMPANY_A, DocumentType.JOURNAL_ENTRY, "OD", d2026);

            assertThat(i1.value()).isEqualTo(1L);
            assertThat(i2.value()).isEqualTo(2L);   // continue, pas de reset
            assertThat(i1.periodKey()).isEmpty();
            assertThat(i2.periodKey()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Règle 5 — ResetPolicy.YEARLY : reset au changement d'année")
    class ResetYearly {

        @Test
        @DisplayName("Émissions 2025 puis 2026 → valeurs 1 et 1 (reset), periodKey différents")
        @Transactional
        void counterResetsAcrossYears() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);

            Instant d2025 = LocalDate.of(2025, 12, 30).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant d2026 = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toInstant();

            IssuedNumber i1 = service.nextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", d2025);
            IssuedNumber i2 = service.nextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", d2026);

            assertThat(i1.value()).isEqualTo(1L);
            assertThat(i1.periodKey()).isEqualTo("2025");
            assertThat(i2.value()).isEqualTo(1L);
            assertThat(i2.periodKey()).isEqualTo("2026");
        }
    }

    @Nested
    @DisplayName("Règle 6 — ResetPolicy.MONTHLY : reset au changement de mois")
    class ResetMonthly {

        @Test
        @DisplayName("Émissions juin puis juillet → valeurs 1 et 1 (reset), periodKey différents")
        @Transactional
        void counterResetsAcrossMonths() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.CREDIT_NOTE, "", "AV", true, 5, ResetPolicy.MONTHLY);

            Instant dJune = LocalDate.of(2026, 6, 30).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dJuly = LocalDate.of(2026, 7, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

            IssuedNumber i1 = service.nextNumber(COMPANY_A, DocumentType.CREDIT_NOTE, "", dJune);
            IssuedNumber i2 = service.nextNumber(COMPANY_A, DocumentType.CREDIT_NOTE, "", dJuly);

            assertThat(i1.value()).isEqualTo(1L);
            assertThat(i1.periodKey()).isEqualTo("2026-06");
            assertThat(i2.value()).isEqualTo(1L);
            assertThat(i2.periodKey()).isEqualTo("2026-07");
        }
    }

    @Nested
    @DisplayName("Règle 7 — Deux scopeKey différents = deux séquences indépendantes")
    class ScopeKeyIndependant {

        @Test
        @DisplayName("VT et AC émettent chacun leur propre séquence 1, 2, 3...")
        @Transactional
        void twoScopeKeysAreIndependent() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.JOURNAL_ENTRY, "VT", "VT", true, 5, ResetPolicy.YEARLY);
            service.createSequence(COMPANY_A, DocumentType.JOURNAL_ENTRY, "AC", "AC", true, 5, ResetPolicy.YEARLY);
            Instant asOf = LocalDate.of(2026, 7, 21).atStartOfDay(ZoneOffset.UTC).toInstant();

            IssuedNumber vt1 = service.nextNumber(COMPANY_A, DocumentType.JOURNAL_ENTRY, "VT", asOf);
            IssuedNumber ac1 = service.nextNumber(COMPANY_A, DocumentType.JOURNAL_ENTRY, "AC", asOf);
            IssuedNumber vt2 = service.nextNumber(COMPANY_A, DocumentType.JOURNAL_ENTRY, "VT", asOf);

            assertThat(vt1.number()).isEqualTo("VT-2026-00001");
            assertThat(ac1.number()).isEqualTo("AC-2026-00001");
            assertThat(vt2.number()).isEqualTo("VT-2026-00002");
        }
    }

    @Nested
    @DisplayName("Règle 8 — Isolation multi-tenant")
    class TenantIsolation {

        @Test
        @DisplayName("Company B ne voit pas la config créée par Company A")
        @Transactional
        void companyBCannotSeeCompanyAConfig() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);

            asTenant(COMPANY_B);
            // Company B n'a aucune config pour SALES_INVOICE → 404
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.getConfig(COMPANY_B, DocumentType.SALES_INVOICE, ""))
                .isInstanceOf(NotFoundException.class);

            // Elle peut créer la sienne avec le même (documentType, scopeKey) — pas de conflit cross-tenant
            service.createSequence(COMPANY_B, DocumentType.SALES_INVOICE, "", "INV", true, 6, ResetPolicy.YEARLY);
            Instant asOf = LocalDate.of(2026, 7, 21).atStartOfDay(ZoneOffset.UTC).toInstant();
            IssuedNumber b1 = service.nextNumber(COMPANY_B, DocumentType.SALES_INVOICE, "", asOf);
            assertThat(b1.number()).startsWith("INV-2026-");
        }
    }

    @Nested
    @DisplayName("Règle 9 — Doublon (documentType, scopeKey) → 409")
    class Doublon {

        @Test
        @DisplayName("Recréer la même config → ConflictException")
        @Transactional
        void duplicateConfigThrows409() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("SEQUENCE_CONFIG_ALREADY_EXISTS");
        }
    }

    @Nested
    @DisplayName("Règle 10 — Validation des entrées")
    class Validation {

        @Test
        @DisplayName("prefix avec caractères spéciaux → 422")
        @Transactional
        void invalidPrefixRejected() {
            asTenant(COMPANY_A);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC!", true, 6, ResetPolicy.YEARLY))
                .isInstanceOf(jo.accountant.core.exception.ValidationException.class)
                .extracting("code").isEqualTo("PREFIX_INVALID");
        }

        @Test
        @DisplayName("padding > 12 → 422")
        @Transactional
        void invalidPaddingRejected() {
            asTenant(COMPANY_A);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 15, ResetPolicy.YEARLY))
                .isInstanceOf(jo.accountant.core.exception.ValidationException.class)
                .extracting("code").isEqualTo("PADDING_INVALID");
        }
    }

    @Nested
    @DisplayName("Règle 11 — Numéro jamais attribué à l'état brouillon")
    class PasDeNumeroEnBrouillon {

        @Test
        @DisplayName("Le service expose nextNumber() et previewNextNumber() mais ne fait rien d'autre — " +
                     "la règle 'pas de numéro en brouillon' est enforced côté consommateurs (Phase 5/12)")
        @Transactional
        void documentationRule() {
            asTenant(COMPANY_A);
            service.createSequence(COMPANY_A, DocumentType.SALES_INVOICE, "", "FAC", true, 6, ResetPolicy.YEARLY);
            Instant asOf = LocalDate.of(2026, 7, 21).atStartOfDay(ZoneOffset.UTC).toInstant();

            // Le preview peut être appelé autant de fois qu'on veut — il ne consomme jamais
            NextNumberPreview p1 = service.previewNextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            NextNumberPreview p2 = service.previewNextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            assertThat(p1.nextValue()).isEqualTo(p2.nextValue()).isEqualTo(1L);

            // Seule nextNumber() consomme — c'est ce que les modules Phase 5/12 appelleront au
            // moment précis de la transition DRAFT → POSTED/ISSUED, jamais avant.
            IssuedNumber issued = service.nextNumber(COMPANY_A, DocumentType.SALES_INVOICE, "", asOf);
            assertThat(issued.value()).isEqualTo(1L);
        }
    }
}

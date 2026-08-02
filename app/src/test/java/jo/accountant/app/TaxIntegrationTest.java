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
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.tax.dto.CreateTaxRuleRequest;
import jo.accountant.tax.dto.CreateWithholdingRuleRequest;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.entity.TaxRule;
import jo.accountant.tax.entity.WithholdingRule;
import jo.accountant.tax.repository.TaxRuleRepository;
import jo.accountant.tax.repository.WithholdingRuleRepository;
import jo.accountant.tax.service.TaxService;
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

@SpringBootTest(classes = {JoAccountantApplication.class, TaxIntegrationTest.TestConfig.class})
@ActiveProfiles("test")
class TaxIntegrationTest extends jo.accountant.testsupport.EmbeddedPostgresSupport {

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

    @Autowired private TaxService service;
    @Autowired private TaxRuleRepository taxRuleRepo;
    @Autowired private WithholdingRuleRepository whRuleRepo;
    @Autowired private CompanyModuleService companyModuleService;
    @Autowired private ModuleAccessGuard moduleAccessGuard;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        txTemplate.executeWithoutResult(status -> {
            whRuleRepo.deleteAll();
            taxRuleRepo.deleteAll();
        });
        TenantContext.clear();
    }

    private void asTenant(UUID companyId) {
        TenantContext.setCompanyId(companyId);
        TenantContext.setUserId(USER_X);
    }

    @Nested
    @DisplayName("Règle 5 — Module non activé → 403 MODULE_NOT_ENABLED")
    class ModuleNotEnabled {
        @Test
        @DisplayName("moduleAccessGuard lève 403 MODULE_NOT_ENABLED si TAX désactivé")
        void moduleNotEnabledRejected() {
            asTenant(COMPANY_A);
            assertThatThrownBy(() -> moduleAccessGuard.ensureEnabled(COMPANY_A, ModuleCode.TAX))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo("MODULE_NOT_ENABLED");
        }
    }

    @Nested
    @DisplayName("Règle 1 — Création de règle TVA")
    class CreationTVA {
        @Test
        @DisplayName("Créer une règle TVA 15% OK")
        void createTaxRule() {
            asTenant(COMPANY_A);
            TaxRule rule = service.createTaxRule(COMPANY_A, new CreateTaxRuleRequest(
                "TVA-HT-15", "TVA Haïti 15%", new BigDecimal("15"),
                null, null, LocalDate.of(2026, 1, 1), null));
            assertThat(rule.getId()).isNotNull();
            assertThat(rule.getCode()).isEqualTo("TVA-HT-15");
            assertThat(rule.getRate()).isEqualByComparingTo("15");
        }
    }

    @Nested
    @DisplayName("Règle 2 — Création de règle de retenue à la source")
    class CreationRetenue {
        @Test
        @DisplayName("Créer une retenue 2% sur fournisseurs OK")
        void createWithholdingRule() {
            asTenant(COMPANY_A);
            WithholdingRule rule = service.createWithholdingRule(COMPANY_A,
                new CreateWithholdingRuleRequest("RS-2", "Retenue 2%", new BigDecimal("2"),
                    List.of("SUPPLIER")));
            assertThat(rule.getId()).isNotNull();
            assertThat(rule.getCode()).isEqualTo("RS-2");
            assertThat(rule.getRate()).isEqualByComparingTo("2");
        }
    }

    @Nested
    @DisplayName("Règle 3 — Déclaration fiscale par période")
    class Declaration {
        @Test
        @DisplayName("Déclaration sans facture → lignes vides, total = 0")
        void emptyDeclaration() {
            asTenant(COMPANY_A);
            TaxDeclaration decl = service.getDeclaration(COMPANY_A,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
            // Audit v4.7 §4.1 — DTO TaxDeclaration renommé : lines() → collectedLines() + deductibleLines()
            assertThat(decl.collectedLines()).isEmpty();
            assertThat(decl.deductibleLines()).isEmpty();
            assertThat(decl.totalTaxCollected()).isEqualByComparingTo("0");
            assertThat(decl.totalTaxDeductible()).isEqualByComparingTo("0");
            assertThat(decl.taxDue()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Règle 4 — Lister les règles")
    class Lister {
        @Test
        @DisplayName("Lister TVA + retenues")
        void listRules() {
            asTenant(COMPANY_A);
            service.createTaxRule(COMPANY_A, new CreateTaxRuleRequest(
                "TVA-15", "TVA 15%", new BigDecimal("15"), null, null, null, null));
            service.createWithholdingRule(COMPANY_A, new CreateWithholdingRuleRequest(
                "RS-2", "RS 2%", new BigDecimal("2"), List.of("SUPPLIER")));

            // listTaxRules renvoie les règles de l'entreprise (company_id = COMPANY_A) ET les
            // règles globales (company_id IS NULL) seedées par la migration V66
            // (TVA_HT_10, TCA_HT_2_BANK, TCA_HT_5_TELECOM, TCA_HT_10_SERVICES — 4 seeds).
            // On a donc 1 règle company-specific + 4 seeds = 5 au total.
            var taxRules = service.listTaxRules(COMPANY_A);
            assertThat(taxRules).hasSize(5);
            assertThat(taxRules).anyMatch(r -> "TVA-15".equals(r.getCode()));

            // WithholdingRule n'a pas de seeds globales (company_id NOT NULL) — 1 seule.
            assertThat(service.listWithholdingRules(COMPANY_A)).hasSize(1);
        }
    }
}

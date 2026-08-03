package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import jo.accountant.company.dto.WizardStep2Request;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.entity.TaxExemptionStatus;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.company.service.CompanyService;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test d'intégration de non-régression pour le fix Dim 2 C1 (audit v9.4) :
 * propagation automatique du {@code taxExemptionStatus} pour les ONG (NGO_HUMANITARIAN).
 *
 * <p>Avant le fix, une ONG créée via le wizard gardait {@code taxExemptionStatus=STANDARD}
 * → IS calculé à 30% au lieu de 0% (Code Fiscal Haïti art. 195). La Javadoc de
 * {@code TaxExemptionStatus} prétendait que ce champ était "alimenté par le wizard" — c'était faux.
 *
 * <p>Ce test valide que :
 * <ol>
 *   <li>Une ONG (NGO_HUMANITARIAN) créée via le wizard a bien {@code taxExemptionStatus=NGO_EXEMPT}</li>
 *   <li>Une entreprise standard (RETAIL_COMMERCE) a bien {@code taxExemptionStatus=STANDARD}</li>
 * </ol>
 *
 * @author jo@Dev
 */
@SpringBootTest(classes = {JoAccountantApplication.class, NgoTaxExemptionPropagationIT.TestConfig.class})
@ActiveProfiles("test")
class NgoTaxExemptionPropagationIT extends jo.accountant.testsupport.EmbeddedPostgresSupport {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-c00000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationChannelPort spyNotificationChannel() {
            return new RecordingNotificationChannel();
        }
    }

    @Autowired private CompanyService companyService;
    @Autowired private CompanyRepository companyRepository;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        try {
            companyRepository.findById(COMPANY_ID).ifPresent(companyRepository::delete);
        } catch (Exception ignored) {
            // Best-effort
        }
    }

    @Test
    @DisplayName("Fix Dim 2 C1 — Wizard avec businessTypeCode=NGO_HUMANITARIAN → taxExemptionStatus=NGO_EXEMPT")
    void wizardNgoPropagatesTaxExemptionStatus() {
        // 1. Créer une company (étape 1 du wizard)
        TenantContext.setUserId(USER_ID);
        var created = companyService.createCompany(USER_ID, "ONG Test", "HT", "HTG", null, null);
        Company company = companyRepository.findById(created.company().id()).orElseThrow();

        // Avant étape 2, taxExemptionStatus est STANDARD (défaut)
        assertThat(company.getTaxExemptionStatus())
            .as("Avant étape 2, taxExemptionStatus doit être STANDARD (défaut)")
            .isEqualTo(TaxExemptionStatus.STANDARD);

        // 2. Appliquer l'étape 2 avec businessTypeCode=NGO_HUMANITARIAN
        companyService.applyWizardStep2(company.getId(), USER_ID,
            new WizardStep2Request(
                "ONG humanitaire test",
                "NGO_HUMANITARIAN",
                Sector.AUTRE,
                java.util.Map.of("donor_reporting_currency", "USD"),
                null));

        // 3. Recharger et vérifier que taxExemptionStatus a été propagé
        Company updated = companyRepository.findById(company.getId()).orElseThrow();
        assertThat(updated.getBusinessTypeCode())
            .as("businessTypeCode doit être NGO_HUMANITARIAN")
            .isEqualTo("NGO_HUMANITARIAN");
        assertThat(updated.getTaxExemptionStatus())
            .as("Fix Dim 2 C1 — Une ONG doit avoir taxExemptionStatus=NGO_EXEMPT (IS 0%, CF art. 195)")
            .isEqualTo(TaxExemptionStatus.NGO_EXEMPT);
    }

    @Test
    @DisplayName("Fix Dim 2 C1 — Wizard avec businessTypeCode=RETAIL_COMMERCE → taxExemptionStatus=STANDARD")
    void wizardRetailKeepsStandardTaxExemptionStatus() {
        TenantContext.setUserId(USER_ID);
        var created = companyService.createCompany(USER_ID, "Retail Test", "HT", "HTG", null, null);
        Company company = companyRepository.findById(created.company().id()).orElseThrow();

        companyService.applyWizardStep2(company.getId(), USER_ID,
            new WizardStep2Request(
                "Commerce de détail test",
                "RETAIL_COMMERCE",
                Sector.COMMERCE,
                java.util.Map.of(),
                null));

        Company updated = companyRepository.findById(company.getId()).orElseThrow();
        assertThat(updated.getTaxExemptionStatus())
            .as("Une entreprise standard (RETAIL_COMMERCE) doit garder taxExemptionStatus=STANDARD")
            .isEqualTo(TaxExemptionStatus.STANDARD);
    }
}

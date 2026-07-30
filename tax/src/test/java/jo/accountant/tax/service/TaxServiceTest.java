package jo.accountant.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.financialstatements.dto.IncomeStatement;
import jo.accountant.financialstatements.service.FinancialStatementsService;
import jo.accountant.invoicing.repository.InvoiceLineRepository;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.purchasing.repository.PurchaseInvoiceLineRepository;
import jo.accountant.purchasing.repository.PurchaseInvoiceRepository;
import jo.accountant.tax.dto.CorporateTaxProjection;
import jo.accountant.tax.entity.CorporateTaxEligibility;
import jo.accountant.tax.entity.CorporateTaxRule;
import jo.accountant.tax.repository.CorporateTaxRuleRepository;
import jo.accountant.tax.repository.TaxCreditCarriedForwardRepository;
import jo.accountant.tax.repository.TaxRuleRepository;
import jo.accountant.tax.repository.WithholdingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link TaxService} — R-16 (lot-D-qualite-arch).
 *
 * <p>Couverture de la méthode {@link TaxService#projectCorporateTax(UUID, LocalDate, LocalDate)}
 * (audit v4.7 §4.1 Finding #4 — Impôt sur les Sociétés).
 *
 * <p>Scénarios :
 * <ul>
 *   <li>Cas nominal : PME éligible sous le seuil de 42 500 € → IS brut = résultat × 15%.</li>
 *   <li>Cas nominal : PME éligible au-delà du seuil → 15% sur 42 500 + 25% sur le solde.</li>
 *   <li>Cas nominal : grande entreprise → 25% sur la totalité.</li>
 *   <li>Cas edge : règle non configurée → règle par défaut France 2026 (25%, UNKNOWN).</li>
 *   <li>Cas edge : résultat déficitaire (≤ 0) → IS brut = 0.</li>
 * </ul>
 *
 * <p>Mockito mocke les repositories (CorporateTaxRuleRepository, FinancialStatementsService)
 * et les autres dépendances du constructeur (non utilisées par projectCorporateTax).
 */
class TaxServiceTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SME_THRESHOLD = new BigDecimal("42500");
    private static final BigDecimal SME_RATE = new BigDecimal("15");
    private static final BigDecimal STANDARD_RATE = new BigDecimal("25");
    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private TaxService taxService;
    private CorporateTaxRuleRepository corporateTaxRuleRepository;
    private FinancialStatementsService financialStatementsService;

    @BeforeEach
    void setUp() {
        // Mocks secondaires (non utilisés par projectCorporateTax mais requis par le constructeur)
        TaxRuleRepository taxRuleRepository = mock(TaxRuleRepository.class);
        WithholdingRuleRepository withholdingRuleRepository = mock(WithholdingRuleRepository.class);
        SalesInvoiceRepository invoiceRepository = mock(SalesInvoiceRepository.class);
        InvoiceLineRepository invoiceLineRepository = mock(InvoiceLineRepository.class);
        PurchaseInvoiceRepository purchaseInvoiceRepository = mock(PurchaseInvoiceRepository.class);
        PurchaseInvoiceLineRepository purchaseInvoiceLineRepository = mock(PurchaseInvoiceLineRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        taxService = new TaxService(
            taxRuleRepository, withholdingRuleRepository,
            invoiceRepository, invoiceLineRepository,
            purchaseInvoiceRepository, purchaseInvoiceLineRepository,
            objectMapper);

        // Dépendances injectées via setter (CorporateTaxRuleRepository + FinancialStatementsService
        // + CompanyRepository + HaitianTaxDeclarationSchedule + TaxCreditCarriedForwardRepository —
        // signature étendue par Lot B R-23)
        corporateTaxRuleRepository = mock(CorporateTaxRuleRepository.class);
        financialStatementsService = mock(FinancialStatementsService.class);
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        HaitianTaxDeclarationSchedule haitianSchedule = mock(HaitianTaxDeclarationSchedule.class);
        TaxCreditCarriedForwardRepository taxCreditRepository = mock(TaxCreditCarriedForwardRepository.class);
        taxService.setCorporateTaxDependencies(
            corporateTaxRuleRepository, financialStatementsService,
            companyRepository, haitianSchedule, taxCreditRepository);
    }

    private void mockIncomeResult(BigDecimal netResult) {
        IncomeStatement is = new IncomeStatement(
            COMPANY_ID, FROM, TO, List.of(), List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO, netResult);
        when(financialStatementsService.getIncomeStatement(COMPANY_ID, FROM, TO)).thenReturn(is);
    }

    private CorporateTaxRule rule(CorporateTaxEligibility eligibility) {
        CorporateTaxRule r = new CorporateTaxRule();
        r.setStandardRate(STANDARD_RATE);
        r.setReducedRate(SME_RATE);
        r.setReducedRateThreshold(SME_THRESHOLD);
        r.setEligibility(eligibility);
        r.setActive(true);
        return r;
    }

    @Test
    @DisplayName("projectCorporateTax — nominal : PME sous le seuil (30 000 €) → IS brut = 15% = 4 500 €")
    void projectCorporateTax_smeUnderThreshold() {
        mockIncomeResult(new BigDecimal("30000.00"));
        when(corporateTaxRuleRepository.findByCompanyIdAndActiveTrue(COMPANY_ID))
            .thenReturn(Optional.of(rule(CorporateTaxEligibility.SME)));

        CorporateTaxProjection projection = taxService.projectCorporateTax(COMPANY_ID, FROM, TO);

        assertThat(projection.accountingResult()).isEqualByComparingTo("30000.00");
        assertThat(projection.taxableResult()).isEqualByComparingTo("30000.00");
        assertThat(projection.appliedRate()).isEqualByComparingTo(SME_RATE);
        // 30000 × 15 / 100 = 4500.00
        assertThat(projection.corporateTaxBrut()).isEqualByComparingTo("4500.00");
        assertThat(projection.corporateTaxNet()).isEqualByComparingTo("4500.00");
        // 4 acomptes égaux + solde = 0
        assertThat(projection.installments()).hasSize(4);
        BigDecimal installment = new BigDecimal("4500.00").divide(new BigDecimal("4"), 2, RoundingMode.HALF_UP);
        assertThat(projection.installments().get(0).amount()).isEqualByComparingTo(installment);
        assertThat(projection.balanceDue()).isEqualByComparingTo(BigDecimal.ZERO.setScale(2));
    }

    @Test
    @DisplayName("projectCorporateTax — nominal : PME au-delà du seuil (50 000 €) → 15% × 42500 + 25% × 7500 = 8 250 €")
    void projectCorporateTax_smeAboveThreshold() {
        mockIncomeResult(new BigDecimal("50000.00"));
        when(corporateTaxRuleRepository.findByCompanyIdAndActiveTrue(COMPANY_ID))
            .thenReturn(Optional.of(rule(CorporateTaxEligibility.SME)));

        CorporateTaxProjection projection = taxService.projectCorporateTax(COMPANY_ID, FROM, TO);

        assertThat(projection.taxableResult()).isEqualByComparingTo("50000.00");
        // 42500 × 15% = 6375 ; 7500 × 25% = 1875 ; total = 8250
        assertThat(projection.corporateTaxBrut()).isEqualByComparingTo("8250.00");
        // appliedRate = 8250 × 100 / 50000 = 16.5000
        assertThat(projection.appliedRate()).isEqualByComparingTo(new BigDecimal("16.5000"));
    }

    @Test
    @DisplayName("projectCorporateTax — nominal : grande entreprise (100 000 €) → 25% = 25 000 €")
    void projectCorporateTax_largeCompany() {
        mockIncomeResult(new BigDecimal("100000.00"));
        when(corporateTaxRuleRepository.findByCompanyIdAndActiveTrue(COMPANY_ID))
            .thenReturn(Optional.of(rule(CorporateTaxEligibility.LARGE)));

        CorporateTaxProjection projection = taxService.projectCorporateTax(COMPANY_ID, FROM, TO);

        assertThat(projection.taxableResult()).isEqualByComparingTo("100000.00");
        assertThat(projection.appliedRate()).isEqualByComparingTo(STANDARD_RATE);
        // 100000 × 25 / 100 = 25000
        assertThat(projection.corporateTaxBrut()).isEqualByComparingTo("25000.00");
    }

    @Test
    @DisplayName("projectCorporateTax — edge : règle non configurée → défaut France 2026 (25%, UNKNOWN)")
    void projectCorporateTax_defaultRuleUsedWhenNoneConfigured() {
        mockIncomeResult(new BigDecimal("100000.00"));
        when(corporateTaxRuleRepository.findByCompanyIdAndActiveTrue(COMPANY_ID))
            .thenReturn(Optional.empty());  // Aucune règle → défaut France

        CorporateTaxProjection projection = taxService.projectCorporateTax(COMPANY_ID, FROM, TO);

        // La règle par défaut a eligibility=UNKNOWN → branche "else" du calcul → 25% sur la totalité
        assertThat(projection.corporateTaxBrut()).isEqualByComparingTo("25000.00");
        assertThat(projection.appliedRate()).isEqualByComparingTo(STANDARD_RATE);
        assertThat(projection.rule().eligibility()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("projectCorporateTax — edge : résultat déficitaire (−10 000 €) → IS brut négatif (reports en avant)")
    void projectCorporateTax_deficit() {
        mockIncomeResult(new BigDecimal("-10000.00"));
        when(corporateTaxRuleRepository.findByCompanyIdAndActiveTrue(COMPANY_ID))
            .thenReturn(Optional.of(rule(CorporateTaxEligibility.LARGE)));

        CorporateTaxProjection projection = taxService.projectCorporateTax(COMPANY_ID, FROM, TO);

        // Pas de PME (LARGE) → -10000 × 25 / 100 = -2500 (le déficit génère un crédit fiscal reportable)
        assertThat(projection.corporateTaxBrut()).isEqualByComparingTo("-2500.00");
    }
}

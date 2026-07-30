package jo.accountant.payroll.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import jo.accountant.employees.entity.Employee;
import jo.accountant.tax.entity.ContributionRule;
import jo.accountant.tax.entity.ContributionRule.ContributionBase;
import jo.accountant.tax.entity.ContributionRule.ContributionRegime;
import jo.accountant.tax.entity.ContributionRule.ContributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link PayrollCalculator} — R-16 (lot-D-qualite-arch).
 *
 * <p>Couverture des règles métier critiques du calcul brut→net :
 * <ul>
 *   <li>Cas nominal : brut sans cotisation → net = brut.</li>
 *   <li>Cotisation EMPLOYEE simple (assiette GROSS) → net = brut − cotisation.</li>
 *   <li>Cotisation EMPLOYEE_AND_EMPLOYER → 2 lignes (salariée + patronale) au même taux.</li>
 *   <li>Cotisation CAPPED_GROSS (Tranche A retraite, plafonnée au PMSS).</li>
 *   <li>Cotisation GROSS_ABATED (CSG 98,25%).</li>
 *   <li>Finding #18 — Heures supplémentaires +25% et +50% sur le salaire de base.</li>
 *   <li>Finding #18 — Prorata absences : 5 jours d'absence sur 30 → base × 25/30.</li>
 *   <li>Cas erreur : gross négatif → IllegalArgumentException.</li>
 *   <li>Cas erreur : employee null → IllegalArgumentException.</li>
 * </ul>
 *
 * <p>Pas de Spring, pas de Mockito : {@link PayrollCalculator} est un {@code @Component} pur
 * sans dépendance injectée.
 */
class PayrollCalculatorTest {

    private PayrollCalculator calculator;
    private UUID companyId;
    private UUID employeeId;

    @BeforeEach
    void setUp() {
        calculator = new PayrollCalculator();
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
    }

    private ContributionRule rule(String code, String label, ContributionType type,
                                    ContributionBase base, BigDecimal rate) {
        ContributionRule r = new ContributionRule();
        r.setId(UUID.randomUUID());
        r.setCompanyId(companyId);
        r.setCode(code);
        r.setLabel(label);
        r.setRegime(ContributionRegime.FR_GENERAL);
        r.setContributionType(type);
        r.setRate(rate);
        r.setBaseType(base);
        r.setActive(true);
        return r;
    }

    @Test
    @DisplayName("calculate(brut, rules) — nominal : brut 5000 sans règle → net = brut")
    void calculate_noRules_netEqualsGross() {
        var result = calculator.calculate(companyId, employeeId, new BigDecimal("5000.00"), List.of());
        assertThat(result.grossSalary()).isEqualByComparingTo("5000.00");
        assertThat(result.netSalary()).isEqualByComparingTo("5000.00");
        assertThat(result.totalEmployeeDeductions()).isEqualByComparingTo("0");
        assertThat(result.totalEmployerContributions()).isEqualByComparingTo("0");
        assertThat(result.employeeContributions()).isEmpty();
        assertThat(result.employerContributions()).isEmpty();
    }

    @Test
    @DisplayName("calculate(brut, rules) — cotisation EMPLOYEE 10% sur GROSS → net = brut − 10%")
    void calculate_employeeContributionGross() {
        var r = rule("URSSAF", "URSSAF maladie", ContributionType.EMPLOYEE,
            ContributionBase.GROSS, new BigDecimal("10.0000"));
        var result = calculator.calculate(companyId, employeeId, new BigDecimal("5000.00"), List.of(r));

        assertThat(result.totalEmployeeDeductions()).isEqualByComparingTo("500.0000");
        assertThat(result.netSalary()).isEqualByComparingTo("4500.0000");
        assertThat(result.employerContributions()).isEmpty();
        assertThat(result.employeeContributions()).hasSize(1);
        assertThat(result.employeeContributions().get(0).code()).isEqualTo("URSSAF");
        assertThat(result.employeeContributions().get(0).party()).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("calculate(brut, rules) — cotisation EMPLOYEE_AND_EMPLOYER 6,9% → 2 lignes au même taux")
    void calculate_employeeAndEmployerContribution() {
        var r = rule("RETRAITE", "Retraite TA", ContributionType.EMPLOYEE_AND_EMPLOYER,
            ContributionBase.GROSS, new BigDecimal("6.9000"));
        var result = calculator.calculate(companyId, employeeId, new BigDecimal("5000.00"), List.of(r));

        // 5000 × 6.9% = 345
        assertThat(result.totalEmployeeDeductions()).isEqualByComparingTo("345.0000");
        assertThat(result.totalEmployerContributions()).isEqualByComparingTo("345.0000");
        assertThat(result.netSalary()).isEqualByComparingTo("4655.0000");

        assertThat(result.employeeContributions()).hasSize(1);
        assertThat(result.employeeContributions().get(0).code()).isEqualTo("RETRAITE_SAL");
        assertThat(result.employerContributions()).hasSize(1);
        assertThat(result.employerContributions().get(0).code()).isEqualTo("RETRAITE_PAT");
    }

    @Test
    @DisplayName("calculate(brut, rules) — CAPPED_GROSS : assiette plafonnée au PMSS 3864")
    void calculate_cappedGross() {
        var r = rule("RETRAITE_TA", "Retraite TA (plafonnée)", ContributionType.EMPLOYEE,
            ContributionBase.CAPPED_GROSS, new BigDecimal("6.9000"));
        r.setMonthlyCeiling(new BigDecimal("3864.0000"));
        // Brut 10000 → assiette = min(10000, 3864) = 3864 → cotisation = 3864 × 6,9% = 266.616
        var result = calculator.calculate(companyId, employeeId, new BigDecimal("10000.00"), List.of(r));

        assertThat(result.totalEmployeeDeductions()).isEqualByComparingTo("266.6160");
        // 10000 − 266.6160 = 9733.3840
        assertThat(result.netSalary()).isEqualByComparingTo("9733.3840");
    }

    @Test
    @DisplayName("calculate(brut, rules) — GROSS_ABATED : CSG 6,865% sur 98,25% du brut")
    void calculate_grossAbatedCsg() {
        var r = rule("CSG", "CSG déductible", ContributionType.EMPLOYEE,
            ContributionBase.GROSS_ABATED, new BigDecimal("6.8650"));
        r.setAbatementRate(new BigDecimal("98.2500"));
        // Brut 5000 → assiette = 5000 × 0.9825 = 4912.5 → cotisation = 4912.5 × 6,865% = 337.2731
        var result = calculator.calculate(companyId, employeeId, new BigDecimal("5000.00"), List.of(r));

        // 4912.5000 × 6.8650 / 100 = 337.2431 (4 décimales, HALF_UP)
        assertThat(result.totalEmployeeDeductions()).isEqualByComparingTo("337.2431");
    }

    @Test
    @DisplayName("calculate(employee, rules) — Finding #18 : HS +25% et +50% sur taux horaire")
    void calculate_overtime25And50() {
        Employee emp = new Employee();
        BigDecimal baseSalary = new BigDecimal("1000.00");
        emp.setBaseSalary(baseSalary);
        emp.setOvertimeHours25(new BigDecimal("10"));
        emp.setOvertimeHours50(new BigDecimal("5"));
        // Pas d'absences → baseProRata = baseSalary
        // Calcul attendu :
        //   hourlyRate   = baseSalary / 173.33 (6 décimales, HALF_UP)
        //   overtime25   = 10 × hourlyRate × 1.25 (4 décimales, HALF_UP)
        //   overtime50   = 5  × hourlyRate × 1.50 (4 décimales, HALF_UP)
        //   grossSalary  = baseProRata + overtime25 + overtime50
        BigDecimal hourlyRate = baseSalary.divide(new BigDecimal("173.33"), 6, RoundingMode.HALF_UP);
        BigDecimal expectedOvertime25 = new BigDecimal("10").multiply(hourlyRate)
            .multiply(new BigDecimal("1.25")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal expectedOvertime50 = new BigDecimal("5").multiply(hourlyRate)
            .multiply(new BigDecimal("1.50")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal expectedGross = baseSalary.add(expectedOvertime25).add(expectedOvertime50);

        var result = calculator.calculate(companyId, employeeId, emp, List.of());
        assertThat(result.baseProRata()).isEqualByComparingTo(baseSalary);
        assertThat(result.overtimeAmount25()).isEqualByComparingTo(expectedOvertime25);
        assertThat(result.overtimeAmount50()).isEqualByComparingTo(expectedOvertime50);
        assertThat(result.grossSalary()).isEqualByComparingTo(expectedGross);
    }

    @Test
    @DisplayName("V79 — v7-7 : prorata 5 absences / 26 → base × 21/26 (Code Travail Haïti)")
    void calculate_absenceProRata() {
        Employee emp = new Employee();
        emp.setBaseSalary(new BigDecimal("3000.00"));
        emp.setAbsenceDays(new BigDecimal("5"));   // 5 jours d'absence
        // V79 — v7-7 : baseProRata = 3000 × (26 - 5) / 26 = 3000 × 21 / 26 = 2423.0769
        var result = calculator.calculate(companyId, employeeId, emp, List.of());
        assertThat(result.baseProRata()).isEqualByComparingTo(new BigDecimal("2423.0769"));
        assertThat(result.grossSalary()).isEqualByComparingTo(new BigDecimal("2423.0769"));
    }

    @Test
    @DisplayName("V79 — v7-7 : 26 jours d'absence → baseProRata = 0")
    void calculate_fullAbsenceZeroBaseProRata() {
        Employee emp = new Employee();
        emp.setBaseSalary(new BigDecimal("3000.00"));
        emp.setAbsenceDays(new BigDecimal("26"));
        var result = calculator.calculate(companyId, employeeId, emp, List.of());
        assertThat(result.baseProRata()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.grossSalary()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("calculate(brut, rules) — error : brut négatif → IllegalArgumentException")
    void calculate_negativeGrossThrows() {
        assertThatThrownBy(() ->
            calculator.calculate(companyId, employeeId, new BigDecimal("-1.00"), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("grossSalary");

        // null également interdit (cast pour lever l'ambiguïté avec l'overload Employee)
        assertThatThrownBy(() ->
            calculator.calculate(companyId, employeeId, (BigDecimal) null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("calculate(employee, rules) — error : employee null → IllegalArgumentException")
    void calculate_nullEmployeeThrows() {
        assertThatThrownBy(() ->
            calculator.calculate(companyId, employeeId, (Employee) null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("employee");
    }

    @Test
    @DisplayName("calculate(brut, rules) — TRANCHE_B : retraite TB sur la part 3864..15456 du brut")
    void calculate_trancheB() {
        // Brut 10000, PMSS 3864, multiplier 4 → plafond sup 15456
        // assiette TB = min(10000, 15456) - 3864 = 6136 → cotisation = 6136 × 15.40% = 944.944
        var r = rule("RETRAITE_TB", "Retraite TB", ContributionType.EMPLOYEE,
            ContributionBase.TRANCHE_B, new BigDecimal("15.4000"));
        r.setMonthlyCeiling(new BigDecimal("3864.0000"));
        r.setCeilingMultiplier(new BigDecimal("4"));

        var result = calculator.calculate(companyId, employeeId, new BigDecimal("10000.00"), List.of(r));
        // 6136.0000 × 15.4000 / 100 = 944.9440
        assertThat(result.totalEmployeeDeductions()).isEqualByComparingTo("944.9440");
    }
}

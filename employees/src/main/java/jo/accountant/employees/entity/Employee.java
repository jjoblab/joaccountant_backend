package jo.accountant.employees.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Employé (restructuration 2026-07-24 — module :employees).
 *
 * <p>Fiche employé rattachée à un {@code ThirdParty} de type {@code EMPLOYEE} du module
 * `:third-parties`. Le {@code thirdPartyId} est créé en amont (via `:third-parties` ou via
 * un endpoint composite de ce module — voir `EmployeesService.createWithThirdParty`).
 *
 * <p>Le module ne génère **aucune** écriture comptable (comme `:third-parties`). Les
 * écritures de paie sont générées par `:payroll` qui consomme cette entité en lecture.
 *
 * <p>Le {@code employeeNumber} est unique par entreprise (contrainte
 * `uc_emp_company_number`). Le {@code bankAccountNumber} est nullable — utilisé par
 * `:payroll` pour préparer les virements.
 */
@Entity
@Table(name = "employee",
    uniqueConstraints = @UniqueConstraint(name = "uc_emp_company_number",
        columnNames = {"company_id", "employee_number"}))
public class Employee extends TenantAwareEntity {

    /** Tiers de type EMPLOYEE (FK logique vers third_party.id). */
    @Column(name = "third_party_id", nullable = false)
    private UUID thirdPartyId;

    @Column(name = "employee_number", nullable = false, length = 50)
    private String employeeNumber;

    /** Libellé du poste (ex. "Comptable senior"). */
    @Column(name = "position", length = 200)
    private String position;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    /** Salaire de base (mensuel pour PERMANENT/FIXED_TERM, prestation pour CONSULTANT). */
    @Column(name = "base_salary", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseSalary;

    @Column(name = "salary_currency", nullable = false, length = 3)
    private String salaryCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 20)
    private ContractType contractType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    /**
     * Heures supplémentaires à +25% (Finding #18 — paie HS/absences/congés).
     * <p>Heures majorées de 25% par rapport au taux horaire de base (cas standard en France
     * et OHADA pour les 8 premières heures sup de la semaine).
     */
    @Column(name = "overtime_hours_25", nullable = false, precision = 19, scale = 4)
    private BigDecimal overtimeHours25 = BigDecimal.ZERO;

    /**
     * Heures supplémentaires à +50% (Finding #18).
     * <p>Heures majorées de 50% (heures sup au-delà de la 8e heure, dimanches/jours fériés).
     */
    @Column(name = "overtime_hours_50", nullable = false, precision = 19, scale = 4)
    private BigDecimal overtimeHours50 = BigDecimal.ZERO;

    /**
     * Lot B R-20 — Heures supplémentaires à +100% (Haïti).
     * <p>Heures majorées de 100% (coefficient 2.0) — au-delà de 56h/sem ou dimanches/jours
     * fériés en Haïti (Code du travail haïtien). Non utilisé en France (où les HS à 100% ne
     * sont pas standard — l'ancien régime 35h prévoit 25%/50%).
     * <p>Défaut 0 pour préserver le comportement historique français.
     */
    @Column(name = "overtime_hours_100", nullable = false, precision = 19, scale = 4)
    private BigDecimal overtimeHours100 = BigDecimal.ZERO;

    /**
     * Lot B R-20 — Numéro CNSS de l'employé (matricule Caisse Nationale des Assurances
     * Sociales, Haïti). Requis pour la déclaration mensuelle CNSS. 12 chiffres.
     * Null pour les employés français (qui utilisent le NIR/numéro sécu).
     */
    @Column(name = "cnss_number", length = 20)
    private String cnssNumber;

    /**
     * Lot B R-20 — Code secteur OFATMA pour l'employé (détermine le taux Accidents du
     * travail, variable 0.5%-6% selon le secteur d'activité). Format libre (ex: "1" pour
     * agriculture, "2" pour industrie, "3" pour commerce).
     * <p>Utilisé par PayrollCalculator pour résoudre la ContributionRule OFATMA_ACCIDENT
     * correspondant au secteur (au MVP, taux default 2% — la résolution par secteur sera
     * ajoutée en v4.8).
     */
    @Column(name = "ofatma_sector_code", length = 10)
    private String ofatmaSectorCode;

    /**
     * R-F-validation (lot-G) — Éligibilité au 13ᵉ mois (Code du Travail haïtien art. 153).
     *
     * <p>En Haïti, le 13ᵉ mois (« mois bonus » de fin d'année) est obligatoire pour tout
     * employé ayant au moins 1 an d'ancienneté au 31 décembre. Pour les employés avec moins
     * d'un an, le 13ᵉ mois est versé au prorata temporis.
     *
     * <p>Ce champ permet de marquer les employés éligibles (défaut : TRUE pour compatibilité).
     * Le calcul effectif du 13ᵉ mois est réalisé par {@code PayrollCalculator.calculateThirteenthMonth()}
     * déclenché en décembre via un {@code PayrollRun} de type {@code THIRTEENTH_MONTH}.
     */
    @Column(name = "thirteenth_month_eligible", nullable = false)
    private Boolean thirteenthMonthEligible = Boolean.TRUE;

    /**
     * Jours d'absence non rémunérés (Finding #18).
     * <p>Ces jours sont déduits du salaire de base au prorata :
     * {@code baseProRata = baseSalary × (workingDays - absenceDays - paidLeaveDays) / workingDays}.
     */
    @Column(name = "absence_days", nullable = false, precision = 19, scale = 4)
    private BigDecimal absenceDays = BigDecimal.ZERO;

    /**
     * Jours de congés payés pris sur la période (Finding #18).
     * <p>Les congés payés sont retenus du temps travaillé mais sont généralement indemnisés
     * séparément (indemnité de congés payés). Au MVP on les déduit du salaire de base comme
     * les absences — l'indemnité de CP séparée sera ajoutée en v4.8.
     */
    @Column(name = "paid_leave_days", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidLeaveDays = BigDecimal.ZERO;

    public UUID getThirdPartyId() { return thirdPartyId; }
    public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

    public String getEmployeeNumber() { return employeeNumber; }
    public void setEmployeeNumber(String employeeNumber) { this.employeeNumber = employeeNumber; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }

    public LocalDate getTerminationDate() { return terminationDate; }
    public void setTerminationDate(LocalDate terminationDate) { this.terminationDate = terminationDate; }

    public BigDecimal getBaseSalary() { return baseSalary; }
    public void setBaseSalary(BigDecimal baseSalary) { this.baseSalary = baseSalary; }

    public String getSalaryCurrency() { return salaryCurrency; }
    public void setSalaryCurrency(String salaryCurrency) { this.salaryCurrency = salaryCurrency; }

    public ContractType getContractType() { return contractType; }
    public void setContractType(ContractType contractType) { this.contractType = contractType; }

    public EmployeeStatus getStatus() { return status; }
    public void setStatus(EmployeeStatus status) { this.status = status; }

    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }

    public BigDecimal getOvertimeHours25() { return overtimeHours25; }
    public void setOvertimeHours25(BigDecimal overtimeHours25) {
        if (overtimeHours25 == null) overtimeHours25 = BigDecimal.ZERO;
        this.overtimeHours25 = overtimeHours25;
    }

    public BigDecimal getOvertimeHours50() { return overtimeHours50; }
    public void setOvertimeHours50(BigDecimal overtimeHours50) {
        if (overtimeHours50 == null) overtimeHours50 = BigDecimal.ZERO;
        this.overtimeHours50 = overtimeHours50;
    }

    public BigDecimal getOvertimeHours100() { return overtimeHours100; }
    public void setOvertimeHours100(BigDecimal overtimeHours100) {
        if (overtimeHours100 == null) overtimeHours100 = BigDecimal.ZERO;
        this.overtimeHours100 = overtimeHours100;
    }

    public String getCnssNumber() { return cnssNumber; }
    public void setCnssNumber(String cnssNumber) { this.cnssNumber = cnssNumber; }

    public String getOfatmaSectorCode() { return ofatmaSectorCode; }
    public void setOfatmaSectorCode(String ofatmaSectorCode) { this.ofatmaSectorCode = ofatmaSectorCode; }

    /** R-F-validation — Éligibilité 13ᵉ mois (Code Travail art. 153). */
    public Boolean getThirteenthMonthEligible() { return thirteenthMonthEligible; }
    public void setThirteenthMonthEligible(Boolean thirteenthMonthEligible) {
        this.thirteenthMonthEligible = thirteenthMonthEligible != null ? thirteenthMonthEligible : Boolean.TRUE;
    }

    public BigDecimal getAbsenceDays() { return absenceDays; }
    public void setAbsenceDays(BigDecimal absenceDays) {
        if (absenceDays == null) absenceDays = BigDecimal.ZERO;
        this.absenceDays = absenceDays;
    }

    public BigDecimal getPaidLeaveDays() { return paidLeaveDays; }
    public void setPaidLeaveDays(BigDecimal paidLeaveDays) {
        if (paidLeaveDays == null) paidLeaveDays = BigDecimal.ZERO;
        this.paidLeaveDays = paidLeaveDays;
    }
}

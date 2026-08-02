package jo.accountant.payroll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Campagne de paie pour une période (mois/année) (module :payroll).
 *
 * <p>Une campagne est unique par entreprise et par période (`uc_pr_company_period`). À la
 * création, son statut est `DRAFT`. {@code calculate()} génère un {@link Payslip} par
 * employé `ACTIVE` à cette date, avec calcul brut→net via les `WithholdingRule` de
 * `:tax` applicables aux employés (`applicableThirdPartyTypes` contient `EMPLOYEE`).
 *
 * <p>Le postage de l'écriture consolidée se fait au moment de l'approbation
 * (`APPROVED`). L'écriture est :
 * <ul>
 * <li>Débit Charges de personnel — total (brut + charges patronales).</li>
 * <li>Crédit Salaires à payer — total net (détaille par employé via `thirdPartyId`).</li>
 * <li>Crédit Organismes sociaux à payer — total charges patronales.</li>
 * <li>Crédit État — total retenues fiscales (impôt sur le revenu salarial).</li>
 * </ul>
 *
 * <p>Le {@code journalEntryId} est positionné à l'approbation (postage).
 */
@Entity
@Table(name = "payroll_run",
 uniqueConstraints = @UniqueConstraint(name = "uc_pr_company_period",
 columnNames = {"company_id", "period_year", "period_month"}))
/**
 * PayrollRun.
 *
 * @author jo@Dev


 */

public class PayrollRun extends TenantAwareEntity {

 @Column(name = "period_month", nullable = false)
 private int periodMonth;

 @Column(name = "period_year", nullable = false)
 private int periodYear;

 @Enumerated(EnumType.STRING)
 @Column(name = "status", nullable = false, length = 20)
 private PayrollRunStatus status = PayrollRunStatus.DRAFT;

 @Column(name = "total_gross", nullable = false, precision = 19, scale = 4)
 private BigDecimal totalGross = BigDecimal.ZERO;

 @Column(name = "total_net", nullable = false, precision = 19, scale = 4)
 private BigDecimal totalNet = BigDecimal.ZERO;

 @Column(name = "total_employer_contributions", nullable = false, precision = 19, scale = 4)
 private BigDecimal totalEmployerContributions = BigDecimal.ZERO;

 /** ID de l'écriture comptable consolidée générée à l'approbation. */
 @Column(name = "journal_entry_id")
 private UUID journalEntryId;

 /**
 * V86 — v7-4 : Type de campagne (REGULAR ou THIRTEENTH_MONTH).
 * Par défaut REGULAR pour préserver la rétro-compatibilité des campagnes existantes.
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "run_type", nullable = false, length = 20)
 private PayrollRunType runType = PayrollRunType.REGULAR;

 public int getPeriodMonth() { return periodMonth; }
 public void setPeriodMonth(int periodMonth) { this.periodMonth = periodMonth; }

 public int getPeriodYear() { return periodYear; }
 public void setPeriodYear(int periodYear) { this.periodYear = periodYear; }

 public PayrollRunStatus getStatus() { return status; }
 public void setStatus(PayrollRunStatus status) { this.status = status; }

 public BigDecimal getTotalGross() { return totalGross; }
 public void setTotalGross(BigDecimal totalGross) { this.totalGross = totalGross; }

 public BigDecimal getTotalNet() { return totalNet; }
 public void setTotalNet(BigDecimal totalNet) { this.totalNet = totalNet; }

 public BigDecimal getTotalEmployerContributions() { return totalEmployerContributions; }
 public void setTotalEmployerContributions(BigDecimal totalEmployerContributions) {
 this.totalEmployerContributions = totalEmployerContributions;
 }

 public UUID getJournalEntryId() { return journalEntryId; }
 public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

 public PayrollRunType getRunType() { return runType; }
 public void setRunType(PayrollRunType runType) {
 this.runType = runType != null ? runType : PayrollRunType.REGULAR;
 }
}

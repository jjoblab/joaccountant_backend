package jo.accountant.expenses.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Note de frais (restructuration 2026-07-24 — module :expenses).
 *
 * <p>Une note de frais peut être rattachée à un employé (tiers de type EMPLOYEE) ou non
 * (dépense d'exploitation générale — {@code thirdPartyId} nullable). Le cycle de vie
 * est DRAFT → SUBMITTED → APPROVED → PAID (ou REJECTED au stade SUBMITTED).
 *
 * <p>Le champ {@link #paidDirectly} distingue deux cas à l'approbation :
 * <ul>
 *   <li>{@code paidDirectly = false} (défaut) : la dépense est remboursable à l'employé.
 *       L'écriture générée est Débit Charges / Crédit Tiers-Employé (créance de
 *       l'employé à recevoir).</li>
 *   <li>{@code paidDirectly = true} : la dépense a été payée directement par la caisse
 *       ou la banque de l'entreprise au moment de la constitution de la note. L'écriture
 *       générée est Débit Charges / Crédit Trésorerie (compte de passage).</li>
 * </ul>
 *
 * <p>Le compte de trésorerie est résolu par recherche `ACTIF + taxMappingCode="CASH"`
 * puis fallback SYSCOHADA `"570000"/"57"`. Si aucun compte de trésorerie n'est trouvé,
 * l'approbation échoue avec `422 CASH_ACCOUNT_NOT_FOUND`.
 */
@Entity
@Table(name = "expense_report")
public class ExpenseReport extends TenantAwareEntity {

    /** Tiers de type EMPLOYEE — nullable (dépense d'exploitation générale non rattachée). */
    @Column(name = "third_party_id")
    private UUID thirdPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExpenseReportStatus status = ExpenseReportStatus.DRAFT;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Si true : payé directement par la trésorerie. Si false : à rembourser à l'employé. */
    @Column(name = "paid_directly", nullable = false)
    private boolean paidDirectly = false;

    /** ID de l'écriture comptable générée au passage APPROVED. */
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    public UUID getThirdPartyId() { return thirdPartyId; }
    public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

    public ExpenseReportStatus getStatus() { return status; }
    public void setStatus(ExpenseReportStatus status) { this.status = status; }

    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public boolean isPaidDirectly() { return paidDirectly; }
    public void setPaidDirectly(boolean paidDirectly) { this.paidDirectly = paidDirectly; }

    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }
}

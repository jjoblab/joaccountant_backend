package jo.accountant.expenses.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne de note de frais (module :expenses).
 *
 * <p>Chaque ligne référence un compte de charge cible (optionnel — fallback sur un compte
 * de charge générique). La catégorie (`TRAVEL`/`MEALS`/`SUPPLIES`/`OTHER`) est purement
 * descriptive et n'affecte pas la comptabilisation (qui se fait via `expenseAccountId`).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "expense_line")
public class ExpenseLine extends TenantAwareEntity {

 @Column(name = "report_id", nullable = false)
 private UUID reportId;

 @Column(name = "category", length = 20)
 private String category;

 @Column(name = "description", nullable = false, length = 500)
 private String description;

 @Column(name = "amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal amount;

 /** Compte de charge cible (CHARGES). Si null, fallback sur compte CHARGES générique. */
 @Column(name = "expense_account_id")
 private UUID expenseAccountId;

 public UUID getReportId() { return reportId; }
 public void setReportId(UUID reportId) { this.reportId = reportId; }

 public String getCategory() { return category; }
 public void setCategory(String category) { this.category = category; }

 public String getDescription() { return description; }
 public void setDescription(String description) { this.description = description; }

 public BigDecimal getAmount() { return amount; }
 public void setAmount(BigDecimal amount) { this.amount = amount; }

 public UUID getExpenseAccountId() { return expenseAccountId; }
 public void setExpenseAccountId(UUID expenseAccountId) { this.expenseAccountId = expenseAccountId; }
}

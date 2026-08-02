package jo.accountant.purchasing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne de facture d'achat (module :purchasing).
 *
 * <p>Chaque ligne référence un compte de charge cible ({@code expenseAccountId}) — ce compte
 * doit être de {@code ReportingClass.CHARGES}. La résolution des comptes de charge se fait
 * ligne par ligne (et non pas au niveau de la facture) car une facture fournisseur peut
 * mélanger plusieurs natures de charge (ex. marchandises + frais de transport).
 *
 * <p>Le {@code lineTotalHt = quantity × unitPrice}, {@code lineTotalTax = lineTotalHt × taxRate / 100}.
 *
 * <p>Note : pas de chaînage automatique achat → entrée de stock {@code :inventory} au MVP.
 * Voir BACKLOG.md — entrée de stock déclenchée par une facture d'achat est explicitement
 * hors scope (§4 du prompt) car elle sort du périmètre de ce lot.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "purchase_invoice_line")
public class PurchaseInvoiceLine extends TenantAwareEntity {

 @Column(name = "invoice_id", nullable = false)
 private UUID invoiceId;

 @Column(name = "description", nullable = false, length = 500)
 private String description;

 @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
 private BigDecimal quantity;

 @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
 private BigDecimal unitPrice;

 @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
 private BigDecimal taxRate = BigDecimal.ZERO;

 /** Compte de charge cible (doit être ReportingClass.CHARGES). */
 @Column(name = "expense_account_id")
 private UUID expenseAccountId;

 @Column(name = "line_total_ht", nullable = false, precision = 19, scale = 4)
 private BigDecimal lineTotalHt = BigDecimal.ZERO;

 @Column(name = "line_total_tax", nullable = false, precision = 19, scale = 4)
 private BigDecimal lineTotalTax = BigDecimal.ZERO;

 public UUID getInvoiceId() { return invoiceId; }
 public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }

 public String getDescription() { return description; }
 public void setDescription(String description) { this.description = description; }

 public BigDecimal getQuantity() { return quantity; }
 public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

 public BigDecimal getUnitPrice() { return unitPrice; }
 public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

 public BigDecimal getTaxRate() { return taxRate; }
 public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }

 public UUID getExpenseAccountId() { return expenseAccountId; }
 public void setExpenseAccountId(UUID expenseAccountId) { this.expenseAccountId = expenseAccountId; }

 public BigDecimal getLineTotalHt() { return lineTotalHt; }
 public void setLineTotalHt(BigDecimal lineTotalHt) { this.lineTotalHt = lineTotalHt; }

 public BigDecimal getLineTotalTax() { return lineTotalTax; }
 public void setLineTotalTax(BigDecimal lineTotalTax) { this.lineTotalTax = lineTotalTax; }
}

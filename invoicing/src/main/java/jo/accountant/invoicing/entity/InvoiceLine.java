package jo.accountant.invoicing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne de facture (§13.
 *
 * <p>Règle fondamentale : une ligne référence {@code itemId} (Commerce — déclègue le COGS
 * à :inventory) <strong>OU</strong> {@code timesheetEntryId} (Service — consomme le WIP),
 * <strong>jamais les deux</strong>. Vérifié côté service au passage DRAFT → ISSUED.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "invoice_line")
public class InvoiceLine extends TenantAwareEntity {

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /** Pourcentage de remise (0 à 100). 0 = pas de remise. */
    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    /** Taux de TVA (0 à 100). 0 = pas de TVA. */
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate = BigDecimal.ZERO;

    /** Si renseigné : article de stock (Commerce). Déclègue le COGS à :inventory. */
    @Column(name = "item_id")
    private UUID itemId;

    /** Si renseigné : entrée de temps (Service). Consomme le WIP (timesheetEntry.invoiced = true). */
    @Column(name = "timesheet_entry_id")
    private UUID timesheetEntryId;

    /** Montant HT de la ligne = quantity × unitPrice × (1 - discountPercent/100). */
    @Column(name = "line_total_ht", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotalHt = BigDecimal.ZERO;

    /** Montant TVA de la ligne = lineTotalHt × taxRate / 100. */
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

    public BigDecimal getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(BigDecimal discountPercent) { this.discountPercent = discountPercent; }

    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getTimesheetEntryId() { return timesheetEntryId; }
    public void setTimesheetEntryId(UUID timesheetEntryId) { this.timesheetEntryId = timesheetEntryId; }

    public BigDecimal getLineTotalHt() { return lineTotalHt; }
    public void setLineTotalHt(BigDecimal lineTotalHt) { this.lineTotalHt = lineTotalHt; }

    public BigDecimal getLineTotalTax() { return lineTotalTax; }
    public void setLineTotalTax(BigDecimal lineTotalTax) { this.lineTotalTax = lineTotalTax; }
}

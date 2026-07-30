package jo.accountant.purchaseorders.entity;

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
 * Commande fournisseur / Purchase Order (Finding #10).
 *
 * <p>Une commande formalise l'engagement d'achat auprès d'un fournisseur ({@code supplierId}
 * = ThirdParty SUPPLIER) pour un ensemble de lignes (quantités + prix unitaires). Elle sert
 * de référence au 3-way match : à la réception de la facture fournisseur ({@code PurchaseInvoice}
 * du module :purchasing), le service {@code ThreeWayMatchService} vérifie que :
 * <ol>
 *   <li>une commande existe pour ce fournisseur,</li>
 *   <li>les quantités facturées ≤ quantités commandées,</li>
 *   <li>les prix facturés = prix commandés.</li>
 * </ol>
 *
 * <p>Cycle de vie : DRAFT → SUBMITTED → RECEIVED → CLOSED. Les commandes ne génèrent pas
 * d'écriture comptable au MVP (l'écriture est générée à la facture dans :purchasing).
 *
 * <p>Le {@code orderNumber} est unique par entreprise (contrainte {@code uc_po_company_number}).
 */
@Entity
@Table(name = "purchase_order",
    uniqueConstraints = @UniqueConstraint(name = "uc_po_company_number",
        columnNames = {"company_id", "order_number"}))
public class PurchaseOrder extends TenantAwareEntity {

    /** Tiers SUPPLIER (FK logique vers third_party.id). */
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "order_number", nullable = false, length = 50)
    private String orderNumber;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "HTG";

    /** Total calculé = Σ (line.quantity × line.unitPrice). Mis à jour à chaque modif de ligne. */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }

    public PurchaseOrderStatus getStatus() { return status; }
    public void setStatus(PurchaseOrderStatus status) { this.status = status; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) {
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;
        this.totalAmount = totalAmount;
    }
}

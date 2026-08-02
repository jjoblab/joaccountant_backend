package jo.accountant.purchaseorders.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne de commande fournisseur.
 *
 * <p>Chaque ligne référence un {@code itemId} (article du module :inventory, nullable pour
 * les lignes "libres" type frais de port), une description, une quantité commandée et un
 * prix unitaire. Le {@code receivedQuantity} est incrémenté à mesure que les marchandises
 * sont réceptionnées (typiquement via le module :inventory StockMove IN).
 *
 * <p>Le {@code lineTotal = quantity × unitPrice} est calculé côté service (pas persisté en
 * colonne pour éviter la dénormalisation) — mais le {@code totalAmount} de la commande parente
 * est lui persisté (somme des lineTotal).
 */
@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine extends TenantAwareEntity {

 /** Commande parente (FK logique vers purchase_order.id). */
 @Column(name = "po_id", nullable = false)
 private UUID poId;

 /** Article du module :inventory (nullable pour lignes libres type frais de port). */
 @Column(name = "item_id")
 private UUID itemId;

 @Column(name = "description", nullable = false, length = 500)
 private String description;

 @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
 private BigDecimal quantity;

 @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
 private BigDecimal unitPrice;

 /**
 * Quantité déjà reçue (incrémentée à la réception via :inventory).
 * Permet de distinguer les lignes entièrement reçues ({@code receivedQuantity = quantity})
 * des lignes partiellement reçues.
 */
 @Column(name = "received_quantity", nullable = false, precision = 19, scale = 4)
 private BigDecimal receivedQuantity = BigDecimal.ZERO;

 public UUID getPoId() { return poId; }
 public void setPoId(UUID poId) { this.poId = poId; }

 public UUID getItemId() { return itemId; }
 public void setItemId(UUID itemId) { this.itemId = itemId; }

 public String getDescription() { return description; }
 public void setDescription(String description) { this.description = description; }

 public BigDecimal getQuantity() { return quantity; }
 public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

 public BigDecimal getUnitPrice() { return unitPrice; }
 public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

 public BigDecimal getReceivedQuantity() { return receivedQuantity; }
 public void setReceivedQuantity(BigDecimal receivedQuantity) {
 if (receivedQuantity == null) receivedQuantity = BigDecimal.ZERO;
 this.receivedQuantity = receivedQuantity;
 }
}

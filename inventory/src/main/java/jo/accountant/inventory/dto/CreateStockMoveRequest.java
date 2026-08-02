package jo.accountant.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.inventory.entity.StockMoveDirection;

/**
 * Corps de requête pour {@code POST .../stock-moves}.
 *
 * @param counterpartyAccountId compte de contrepartie pour l'écriture d'entrée stock (audit E-A).
 * Pour un achat à crédit : compte de fournisseur (PASSIF). Pour un achat au comptant :
 * compte de trésorerie (ACTIF). Si null pour un IN move, aucune écriture n'est générée
 * (rétro-compatibilité — le stock est valorisé mais pas comptabilisé).
 
 *
 * @author jo@Dev


*/
public record CreateStockMoveRequest(
    @NotNull UUID itemId,
    @NotNull UUID warehouseId,
    UUID toWarehouseId,
    @NotNull LocalDate moveDate,
    @NotNull StockMoveDirection direction,
    @NotNull @Positive BigDecimal quantity,
    BigDecimal unitCost,
    String sourceDocument,
    UUID counterpartyAccountId
) {
    /** Rétro-compatibilité — pour les anciens appelants qui ne passent pas counterpartyAccountId. */
    public CreateStockMoveRequest(
        @NotNull UUID itemId,
        @NotNull UUID warehouseId,
        UUID toWarehouseId,
        @NotNull LocalDate moveDate,
        @NotNull StockMoveDirection direction,
        @NotNull @Positive BigDecimal quantity,
        BigDecimal unitCost,
        String sourceDocument
    ) {
        this(itemId, warehouseId, toWarehouseId, moveDate, direction, quantity, unitCost, sourceDocument, null);
    }
}

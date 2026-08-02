package jo.accountant.inventory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.inventory.entity.StockMove;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA StockMove.
 *
 * @author jo@Dev


 */

public interface StockMoveRepository extends JpaRepository<StockMove, UUID> {
    List<StockMove> findByItemIdOrderByMoveDate(UUID itemId);
    List<StockMove> findByItemIdAndWarehouseIdOrderByMoveDate(UUID itemId, UUID warehouseId);

    /**
     * Tous les mouvements de stock d'une entreprise dont la {@code moveDate} est comprise
     * entre {@code start} et {@code end} (inclus), triés par {@code moveDate} décroissant.
     *
     * <p>Utilisé par {@code GET /api/v1/companies/{companyId}/inventory/stock-moves?from=&to=}
     * (Part E2) pour le registre des mouvements de stock.
     */
    List<StockMove> findByCompanyIdAndMoveDateBetweenOrderByMoveDateDesc(
        UUID companyId, LocalDate start, LocalDate end);

    /** Tous les mouvements de stock d'une entreprise (toutes dates), triés par date. */
    List<StockMove> findByCompanyIdOrderByMoveDateDesc(UUID companyId);
}

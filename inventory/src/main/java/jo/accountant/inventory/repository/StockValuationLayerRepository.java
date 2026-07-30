package jo.accountant.inventory.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jo.accountant.inventory.entity.StockValuationLayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des couches de valorisation FIFO.
 *
 * <p><b>Audit v4.7 §3.2 Finding HAUT — race condition FIFO</b> : la méthode
 * {@link #findFifoLayersForUpdate} utilise {@code SELECT ... FOR UPDATE} (pessimistic write lock)
 * pour empêcher deux mouvements OUT simultanés de consommer la même couche FIFO. Sans ce verrou,
 * la transaction T1 lit la couche (quantityRemaining=10), T2 lit la même couche (10), T1 décrémente
 * (→5), T2 décrémente (→-5) → surconsommation et stock négatif.
 */
public interface StockValuationLayerRepository
    extends JpaRepository<StockValuationLayer, UUID> {

    /** Couches non épuisées (quantityRemaining > 0) pour un item + entrepôt, triées par
     *  date de réception (plus ancienne d'abord = première à consommer en FIFO). */
    @Query("select l from StockValuationLayer l " +
           "where l.companyId = :companyId and l.itemId = :itemId " +
           "and l.warehouseId = :warehouseId and l.quantityRemaining > 0 " +
           "order by l.receiptDate asc, l.createdAt asc")
    List<StockValuationLayer> findFifoLayers(@Param("companyId") UUID companyId,
                                              @Param("itemId") UUID itemId,
                                              @Param("warehouseId") UUID warehouseId);

    /**
     * Variante avec verrou pessimiste {@code SELECT FOR UPDATE} (audit v4.7 §3.2 Finding HAUT).
     *
     * <p>À utiliser dans les transactions de consommation FIFO ({@code consumeFifoLayers}) pour
     * empêcher la race condition. Le verrou est libéré au commit/rollback de la transaction.
     *
     * <p>Note : sur PostgreSQL, {@code LockModeType.PESSIMISTIC_WRITE} génère
     * {@code SELECT ... FOR UPDATE}. Sur d'autres SGBD, le comportement peut varier — vérifier
     * la doc du dialecte Hibernate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockValuationLayer l " +
           "where l.companyId = :companyId and l.itemId = :itemId " +
           "and l.warehouseId = :warehouseId and l.quantityRemaining > 0 " +
           "order by l.receiptDate asc, l.createdAt asc")
    List<StockValuationLayer> findFifoLayersForUpdate(@Param("companyId") UUID companyId,
                                                       @Param("itemId") UUID itemId,
                                                       @Param("warehouseId") UUID warehouseId);

    /** Somme des quantités restantes pour un item (tous entrepôts confondus). */
    @Query("select coalesce(sum(l.quantityRemaining), 0) from StockValuationLayer l " +
           "where l.companyId = :companyId and l.itemId = :itemId")
    BigDecimal sumQuantityRemainingByItemId(@Param("companyId") UUID companyId,
                                             @Param("itemId") UUID itemId);

    /** Toutes les couches d'un item (pour audit). */
    List<StockValuationLayer> findByItemIdOrderByReceiptDate(UUID itemId);
}

package jo.accountant.purchaseorders.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.purchaseorders.entity.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des lignes de commande.
 
 *
 * @author jo@Dev


*/
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

 /** Toutes les lignes d'une commande, triées par date de création. */
 List<PurchaseOrderLine> findByPoIdOrderByCreatedAt(UUID poId);

 /** Supprime toutes les lignes d'une commande — utilisé lors d'une regen. */
 void deleteByPoId(UUID poId);
}

package jo.accountant.purchaseorders.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.purchaseorders.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des commandes fournisseurs (Finding #10).
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /** Toutes les commandes d'une entreprise, triées par date décroissante. */
    List<PurchaseOrder> findByCompanyIdOrderByOrderDateDesc(UUID companyId);

    /** Commandes d'un fournisseur donné, triées par date décroissante. */
    List<PurchaseOrder> findByCompanyIdAndSupplierIdOrderByOrderDateDesc(UUID companyId, UUID supplierId);

    /** Recherche par numéro de commande (unicité par entreprise). */
    Optional<PurchaseOrder> findByCompanyIdAndOrderNumber(UUID companyId, String orderNumber);
}

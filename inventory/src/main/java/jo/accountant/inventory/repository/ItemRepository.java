package jo.accountant.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.inventory.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA Item.
 *
 * @author jo@Dev


 */

public interface ItemRepository extends JpaRepository<Item, UUID> {
    List<Item> findByCompanyIdOrderBySku(UUID companyId);
    Optional<Item> findByCompanyIdAndSku(UUID companyId, String sku);
}

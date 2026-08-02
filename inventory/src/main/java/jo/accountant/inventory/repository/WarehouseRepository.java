package jo.accountant.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.inventory.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA Warehouse.
 *
 * @author jo@Dev


 */

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    List<Warehouse> findByCompanyIdOrderByLabel(UUID companyId);
    Optional<Warehouse> findByCompanyIdAndLabel(UUID companyId, String label);
}

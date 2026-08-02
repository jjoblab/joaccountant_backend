package jo.accountant.fxoperations.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.fxoperations.entity.FxOperation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA FxOperation.
 *
 * @author jo@Dev


 */

public interface FxOperationRepository extends JpaRepository<FxOperation, UUID> {

    List<FxOperation> findByCompanyIdOrderByOperationDateDesc(UUID companyId);
}

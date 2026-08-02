package jo.accountant.core.framework;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA AccountingFramework.
 *
 * @author jo@Dev


 */

public interface AccountingFrameworkRepository extends JpaRepository<AccountingFramework, UUID> {
    Optional<AccountingFramework> findByCode(String code);
}

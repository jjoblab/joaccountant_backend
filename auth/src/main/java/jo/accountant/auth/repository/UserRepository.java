package jo.accountant.auth.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA User.
 *
 * @author jo@Dev


 */

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}

package jo.accountant.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.auth.entity.UserCompanyRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCompanyRoleRepository extends JpaRepository<UserCompanyRole, UUID> {

    List<UserCompanyRole> findByUserId(UUID userId);

    List<UserCompanyRole> findByCompanyId(UUID companyId);

    Optional<UserCompanyRole> findByUserIdAndCompanyId(UUID userId, UUID companyId);

    long countByUserId(UUID userId);
}

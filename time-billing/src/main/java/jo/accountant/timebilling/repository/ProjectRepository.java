package jo.accountant.timebilling.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.timebilling.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByCompanyIdOrderByCode(UUID companyId);
    Optional<Project> findByCompanyIdAndCode(UUID companyId, String code);
}

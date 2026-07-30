package jo.accountant.company.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    long countByCreatedBy(UUID createdBy);
}

package jo.accountant.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.company.entity.CompanyModule;
import jo.accountant.company.entity.ModuleCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyModuleRepository extends JpaRepository<CompanyModule, UUID> {

    List<CompanyModule> findByCompanyId(UUID companyId);

    Optional<CompanyModule> findByCompanyIdAndModuleCode(UUID companyId, ModuleCode moduleCode);
}

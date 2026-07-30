package jo.accountant.company.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.BusinessTypeModule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessTypeModuleRepository extends JpaRepository<BusinessTypeModule, UUID> {

    List<BusinessTypeModule> findByBusinessTypeCode(String businessTypeCode);
}

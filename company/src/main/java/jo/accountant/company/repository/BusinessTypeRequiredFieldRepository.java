package jo.accountant.company.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.BusinessTypeRequiredField;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA BusinessTypeRequiredField.
 *
 * @author jo@Dev


 */

public interface BusinessTypeRequiredFieldRepository
    extends JpaRepository<BusinessTypeRequiredField, UUID> {

    List<BusinessTypeRequiredField> findByBusinessTypeCodeOrderByDisplayOrderAsc(String businessTypeCode);
}

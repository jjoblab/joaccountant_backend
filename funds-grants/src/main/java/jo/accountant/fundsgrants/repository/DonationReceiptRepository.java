package jo.accountant.fundsgrants.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.DonationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA DonationReceipt.
 *
 * @author jo@Dev


 */

public interface DonationReceiptRepository extends JpaRepository<DonationReceipt, UUID> {
    List<DonationReceipt> findByCompanyId(UUID companyId);
    List<DonationReceipt> findByGrantId(UUID grantId);
}

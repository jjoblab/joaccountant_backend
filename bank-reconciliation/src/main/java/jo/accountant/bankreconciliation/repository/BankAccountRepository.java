package jo.accountant.bankreconciliation.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.bankreconciliation.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {
    List<BankAccount> findByCompanyId(UUID companyId);
}

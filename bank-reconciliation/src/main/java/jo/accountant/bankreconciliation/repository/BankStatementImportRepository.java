package jo.accountant.bankreconciliation.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.bankreconciliation.entity.BankStatementImport;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA BankStatementImport.
 *
 * @author jo@Dev


 */

public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, UUID> {
    List<BankStatementImport> findByBankAccountIdOrderByImportedAtDesc(UUID bankAccountId);
}

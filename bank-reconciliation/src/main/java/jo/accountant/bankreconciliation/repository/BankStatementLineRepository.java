package jo.accountant.bankreconciliation.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA BankStatementLine.
 *
 * @author jo@Dev


 */

public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, UUID> {

    /** Lignes non rapprochées d'un compte bancaire. */
    List<BankStatementLine> findByBankAccountIdAndMatchedFalse(UUID bankAccountId);

    /** Toutes les lignes d'un compte bancaire, triées par date. */
    List<BankStatementLine> findByBankAccountIdOrderByLineDate(UUID bankAccountId);

    /** Lignes non rapprochées d'un import. */
    List<BankStatementLine> findByImportIdAndMatchedFalse(UUID importId);
}

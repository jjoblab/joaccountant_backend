package jo.accountant.financialstatements.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.financialstatements.entity.FinancialStatementSnapshot;
import jo.accountant.financialstatements.entity.FinancialStatementType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des snapshots d'états financiers.
 
 *
 * @author jo@Dev


*/
public interface FinancialStatementSnapshotRepository
    extends JpaRepository<FinancialStatementSnapshot, UUID> {

    /** Tous les snapshots d'une entreprise, triés par date de génération décroissante. */
    List<FinancialStatementSnapshot> findByCompanyIdOrderByGeneratedAtDesc(UUID companyId);

    /** Snapshot par (companyId, type, periodId) — utilisé pour vérifier l'unicité. */
    Optional<FinancialStatementSnapshot> findByCompanyIdAndTypeAndPeriodId(
        UUID companyId, FinancialStatementType type, UUID periodId);
}

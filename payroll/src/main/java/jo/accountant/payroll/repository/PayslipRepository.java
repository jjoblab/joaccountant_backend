package jo.accountant.payroll.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.payroll.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA Payslip.
 *
 * @author jo@Dev


 */

public interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    List<Payslip> findByRunIdOrderByCreatedAt(UUID runId);

    Optional<Payslip> findByRunIdAndEmployeeId(UUID runId, UUID employeeId);

    /**
     * Reports Hub : Batch lookup des bulletins pour plusieurs
     * campagnes. Évite le N+1 pattern (1 query au lieu de ≤12) pour l'agrégation
     * CNSS_RETURN sur une période multi-mois.
     *
     * @param runIds collection d'IDs de PayrollRun (typiquement ≤12 pour 1 an d'historique)
     * @return tous les payslips des runs donnés, triés par createdAt (cohérent avec
     * {@link #findByRunIdOrderByCreatedAt(UUID)})
     */
    List<Payslip> findByRunIdInOrderByCreatedAt(Collection<UUID> runIds);
}

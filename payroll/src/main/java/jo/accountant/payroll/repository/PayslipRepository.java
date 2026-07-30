package jo.accountant.payroll.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.payroll.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    List<Payslip> findByRunIdOrderByCreatedAt(UUID runId);

    Optional<Payslip> findByRunIdAndEmployeeId(UUID runId, UUID employeeId);
}

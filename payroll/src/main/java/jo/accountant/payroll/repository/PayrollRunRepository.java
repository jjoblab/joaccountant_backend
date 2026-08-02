package jo.accountant.payroll.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.entity.PayrollRunType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    List<PayrollRun> findByCompanyIdOrderByPeriodYearDescPeriodMonthDesc(UUID companyId);

    Optional<PayrollRun> findByCompanyIdAndPeriodYearAndPeriodMonth(UUID companyId, int year, int month);

    /**
     * V86 — v7-4 : Recherche par (company, year, month, runType). Permet de co-exister
     * une campagne REGULAR et THIRTEENTH_MONTH en décembre de la même année.
     */
    Optional<PayrollRun> findByCompanyIdAndPeriodYearAndPeriodMonthAndRunType(
        UUID companyId, int year, int month, PayrollRunType runType);
}

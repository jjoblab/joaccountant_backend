package jo.accountant.employees.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    List<Employee> findByCompanyIdOrderByHireDateDesc(UUID companyId);

    List<Employee> findByCompanyIdAndStatus(UUID companyId, EmployeeStatus status);

    Optional<Employee> findByCompanyIdAndEmployeeNumber(UUID companyId, String employeeNumber);

    /** Utilisé par :payroll pour lister les salariés à payer sur une période. */
    List<Employee> findByCompanyIdAndStatusOrderByIdAsc(UUID companyId, EmployeeStatus status);

    /**
     * V75 / v8-7 — Liste les employés éligibles au 13e mois pour une année donnée.
     *
     * <p>Critères d'éligibilité (Code du Travail Haïti art. 153 + PayrollCalculator) :
     * <ul>
     *   <li>{@code company_id = :companyId} — tenant.</li>
     *   <li>{@code status = 'ACTIVE'} — employés actifs à la date du run.</li>
     *   <li>{@code thirteenth_month_eligible = true} — flag métier explicit (permet d'exclure
     *       les cadres dirigeants ou les consultants par exemple).</li>
     *   <li>{@code hire_date <= :hireDateCutoff} — embauché au plus tard le 31/12/{@code year}
     *       (sinon pas d'ancienneté sur l'année — pas de 13e mois dû).</li>
     * </ul>
     *
     * <p>Trié par {@code id} ASC pour un ordre stable (utile pour les logs et les tests
     * d'intégration — voir PayrollIntegrationTest).
     *
     * <p>Utilisé par :
     * <ul>
     *   <li>le reader Spring Batch {@code thirteenthMonthReader} de BatchConfig (v8-7 Étape 2) ;</li>
     *   <li>le runner async ThirteenthMonthAsyncRunner (v8-7 Étape 3, fallback @Async).</li>
     * </ul>
     */
    @Query("SELECT e FROM Employee e " +
           "WHERE e.companyId = :companyId " +
           "AND e.status = :status " +
           "AND e.thirteenthMonthEligible = true " +
           "AND e.hireDate <= :hireDateCutoff " +
           "ORDER BY e.id")
    List<Employee> findThirteenthMonthEligibleByCompanyId(
        @Param("companyId") UUID companyId,
        @Param("status") EmployeeStatus status,
        @Param("hireDateCutoff") LocalDate hireDateCutoff);
}

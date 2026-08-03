package jo.accountant.employees.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository JPA Employee.
 *
 * @author jo@Dev


 */

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    List<Employee> findByCompanyIdOrderByHireDateDesc(UUID companyId);

    List<Employee> findByCompanyIdAndStatus(UUID companyId, EmployeeStatus status);

    Optional<Employee> findByCompanyIdAndEmployeeNumber(UUID companyId, String employeeNumber);

    /** Utilisé par :payroll pour lister les salariés à payer sur une période. */
    List<Employee> findByCompanyIdAndStatusOrderByIdAsc(UUID companyId, EmployeeStatus status);

    // ── variantes paginées (fix mobile 2026-07-26 : GET /employees?page=&size=) ──

    /** Variante paginée — tous les employés de l'entreprise, triés par date d'embauche desc. */
    Page<Employee> findByCompanyId(UUID companyId, Pageable pageable);

    /** Variante paginée — employés par statut dans l'entreprise. */
    Page<Employee> findByCompanyIdAndStatus(UUID companyId, EmployeeStatus status, Pageable pageable);

    /**
     * V86 / v8-7 — Liste les employés éligibles au 13e mois pour une année donnée.
     *
     * <p>Critères d'éligibilité (Code du Travail Haïti art. 153 + PayrollCalculator) :
     * <ul>
     * <li>{@code company_id = :companyId} — tenant.</li>
     * <li>{@code status = 'ACTIVE'} — employés actifs à la date du run.</li>
     * <li>{@code thirteenth_month_eligible = true} — flag métier explicit (permet d'exclure
     * les cadres dirigeants ou les consultants par exemple).</li>
     * <li>{@code hire_date <= :hireDateCutoff} — embauché au plus tard le 31/12/{@code year}
     * (sinon pas d'ancienneté sur l'année — pas de 13e mois dû).</li>
     * </ul>
     *
     * <p>Trié par {@code id} ASC pour un ordre stable (utile pour les logs et les tests
     * d'intégration — voir PayrollIntegrationTest).
     *
     * <p>Utilisé par :
     * <ul>
     * <li>le reader Spring Batch {@code thirteenthMonthReader} de BatchConfig (v8-7;</li>
     * <li>le runner async ThirteenthMonthAsyncRunner (v8-7, fallback @Async).</li>
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

    /**
     * Task 16 : recherche full-text (case-insensitive) sur le nom du tiers
     * (employé), le matricule, le poste ou le département, pour la recherche globale
     * (Ctrl+K).
     *
     * <p>Le nom de l'employé vit sur le {@code ThirdParty} de type EMPLOYEE (FK
     * {@code third_party_id}). On fait donc une jointure native sur la table
     * {@code third_party} pour pouvoir chercher par nom.
     *
     * <p>Champs recherchés (OR) :
     * <ul>
     * <li>{@code third_party.name} — nom de l'employé (ex. « Jean Dupont ») ;</li>
     * <li>{@code employee.employee_number} — matricule (ex. « EMP-001 ») ;</li>
     * <li>{@code employee.position} — poste (ex. « Comptable senior ») ;</li>
     * <li>{@code employee.department} — département (ex. « Comptabilité »).</li>
     * </ul>
     *
     * @param companyId identifiant de l'entreprise (isolation multi-tenant)
     * @param q texte recherché (case-insensitive, partial match)
     * @param pageable pagination (typiquement {@code PageRequest.of(0, 5)})
     * @return page d'employés triés par date d'embauche décroissante
     */
    @Query(value = """
        SELECT e.* FROM employee e
        JOIN third_party tp ON tp.id = e.third_party_id
        WHERE e.company_id = :companyId
          AND (LOWER(tp.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(e.employee_number) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(e.position, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(e.department, '')) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY e.hire_date DESC
        """,
        nativeQuery = true,
        countQuery = """
        SELECT count(*) FROM employee e
        JOIN third_party tp ON tp.id = e.third_party_id
        WHERE e.company_id = :companyId
          AND (LOWER(tp.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(e.employee_number) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(e.position, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(e.department, '')) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    org.springframework.data.domain.Page<Employee> searchByNameOrNumberOrPosition(
        @Param("companyId") UUID companyId,
        @Param("q") String q,
        org.springframework.data.domain.Pageable pageable);
}

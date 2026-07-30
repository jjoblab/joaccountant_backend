package jo.accountant.expenses.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.expenses.entity.ExpenseLine;
import jo.accountant.expenses.entity.ExpenseReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseLineRepository extends JpaRepository<ExpenseLine, UUID> {

    List<ExpenseLine> findByReportIdOrderByCreatedAt(UUID reportId);

    /**
     * Somme des montants des lignes d'une catégorie donnée, pour les notes de frais d'une
     * entreprise dont la {@code expenseDate} est comprise dans une période donnée et dont le
     * statut n'est PAS dans la liste des statuts exclus (typiquement REJECTED).
     *
     * <p>Utilisé par la validation des plafonds journaliers/mensuels (Finding #19).
     *
     * @param companyId   identifiant de l'entreprise
     * @param category    code catégorie (TRAVEL, MEALS, SUPPLIES, OTHER, ou code personnalisé)
     * @param startDate   borne inférieure (inclusive) de la période
     * @param endDate     borne supérieure (inclusive) de la période
     * @param excludedStatuses statuts à exclure (ex: REJECTED)
     * @return somme des montants, ou 0 si aucune ligne ne matche
     */
    @Query("""
        SELECT COALESCE(SUM(l.amount), 0)
        FROM ExpenseLine l
        JOIN ExpenseReport r ON r.id = l.reportId
        WHERE l.companyId = :companyId
          AND l.category = :category
          AND r.expenseDate BETWEEN :startDate AND :endDate
          AND r.status NOT IN :excludedStatuses
        """)
    BigDecimal sumAmountByCategoryAndDateRange(@Param("companyId") UUID companyId,
                                                @Param("category") String category,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate,
                                                @Param("excludedStatuses") List<ExpenseReportStatus> excludedStatuses);
}

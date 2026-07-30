package jo.accountant.expenses.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.expenses.entity.ExpenseReport;
import jo.accountant.expenses.entity.ExpenseReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des notes de frais.
 *
 * <p><b>Finding #3 — pagination Pageable</b> : variantes paginées ({@code Page<>}) disponibles
 * pour l'endpoint {@code GET /expense-reports}. Les variantes {@code List<>} sont conservées
 * pour rétro-compatibilité (appels internes sans pagination).
 */
public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, UUID> {

    List<ExpenseReport> findByCompanyIdOrderByExpenseDateDesc(UUID companyId);

    List<ExpenseReport> findByCompanyIdAndStatus(UUID companyId, ExpenseReportStatus status);

    /**
     * Notes de frais d'une entreprise dont la {@code expenseDate} est comprise entre
     * {@code start} et {@code end} (inclus), triées par {@code expenseDate} décroissant.
     *
     * <p>Utilisé par {@code GET /expense-reports?fiscalYearId=} (restructuration 2026-07-25
     * suite 4) pour filtrer les notes par exercice fiscal.
     */
    List<ExpenseReport> findByCompanyIdAndExpenseDateBetweenOrderByExpenseDateDesc(
        UUID companyId, LocalDate start, LocalDate end);

    // ── Finding #3 — variantes paginées (rétro-compat : les méthodes List<> ci-dessus sont conservées) ──

    /** Variante paginée — toutes les notes de frais, triées par expenseDate desc. */
    Page<ExpenseReport> findByCompanyIdOrderByExpenseDateDesc(UUID companyId, Pageable pageable);

    /** Variante paginée — notes de frais d'un exercice fiscal (between start/end), triées par expenseDate desc. */
    Page<ExpenseReport> findByCompanyIdAndExpenseDateBetweenOrderByExpenseDateDesc(
        UUID companyId, LocalDate start, LocalDate end, Pageable pageable);
}

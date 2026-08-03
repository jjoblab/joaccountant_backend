package jo.accountant.invoicing.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.Invoice;
import jo.accountant.invoicing.entity.InvoiceDirection;
import jo.accountant.invoicing.entity.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * InvoiceRepository — repository pour l'entité unifiée {@link Invoice} (v9.1).
 *
 * <p>Remplace définitivement {@code SalesInvoiceRepository} et
 * {@code PurchaseInvoiceRepository}.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Liste toutes les factures d'une entreprise (toutes directions confondues). */
    List<Invoice> findByCompanyIdOrderByIssueDateDesc(UUID companyId);

    /** Liste paginée (toutes directions). */
    Page<Invoice> findByCompanyIdOrderByIssueDateDesc(UUID companyId, Pageable pageable);

    /** Liste par direction (SALES ou PURCHASE). */
    List<Invoice> findByCompanyIdAndDirectionOrderByIssueDateDesc(
            UUID companyId, InvoiceDirection direction);

    /** Liste paginée par direction. */
    Page<Invoice> findByCompanyIdAndDirectionOrderByIssueDateDesc(
            UUID companyId, InvoiceDirection direction, Pageable pageable);

    /** Liste par direction + statut. */
    List<Invoice> findByCompanyIdAndDirectionAndStatusOrderByIssueDateDesc(
            UUID companyId, InvoiceDirection direction, InvoiceStatus status);

    /** Liste filtrée par exercice fiscal (issueDate entre start et end). */
    Page<Invoice> findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
            UUID companyId, LocalDate start, LocalDate end, Pageable pageable);

    /** Recherche par numéro de facture (pour global search). */
    @Query("SELECT i FROM Invoice i WHERE i.companyId = :companyId " +
           "AND LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Invoice> searchByNumber(@Param("companyId") UUID companyId,
                                  @Param("query") String query);

    /** Keyset pagination — factures après une date/ID donnés. */
    @Query("SELECT i FROM Invoice i WHERE i.companyId = :companyId " +
           "AND (i.issueDate < :afterIssueDate OR " +
           "     (i.issueDate = :afterIssueDate AND i.id > :afterId)) " +
           "ORDER BY i.issueDate DESC, i.id ASC")
    List<Invoice> findKeysetAfter(@Param("companyId") UUID companyId,
                                   @Param("afterIssueDate") LocalDate afterIssueDate,
                                   @Param("afterId") UUID afterId,
                                   Pageable pageable);

    /** Somme des montants pour une période (pour acompte IS 1% Haïti). */
    @Query("SELECT coalesce(sum(i.totalAmount), 0) FROM Invoice i " +
           "WHERE i.companyId = :companyId " +
           "AND i.direction = :direction " +
           "AND i.status IN :statuses " +
           "AND i.issueDate >= coalesce(:from, i.issueDate) " +
           "AND i.issueDate <= coalesce(:to, i.issueDate)")
    java.math.BigDecimal sumTotalAmountByCompanyIdAndIssueDateBetweenAndStatusIn(
            @Param("companyId") UUID companyId,
            @Param("direction") InvoiceDirection direction,
            @Param("statuses") List<InvoiceStatus> statuses,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Compte par direction (pour stats dashboard). */
    long countByCompanyIdAndDirection(UUID companyId, InvoiceDirection direction);

    long countByCompanyIdAndDirectionAndStatus(UUID companyId,
                                                InvoiceDirection direction,
                                                InvoiceStatus status);

    /** Factures avec retenue à la source dans une période (pour déclaration RS). */
    @Query("SELECT i FROM Invoice i WHERE i.companyId = :companyId " +
           "AND i.direction = 'SALES' " +
           "AND i.withholdingAmount IS NOT NULL " +
           "AND i.withholdingAmount > 0 " +
           "AND i.issueDate >= coalesce(:from, i.issueDate) " +
           "AND i.issueDate <= coalesce(:to, i.issueDate) " +
           "ORDER BY i.issueDate DESC")
    List<Invoice> findWithholdingInvoicesInPeriod(
            @Param("companyId") UUID companyId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Avoirs liés à une facture originale (anti-fraud ceiling check). */
    List<Invoice> findByCompanyIdAndTypeAndCreditNoteForInvoiceId(
            UUID companyId, jo.accountant.invoicing.entity.InvoiceType type,
            UUID creditNoteForInvoiceId);
}

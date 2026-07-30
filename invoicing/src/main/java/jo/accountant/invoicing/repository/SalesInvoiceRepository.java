package jo.accountant.invoicing.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.SalesInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des factures de ventes.
 *
 * <p><b>Audit v4.7 §7.2 Finding #5 — pagination</b> : la v4.7 retournait {@code List<>} complet
 * sur l'endpoint {@code GET /invoices}. Sur une entreprise mature (5 ans d'historique =
 * potentiellement des milliers de factures), cela causait des timeout/OOM côté mobile.
 *
 * <p>Deux variantes Paginées ({@code Page<>}) sont désormais disponibles pour les clients qui
 * veulent contrôler la pagination. Les variantes {@code List<>} sont conservées pour
 * rétro-compatibilité, mais un hard cap (200 items) est appliqué au niveau service pour
 * empêcher l'OOM — voir {@code InvoicingService.listInvoices}.
 */
public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, UUID> {
    List<SalesInvoice> findByCompanyIdOrderByIssueDateDesc(UUID companyId);
    List<SalesInvoice> findByCompanyIdAndStatus(UUID companyId, jo.accountant.invoicing.entity.InvoiceStatus status);

    /**
     * Variante paginée — pour les clients qui implémentent la pagination côté UI.
     * Hard cap recommandé côté service : size ≤ 200.
     */
    Page<SalesInvoice> findByCompanyIdOrderByIssueDateDesc(UUID companyId, Pageable pageable);

    /**
     * Variante paginée par exercice fiscal — évite de charger toutes les factures puis filtrer.
     */
    Page<SalesInvoice> findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
        UUID companyId, LocalDate from, LocalDate to, Pageable pageable);

    /**
     * Liste tous les avoirs (CREDIT_NOTE) rattachés à une facture originale donnée.
     *
     * <p><b>Audit v4.7 §4.2 Finding MOYENNE — anti-fraude avoirs</b> : utilisé par
     * {@code InvoicingService.createCreditNote} pour vérifier que le total des avoirs déjà
     * émis ne dépasse pas le total de la facture originale. Sans cette vérification, un
     * utilisateur pouvait créer N avoirs pour la même facture — chacun à 100% du montant —
     * et rembourser le client N× le montant de la facture (fraude).
     *
     * <p>On filtre par {@code type = 'CREDIT_NOTE'} et {@code creditNoteForInvoiceId = ?} pour
     * récupérer uniquement les avoirs liés (les factures normales ont
     * {@code creditNoteForInvoiceId = null}).
     */
    List<SalesInvoice> findByCompanyIdAndTypeAndCreditNoteForInvoiceId(
        UUID companyId, jo.accountant.invoicing.entity.InvoiceType type, UUID creditNoteForInvoiceId);

    /**
     * R-F-validation v6-2 — Liste les factures de ventes (STANDARD + CREDIT_NOTE) portant une
     * retenue à la source (withholding_amount > 0) sur une période donnée, pour la construction
     * de la déclaration RS mensuelle DGI ({@code TaxService.getWithholdingDeclaration}).
     *
     * <p>Filtres :
     * <ul>
     *   <li>{@code companyId} — isolation multi-tenant</li>
     *   <li>{@code status IN (ISSUED, PARTIALLY_PAID, PAID)} — on exclut les DRAFT et VOID</li>
     *   <li>{@code issue_date BETWEEN from AND to} — période de la déclaration (mois M)</li>
     *   <li>{@code withholding_amount > 0} — uniquement les factures avec RS appliquée</li>
     * </ul>
     *
     * <p>Les avoirs (CREDIT_NOTE) sont inclus : la RS négative d'un avoir vient compenser la RS
     * positive de la facture originale dans l'agrégation par taux.
     *
     * <p>Note : une requête JPQL custom est nécessaire car {@code withholding_amount} est un
     * BigDecimal nullable — Spring Data JPA ne propose pas de méthode dérivée directe pour
     * « > 0 » sur un BigDecimal nullable.
     */
    @Query("SELECT si FROM SalesInvoice si " +
           "WHERE si.companyId = :companyId " +
           "AND si.status IN :statuses " +
           "AND si.issueDate BETWEEN :from AND :to " +
           "AND si.withholdingAmount IS NOT NULL " +
           "AND si.withholdingAmount > 0 " +
           "ORDER BY si.issueDate ASC, si.invoiceNumber ASC")
    List<SalesInvoice> findWithholdingInvoicesInPeriod(
        @Param("companyId") UUID companyId,
        @Param("statuses") List<jo.accountant.invoicing.entity.InvoiceStatus> statuses,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    /**
     * V6-5 (acompte IS 1% mensuel Haïti) — Somme des montants totaux des factures
     * sur une période, filtrées par statut.
     *
     * <p>Utilisé par {@code TaxService.computeMonthlyInstallmentHT} pour calculer
     * l'acompte IS 1% sur encaissements bruts (Code Fiscal Haïti art. 5).
     *
     * @param companyId ID de l'entreprise
     * @param from date de début (inclusive)
     * @param to date de fin (inclusive)
     * @param statuses liste des statuts à inclure (ISSUED, PARTIALLY_PAID, PAID)
     * @return Optional contenant la somme, ou vide si aucune facture
     */
    @Query("SELECT COALESCE(SUM(si.totalAmount), 0) FROM SalesInvoice si " +
           "WHERE si.companyId = :companyId " +
           "AND si.status IN :statuses " +
           "AND si.issueDate BETWEEN :from AND :to")
    java.util.Optional<java.math.BigDecimal> sumTotalAmountByCompanyIdAndIssueDateBetweenAndStatusIn(
        @Param("companyId") UUID companyId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to,
        @Param("statuses") List<String> statuses);

    /**
     * V7-8 — Pagination keyset (curseur) sur les factures de ventes d'une entreprise.
     *
     * <p>Contrairement à la pagination OFFSET classique ({@link #findByCompanyIdOrderByIssueDateDesc(UUID, Pageable)}),
     * la pagination keyset conserve une latence constante sur les pages profondes — essentielle
     * pour Caribbean Textiles (50 000+ factures/an, 250 000+ sur 5 ans).
     *
     * <p>Le curseur est composé de {@code (issueDate, id)} — paire qui garantit un ordre total
     * déterministe même si plusieurs factures partagent la même date d'émission.
     *
     * <p>Usage côté client :
     * <pre>
     *   // Première page
     *   GET /invoices/keyset?size=50
     *   // → retourne { content: [...], nextAfterIssueDate, nextAfterId, hasNext: true }
     *
     *   // Page suivante
     *   GET /invoices/keyset?size=50&afterIssueDate=2026-07-15&afterId=0192c0...
     * </pre>
     *
     * @param companyId      identifiant de l'entreprise (isolation multi-tenant)
     * @param afterIssueDate date de début du curseur (exclusive), ou null pour la première page
     * @param afterId        ID de début du curseur (exclusive), ou null pour la première page
     * @param pageable       pagination (typiquement PageRequest.of(0, 50))
     * @return liste ordonnée par (issueDate DESC, id DESC)
     */
    @Query("""
        SELECT si FROM SalesInvoice si
        WHERE si.companyId = :companyId
          AND (:afterIssueDate IS NULL OR (si.issueDate, si.id) < (:afterIssueDate, :afterId))
        ORDER BY si.issueDate DESC, si.id DESC
        """)
    List<SalesInvoice> findKeysetAfter(
        @Param("companyId") UUID companyId,
        @Param("afterIssueDate") LocalDate afterIssueDate,
        @Param("afterId") UUID afterId,
        Pageable pageable
    );
}

package jo.accountant.invoicing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.InvoiceLine;
import jo.accountant.invoicing.entity.InvoiceLineTax;
import jo.accountant.invoicing.entity.InvoiceLineTaxType;
import jo.accountant.invoicing.entity.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository des lignes de taxe associées aux {@code InvoiceLine} (v6-1-multi-tax-invoice-line).
 *
 * <p>Permet de charger les taxes d'une ligne (ou d'un batch de lignes) pour :
 * <ul>
 *   <li>la génération de l'écriture comptable multi-taxes ({@code InvoicingService.generateInvoiceEntry})</li>
 *   <li>la lecture de la réponse API ({@code InvoiceResponse.LineResponse.taxes})</li>
 *   <li>l'agrégation déclarative par {@code taxType} ({@code TaxService.getDeclaration(taxType)})</li>
 * </ul>
 */
public interface InvoiceLineTaxRepository extends JpaRepository<InvoiceLineTax, UUID> {

    /**
     * Charge toutes les taxes d'une ligne de facture.
     *
     * @param invoiceLineId ID de la ligne parente
     * @return liste triée par {@code displayOrder} puis {@code id} (ordre stable pour rendu PDF)
     */
    List<InvoiceLineTax> findByInvoiceLineIdOrderByDisplayOrderAscIdAsc(UUID invoiceLineId);

    /**
     * Charge toutes les taxes d'un ensemble de lignes (batch loading — évite le N+1 quand on
     * charge une facture avec 50 lignes : 1 SELECT au lieu de 50).
     *
     * @param invoiceLineIds collection d'IDs de lignes
     * @return liste plate (à regrouper par {@code invoiceLineId} côté appelant si besoin)
     */
    List<InvoiceLineTax> findByInvoiceLineIdInOrderByDisplayOrderAscIdAsc(Collection<UUID> invoiceLineIds);

    /**
     * Supprime toutes les taxes d'une ligne (utilisé en réécriture lors d'une édition de facture
     * DRAFT — actuellement InvoicingService ne permet pas d'éditer une ligne existante, mais le
     * repo expose la méthode pour les évolutions futures).
     *
     * @param invoiceLineId ID de la ligne parente
     */
    @Transactional
    void deleteByInvoiceLineId(UUID invoiceLineId);

    /**
     * v6-1 — Agrégation par taux de taxe, FILTRÉE par {@code taxType}, côté SQL.
     *
     * <p>Contrairement à {@code InvoiceLineRepository.aggregateByTaxRate} (qui agrège par
     * {@code invoice_line.tax_rate} sans distinction de type), cette méthode agrège les
     * {@link InvoiceLineTax} en filtrant par {@code tax_type}. Elle est utilisée par
     * {@code TaxService.getDeclaration(companyId, from, to, taxType)} pour produire 2 déclarations
     * DGI distinctes (TVA + TCA) au lieu d'une seule fusionnée.
     *
     * <p>L'agrégation se fait sur les taxes dont la {@link InvoiceLine} parente appartient à
     * l'entreprise, a un statut dans {@code statuses}, et une date d'émission dans la plage
     * [{@code from}, {@code to}].
     *
     * <p>Les taux à 0% sont inclus (le caller les filtre si besoin).
     *
     * @param companyId identifiant de l'entreprise (sécurité multi-tenant)
     * @param from      date d'émission minimum (inclusive), null = pas de borne basse
     * @param to        date d'émission maximum (inclusive), null = pas de borne haute
     * @param statuses  liste des statuts de facture à inclure (ne doit pas être vide)
     * @param taxType   type de taxe à filtrer (VAT, TCA, TURNOVER_TAX, EXCISE) — ne doit pas être null
     * @return agrégats par taux de taxe (pour le taxType spécifié)
     */
    @Query("select t.rate AS taxRate, " +
           "coalesce(sum(t.taxableBase), 0) AS totalHt, " +
           "coalesce(sum(t.taxAmount), 0) AS totalTax " +
           "from InvoiceLineTax t " +
           "where t.taxType = :taxType " +
           "and t.invoiceLineId in (" +
           "  select l.id from InvoiceLine l " +
           "  where l.invoiceId in (" +
           "    select s.id from SalesInvoice s " +
           "    where s.status in :statuses " +
           "    and (:from is null or s.issueDate >= :from) " +
           "    and (:to is null or s.issueDate <= :to)" +
           "  )" +
           ") " +
           "group by t.rate " +
           "order by t.rate")
    List<TaxTypeRateAggregate> aggregateByTaxType(@Param("companyId") UUID companyId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to,
                                                    @Param("statuses") List<InvoiceStatus> statuses,
                                                    @Param("taxType") InvoiceLineTaxType taxType);

    /**
     * v6-1 — Projection pour l'agrégation SQL par taux de taxe filtrée par taxType.
     *
     * <p>Même structure que {@code InvoiceLineRepository.TaxRateAggregate} mais les montants
     * proviennent de {@link InvoiceLineTax} (table {@code invoice_line_tax}) au lieu de
     * {@link InvoiceLine} (table {@code invoice_line}).
     */
    interface TaxTypeRateAggregate {
        BigDecimal getTaxRate();
        BigDecimal getTotalHt();
        BigDecimal getTotalTax();
    }
}


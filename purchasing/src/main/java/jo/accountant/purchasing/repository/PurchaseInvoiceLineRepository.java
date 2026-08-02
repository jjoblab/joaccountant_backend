package jo.accountant.purchasing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.purchasing.entity.PurchaseInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseInvoiceLineRepository extends JpaRepository<PurchaseInvoiceLine, UUID> {

 List<PurchaseInvoiceLine> findByInvoiceIdOrderByCreatedAt(UUID invoiceId);

 /**
 * (lot-C-perf-devops) — Agrégation par taux de TVA côté SQL (côté achats).
 *
 * <p>Équivalent de {@code InvoiceLineRepository.aggregateByTaxRate} mais pour les factures
 * d'achat. Permet le calcul de la TVA déductible en une seule requête SQL GROUP BY au lieu
 * du pattern N+1 historique.
 *
 * @param companyId identifiant de l'entreprise (sécurité multi-tenant)
 * @param from date d'émission minimum (inclusive), null = pas de borne basse
 * @param to date d'émission maximum (inclusive), null = pas de borne haute
 * @param statuses liste des statuts de facture à inclure (ne doit pas être vide)
 * @return agrégats par taux de TVA
 */
 @Query("select l.taxRate AS taxRate, " +
 "coalesce(sum(l.lineTotalHt), 0) AS totalHt, " +
 "coalesce(sum(l.lineTotalTax), 0) AS totalTax " +
 "from PurchaseInvoiceLine l " +
 "where l.companyId = :companyId " +
 "and l.invoiceId in (" +
 " select p.id from PurchaseInvoice p " +
 " where p.status in :statuses " +
 " and p.issueDate >= coalesce(:from, p.issueDate) " +
 " and p.issueDate <= coalesce(:to, p.issueDate)" +
 ") " +
 "group by l.taxRate " +
 "order by l.taxRate")
 List<TaxRateAggregate> aggregateByTaxRate(@Param("companyId") UUID companyId,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to,
 @Param("statuses") List<jo.accountant.purchasing.entity.PurchaseInvoiceStatus> statuses);

 /**
 * (lot-C-perf-devops) — Projection pour l'agrégation SQL par taux de TVA (achats).
 */
 interface TaxRateAggregate {
 BigDecimal getTaxRate();
 BigDecimal getTotalHt();
 BigDecimal getTotalTax();
 }
}

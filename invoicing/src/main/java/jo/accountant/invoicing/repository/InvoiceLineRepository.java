package jo.accountant.invoicing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.InvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository JPA InvoiceLine.
 *
 * @author jo@Dev


 */

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {
 List<InvoiceLine> findByInvoiceIdOrderByCreatedAt(UUID invoiceId);

 /**
 *Agrégation par taux de TVA côté SQL.
 *
 * <p>Remplace l'ancien pattern N+1 de {@code TaxService.getDeclaration()} qui chargeait
 * chaque facture individuellement puis bouclait sur ses lignes en Java. Sur 1000 factures,
 * l'ancien code faisait 3 SELECT (par statut) + 1000 SELECT (par facture) = 1003 requêtes.
 * Cette méthode fait 1 seule requête SQL GROUP BY.
 *
 * <p>L'agrégation se fait sur les lignes dont la facture parente appartient à l'entreprise,
 * a un statut dans {@code statuses}, et une date d'émission dans la plage [{@code from}, {@code to}].
 * Les taux à 0% sont inclus (le caller les filtre si besoin — la TVA collectée à 0% n'a pas
 * de sens, mais le SQL reste générique).
 *
 * <p>Note : pas de JOIN {@code l.invoice} car il n'y a pas de relation {@code @ManyToOne}
 * entre {@link InvoiceLine} et {@code SalesInvoice} (seulement un champ {@code invoiceId}
 * UUID). On utilise un sous-select JPQL sur l'ID — fonctionnellement équivalent au JOIN,
 * légèrement moins optimal (mais Hibernate 6 génère un semi-join qui est aussi performant).
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
 "from InvoiceLine l " +
 "where l.companyId = :companyId " +
 "and l.invoiceId in (" +
 " select s.id from Invoice s " +
 " where s.direction = :direction " +
 " and s.status in :statuses " +
 " and s.issueDate >= coalesce(:from, s.issueDate) " +
 " and s.issueDate <= coalesce(:to, s.issueDate)" +
 ") " +
 "group by l.taxRate " +
 "order by l.taxRate")
 List<TaxRateAggregate> aggregateByTaxRate(@Param("companyId") UUID companyId,
 @Param("direction") String direction,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to,
 @Param("statuses") List<jo.accountant.invoicing.entity.InvoiceStatus> statuses);


 /**
 *Projection pour l'agrégation SQL par taux de TVA.
 *
 * <p>Interface de projection Spring Data JPA — pas de classe concrète, Hibernate génère
 * un proxy à runtime. Les alias ({@code AS taxRate}, etc.) doivent correspondre exactement
 * aux noms de méthodes.
 */
 interface TaxRateAggregate {
 BigDecimal getTaxRate();
 BigDecimal getTotalHt();
 BigDecimal getTotalTax();
 }
}

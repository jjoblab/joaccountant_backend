package jo.accountant.tax.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.tax.entity.TaxRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository JPA TaxRule.
 *
 * @author jo@Dev


 */

public interface TaxRuleRepository extends JpaRepository<TaxRule, UUID> {
    List<TaxRule> findByCompanyIdOrCompanyIdIsNull(UUID companyId);
    List<TaxRule> findByCompanyId(UUID companyId);

    /**
     * Règles de TVA actives ET valides à une date donnéeFinding HAUT).
     *
     * <p>Filtre par {@code applicableFrom <= date <= applicableTo} (avec null = non borné).
     * Une règle avec {@code applicableFrom=null, applicableTo=null} est toujours valide.
     * Une règle avec {@code applicableTo < date} est expirée (ex: taux réduit COVID 5.5% en 2022).
     *
     * @param companyId identifiant de l'entreprise (les règles globales companyId IS NULL sont incluses)
     * @param date date de référence (ex: date d'émission de la facture)
     * @return règles actives et valides à la date donnée
     */
    @Query("select r from TaxRule r where " +
           "(r.companyId = :companyId or r.companyId is null) " +
           "and r.active = true " +
           "and (r.applicableFrom is null or r.applicableFrom <= :date) " +
           "and (r.applicableTo is null or r.applicableTo >= :date) " +
           "order by r.code")
    List<TaxRule> findActiveRulesValidAt(@Param("companyId") UUID companyId,
                                          @Param("date") LocalDate date);
}

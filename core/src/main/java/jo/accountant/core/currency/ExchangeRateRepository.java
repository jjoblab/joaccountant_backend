package jo.accountant.core.currency;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository JPA ExchangeRate.
 *
 * @author jo@Dev


 */

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    /**
     * Trouve le taux de change applicable à une date donnée (le plus récent ≤ asOfDate).
     */
    @Query("select e from ExchangeRate e where e.companyId = :companyId " +
           "and e.fromCurrency = :from and e.toCurrency = :to " +
           "and e.asOfDate <= :asOf " +
           "order by e.asOfDate desc limit 1")
    Optional<ExchangeRate> findApplicableRate(
        @Param("companyId") UUID companyId,
        @Param("from") String fromCurrency,
        @Param("to") String toCurrency,
        @Param("asOf") LocalDate asOfDate);
}

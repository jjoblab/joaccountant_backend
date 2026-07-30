package jo.accountant.fxoperations.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.fxoperations.entity.ExchangeRateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des snapshots de taux de change (Task v6-4-presentation-currency).
 *
 * <p>Permet de retrouver le taux de clôture (bilan IAS 21) ou le taux moyen de période
 * (compte de résultat / tableau de flux) pour convertir les états financiers de la devise
 * fonctionnelle vers la devise de présentation.
 */
public interface ExchangeRateSnapshotRepository extends JpaRepository<ExchangeRateSnapshot, UUID> {

    /** Recherche exacte par (company, from, to, rate_date). */
    Optional<ExchangeRateSnapshot> findByCompanyIdAndFromCurrencyAndToCurrencyAndRateDate(
        UUID companyId, String fromCurrency, String toCurrency, LocalDate rateDate);

    /**
     * Dernier taux de clôture (snapshot_type = CLOSING) à ou avant {@code asOfDate}.
     *
     * <p>Utilisé pour le bilan : on prend le taux le plus récent disponible à la date de
     * clôture (au sens IAS 21 — taux de clôture pour les postes monétaires et les postes
     * non-monétaires au coût historique en devise).
     */
    @Query("SELECT s FROM ExchangeRateSnapshot s " +
           "WHERE s.companyId = :companyId " +
           "  AND s.fromCurrency = :fromCurrency " +
           "  AND s.toCurrency = :toCurrency " +
           "  AND s.snapshotType = 'CLOSING' " +
           "  AND s.rateDate <= :asOfDate " +
           "ORDER BY s.rateDate DESC, s.createdAt DESC")
    Optional<ExchangeRateSnapshot> findLatestClosingRate(
        @Param("companyId") UUID companyId,
        @Param("fromCurrency") String fromCurrency,
        @Param("toCurrency") String toCurrency,
        @Param("asOfDate") LocalDate asOfDate);

    /**
     * Taux moyen mensuel (snapshot_type = PERIOD_AVERAGE) pour un mois donné.
     *
     * <p>Utilisé pour le compte de résultat et le tableau de flux de trésorerie (IAS 21 —
     * taux moyen pour les flux de l'exercice).
     */
    @Query("SELECT s FROM ExchangeRateSnapshot s " +
           "WHERE s.companyId = :companyId " +
           "  AND s.fromCurrency = :fromCurrency " +
           "  AND s.toCurrency = :toCurrency " +
           "  AND s.snapshotType = 'PERIOD_AVERAGE' " +
           "  AND s.periodYear = :year " +
           "  AND s.periodMonth = :month")
    Optional<ExchangeRateSnapshot> findAverageRateForPeriod(
        @Param("companyId") UUID companyId,
        @Param("fromCurrency") String fromCurrency,
        @Param("toCurrency") String toCurrency,
        @Param("year") int year,
        @Param("month") int month);
}

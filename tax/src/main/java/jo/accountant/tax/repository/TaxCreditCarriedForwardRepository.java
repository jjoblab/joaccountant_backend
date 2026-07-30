package jo.accountant.tax.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.tax.entity.TaxCreditCarriedForward;
import jo.accountant.tax.entity.TaxType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository des crédits de TVA reportés (Lot B R-23).
 *
 * <p>Permet de lire le crédit de la période précédente pour pré-remplir la déclaration
 * courante, et de persister le crédit de fin de période pour la période suivante.
 */
@Repository
public interface TaxCreditCarriedForwardRepository
    extends JpaRepository<TaxCreditCarriedForward, UUID> {

    /**
     * Récupère le crédit reporté pour une période donnée (lecture au début de la
     * déclaration courante pour appliquer le crédit à la TVA due).
     *
     * @param companyId    l'entreprise
     * @param taxType      type de taxe (VAT, TCA...)
     * @param periodYear   année de la période
     * @param periodMonth  mois de la période (1-12)
     * @return le crédit reporté, ou empty si aucun crédit n'a été persisté
     */
    Optional<TaxCreditCarriedForward> findByCompanyIdAndTaxTypeAndPeriodYearAndPeriodMonth(
        UUID companyId, TaxType taxType, int periodYear, int periodMonth);
}

package jo.accountant.payroll.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.payroll.entity.OfatmaSectorRate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * V89 — v7-6 : Repository des taux OFATMA Accidents par secteur.
 *
 * <p>Les taux sont des données de référence (peu de modifications — environ 1× par an
 * lors d'une mise à jour OFATMA). Pas de cache applicatif dédié — le cache Hibernate L2
 * (si activé) suffira, sinon la requête est indexée sur sector_code (UNIQUE).
 */
public interface OfatmaSectorRateRepository extends JpaRepository<OfatmaSectorRate, UUID> {

    /**
     * Recherche un taux actif par code secteur.
     * Utilisé par {@code PayrollCalculator.resolveOfatmaAccidentRate}.
     */
    Optional<OfatmaSectorRate> findBySectorCodeAndActiveTrue(String sectorCode);
}

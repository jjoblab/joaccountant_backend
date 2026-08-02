package jo.accountant.company.repository;

import java.util.List;
import java.util.Optional;
import jo.accountant.company.entity.BusinessType;
import jo.accountant.company.entity.Sector;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA BusinessType.
 *
 * @author jo@Dev


 */

public interface BusinessTypeRepository extends JpaRepository<BusinessType, String> {

    List<BusinessType> findByActiveTrueOrderByCodeAsc();

    Optional<BusinessType> findByCodeAndActiveTrue(String code);

    /**
     * Liste des types métier actifs dont le secteur par défaut correspond au secteur demandé.
     * Utilisé par le filtre {@code GET /api/v1/business-types?sector=...} (restructuration
     * 2026-07-24 — Partie A §1.1) : le mobile appelle ce endpoint avec le {@code sector}
     * choisi à l'du wizard pour peupler l'*/
    List<BusinessType> findByActiveTrueAndDefaultSectorOrderByCodeAsc(Sector sector);
}

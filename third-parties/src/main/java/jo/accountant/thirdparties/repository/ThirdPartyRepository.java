package jo.accountant.thirdparties.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des tiers.
 *
 * <p><b>pagination Pageable</b> : variantes paginées ({@code Page<>}) disponibles
 * pour les endpoints volumineux. Les variantes {@code List<>} sont conservées pour
 * rétro-compatibilité (appels internes sans pagination).
 */
public interface ThirdPartyRepository extends JpaRepository<ThirdParty, UUID> {

 /** Tous les tiers de l'entreprise, triés par nom. */
 List<ThirdParty> findByCompanyIdOrderByName(UUID companyId);

 /** Tiers par type, dans l'entreprise. */
 List<ThirdParty> findByCompanyIdAndTypeOrderByName(UUID companyId, ThirdPartyType type);

 /** Recherche par nom (case-insensitive, partial match). */
 List<ThirdParty> findByCompanyIdAndNameContainingIgnoreCaseOrderByName(UUID companyId, String name);

 /** Tiers actifs uniquement. */
 List<ThirdParty> findByCompanyIdAndActiveTrueOrderByName(UUID companyId);

 // ── variantes paginées (rétro-compat : les méthodes List<> ci-dessus sont conservées) ──

 /** Variante paginée — tous les tiers de l'entreprise, triés par nom. */
 Page<ThirdParty> findByCompanyIdOrderByName(UUID companyId, Pageable pageable);

 /** Variante paginée — tiers par type, dans l'entreprise. */
 Page<ThirdParty> findByCompanyIdAndTypeOrderByName(UUID companyId, ThirdPartyType type, Pageable pageable);
}

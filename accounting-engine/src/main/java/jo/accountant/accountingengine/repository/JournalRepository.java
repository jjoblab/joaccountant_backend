package jo.accountant.accountingengine.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.entity.Journal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des journaux.
 *
 * <p><b>Audit v4.7 §7.2 Cache applicatif</b> : lookup par {@code (companyId, code)}
 * mis en cache ({@code @Cacheable("journals")}). Utilisé par chaque opération d'écriture
 * (factures, achats, paie, OD) — sans cache, 1000 opérations/jour × 1 SELECT journal = 1000
 * SELECT inutiles. Les journaux changent rarement (création à l'initialisation du tenant,
 * modifications ponctuelles).
 *
 * <p>Invalidation via {@code @CacheEvict(value = "journals", allEntries = true)} sur les
 * mutations — placée dans le service qui crée/modifie les journaux (pas sur {@code save()}
 * hérité de {@code JpaRepository}).
 */
public interface JournalRepository extends JpaRepository<Journal, UUID> {

 /** Journal par code, dans l'entreprise donnée. */
 @Cacheable(value = "journals", key = "#companyId.toString() + ':' + #code")
 Optional<Journal> findByCompanyIdAndCode(UUID companyId, String code);

 /** Tous les journaux de l'entreprise. */
 List<Journal> findByCompanyIdOrderByCode(UUID companyId);
}

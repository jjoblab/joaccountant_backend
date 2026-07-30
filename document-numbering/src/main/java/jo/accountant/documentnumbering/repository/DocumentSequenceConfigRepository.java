package jo.accountant.documentnumbering.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.documentnumbering.entity.DocumentSequenceConfig;
import jo.accountant.documentnumbering.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des configurations de séquences documentaires.
 *
 * <p>L'isolation multi-tenant est faite explicitement par le service via {@code companyId} —
 * {@link jo.accountant.core.tenant.TenantAwareEntityListener} injecte déjà le companyId à
 * l'insertion. Hibernate {@code @TenantId} n'est pas activé globalement en Phase 2 (refactor
 * commun à toutes les entités, à faire en Phase 3 ou ultérieurement).
 */
public interface DocumentSequenceConfigRepository extends JpaRepository<DocumentSequenceConfig, UUID> {

    /** Recherche la configuration unique par (companyId, documentType, scopeKey). */
    Optional<DocumentSequenceConfig> findByCompanyIdAndDocumentTypeAndScopeKey(
        UUID companyId, DocumentType documentType, String scopeKey);

    /** Toutes les configurations du tenant donné, triées par type puis scopeKey. */
    List<DocumentSequenceConfig> findByCompanyIdOrderByDocumentTypeAscScopeKeyAsc(UUID companyId);
}

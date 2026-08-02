package jo.accountant.thirdparties.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jo.accountant.thirdparties.entity.LettrageMatch;
import jo.accountant.thirdparties.entity.LettrageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des lettrages.
 *
 * <p><b>Finding MOYENNE</b> : les méthodes actives filtrent
 * {@code status != DELETED} pour exclure les lettrages soft-deletés des requêtes métier.
 * Les lettrages DELETED restent consultables via {@link JpaRepository#findById} pour forensique.
 
 *
 * @author jo@Dev


*/
public interface LettrageMatchRepository extends JpaRepository<LettrageMatch, UUID> {

    /**
     * Tous les lettrages ACTIFS (non DELETED) d'un tiers, triés par date de lettrage décroissante.
     *— exclut les lettrages soft-deletés.
     */
    List<LettrageMatch> findByCompanyIdAndThirdPartyIdAndStatusNotOrderByMatchedAtDesc(
        UUID companyId, UUID thirdPartyId, LettrageStatus status);

    /**
     * Compte les lettrages ACTIFS (non DELETED) d'un tiers — utilisé pour générer le code de
     * lettrage séquentiel.— exclut les lettrages soft-deletés.
     */
    long countByCompanyIdAndThirdPartyIdAndStatusNot(UUID companyId, UUID thirdPartyId, LettrageStatus status);

    // --- Méthodes de compatibilité (incluent DELETED — à éviter en métier, utiliser pour admin/forensique) ---

    /** Tous les lettrages d'un tiers (inclut DELETED) — pour forensique/admin uniquement. */
    List<LettrageMatch> findByCompanyIdAndThirdPartyIdOrderByMatchedAtDesc(UUID companyId, UUID thirdPartyId);

    /** Compte tous les lettrages d'un tiers (inclut DELETED) — pour admin uniquement. */
    long countByCompanyIdAndThirdPartyId(UUID companyId, UUID thirdPartyId);

    // ── Reports Hub : liste paginée filtrée pour GET /lettrage ──

    /**
     * Liste paginée des lettrages d'une entreprise, filtrable par tiers, statut, et plage
     * de dates. Exclut toujours les lettrages DELETED (soft-delete préservé pour forensique).
     *
     * <p>Le filtre {@code status} (si non null) limite aux lettrages FULL ou PARTIAL. Si null,
     * tous les statuts actifs (FULL + PARTIAL) sont retournés.
     *
     * <p>Le filtre de dates s'applique sur {@code matchedAt} (timestamp du lettrage). Si
     * {@code from}/{@code to} sont null, aucun filtre date n'est appliqué.
     *
     * <p>Utilise le pattern {@code :param IS NULL OR ...} standard Hibernate 6 — fonctionne
     * correctement avec les types UUID, enum et Instant sous PostgreSQL.
     *
     * @param companyId identifiant du tenant (obligatoire)
     * @param excludedStatus statut à exclure (typiquement {@link LettrageStatus#DELETED})
     * @param thirdPartyId filtre optionnel par tiers (null = tous les tiers)
     * @param status filtre optionnel par statut (null = tous les statuts actifs)
     * @param from filtre optionnel date de début (inclusive) sur {@code matchedAt}
     * @param to filtre optionnel date de fin (inclusive) sur {@code matchedAt}
     * @param pageable paramètres de pagination (page, size, sort)
     * @return page de {@link LettrageMatch} triée par {@code matchedAt} DESC
     */
    @Query("""
        SELECT lm FROM LettrageMatch lm
        WHERE lm.companyId = :companyId
          AND lm.status <> :excludedStatus
          AND (:thirdPartyId IS NULL OR lm.thirdPartyId = :thirdPartyId)
          AND (:status IS NULL OR lm.status = :status)
          AND (:from IS NULL OR lm.matchedAt >= :from)
          AND (:to IS NULL OR lm.matchedAt <= :to)
        ORDER BY lm.matchedAt DESC
        """)
    Page<LettrageMatch> findFiltered(@Param("companyId") UUID companyId,
                                      @Param("excludedStatus") LettrageStatus excludedStatus,
                                      @Param("thirdPartyId") UUID thirdPartyId,
                                      @Param("status") LettrageStatus status,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      Pageable pageable);
}

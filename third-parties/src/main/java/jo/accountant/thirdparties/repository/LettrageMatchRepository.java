package jo.accountant.thirdparties.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.thirdparties.entity.LettrageMatch;
import jo.accountant.thirdparties.entity.LettrageStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des lettrages.
 *
 * <p><b>Audit v4.7 §3.2 Finding MOYENNE</b> : les méthodes actives filtrent
 * {@code status != DELETED} pour exclure les lettrages soft-deletés des requêtes métier.
 * Les lettrages DELETED restent consultables via {@link JpaRepository#findById} pour forensique.
 */
public interface LettrageMatchRepository extends JpaRepository<LettrageMatch, UUID> {

    /**
     * Tous les lettrages ACTIFS (non DELETED) d'un tiers, triés par date de lettrage décroissante.
     * Audit v4.7 §3.2 — exclut les lettrages soft-deletés.
     */
    List<LettrageMatch> findByCompanyIdAndThirdPartyIdAndStatusNotOrderByMatchedAtDesc(
        UUID companyId, UUID thirdPartyId, LettrageStatus status);

    /**
     * Compte les lettrages ACTIFS (non DELETED) d'un tiers — utilisé pour générer le code de
     * lettrage séquentiel. Audit v4.7 §3.2 — exclut les lettrages soft-deletés.
     */
    long countByCompanyIdAndThirdPartyIdAndStatusNot(UUID companyId, UUID thirdPartyId, LettrageStatus status);

    // --- Méthodes de compatibilité (incluent DELETED — à éviter en métier, utiliser pour admin/forensique) ---

    /** Tous les lettrages d'un tiers (inclut DELETED) — pour forensique/admin uniquement. */
    List<LettrageMatch> findByCompanyIdAndThirdPartyIdOrderByMatchedAtDesc(UUID companyId, UUID thirdPartyId);

    /** Compte tous les lettrages d'un tiers (inclut DELETED) — pour admin uniquement. */
    long countByCompanyIdAndThirdPartyId(UUID companyId, UUID thirdPartyId);
}

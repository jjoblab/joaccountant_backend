package jo.accountant.auth.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.auth.entity.MfaSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository des secrets MFA TOTPFinding MOYENNE — suite).
 *
 * <p>Note : MfaSecret n'est PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} car
 * la MFA est attachée à un utilisateur, pas à une company. Un utilisateur peut avoir des rôles
 * dans plusieurs companies — son secret MFA est global.
 
 *
 * @author jo@Dev


*/
public interface MfaSecretRepository extends JpaRepository<MfaSecret, UUID> {

    /** Secret MFA d'un utilisateur (unique par utilisateur). */
    Optional<MfaSecret> findByUserId(UUID userId);

    /** True si l'utilisateur a activé la MFA. */
    @Query("select count(m) > 0 from MfaSecret m where m.userId = :userId and m.enabledAt is not null")
    boolean isMfaEnabled(UUID userId);

    /** Supprime le secret MFA d'un utilisateur (désactivation). */
    @Modifying
    @Query("delete from MfaSecret m where m.userId = :userId")
    int deleteByUserId(UUID userId);
}

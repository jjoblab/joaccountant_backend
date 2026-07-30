package jo.accountant.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // P1 (fix) : query ciblée au lieu de findAll() pour éviter le DoS mémoire
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}

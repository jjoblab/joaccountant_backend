package jo.accountant.auth.repository;

import java.util.Optional;
import jo.accountant.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, java.util.UUID> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}

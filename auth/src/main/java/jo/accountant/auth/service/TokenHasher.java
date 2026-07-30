package jo.accountant.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hasher SHA-256 pour les tokens opaques (refresh tokens, tokens de réinitialisation de mot de
 * passe).
 *
 * <p>On ne stocke que le hash en base pour qu'une fuite de DB n'accorde pas immédiatement l'accès
 * aux sessions. Le token brut est donné à l'utilisateur une fois et n'est jamais persisté.
 */
@Service
public class TokenHasher {

    public String hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public String generateRawToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }
}

package jo.accountant.auth.config;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

/**
 * Hashing de mots de passe Argon2id (§3.4 recommandation OWASP).
 *
 * <p>Paramètres choisis aux minimums recommandés par OWASP (2024) :
 * <ul>
 *   <li>mémoire = 19 MiB (19456)</li>
 *   <li>itérations = 2</li>
 *   <li>parallélisme = 1</li>
 *   <li>longueur du hash = 32 octets</li>
 * </ul>
 *
 * <p>Format de sortie : {@code $argon2id$v=19$m=19456,t=2,p=1$<salt_b64>$<hash_b64>} — identique
 * au format string libargon2, pour qu'une migration future vers un binding natif libargon2 soit
 * wire-compatible.
 */
@Component
public class Argon2PasswordEncoder {

    private static final int MEMORY_KIB = 19456;  // 19 MiB
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int SALT_LENGTH_BYTES = 16;

    public String encode(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] hash = hash(rawPassword.toCharArray(), salt);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
            + "$" + java.util.Base64.getEncoder().withoutPadding().encodeToString(salt)
            + "$" + java.util.Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    public boolean matches(String rawPassword, String encoded) {
        if (encoded == null || !encoded.startsWith("$argon2id$")) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        // parts[0] est vide (avant le premier $)
        // parts[1] = "argon2id", parts[2] = "v=19", parts[3] = "m=..,t=..,p=..", parts[4] = salt, parts[5] = hash
        if (parts.length != 6) return false;
        byte[] salt = java.util.Base64.getDecoder().decode(parts[4]);
        byte[] expectedHash = java.util.Base64.getDecoder().decode(parts[5]);
        byte[] actualHash = hash(rawPassword.toCharArray(), salt);
        return java.security.MessageDigest.isEqual(expectedHash, actualHash);
    }

    private byte[] hash(char[] password, byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(ITERATIONS)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] output = new byte[HASH_LENGTH_BYTES];
        generator.generateBytes(password, output);
        return output;
    }
}

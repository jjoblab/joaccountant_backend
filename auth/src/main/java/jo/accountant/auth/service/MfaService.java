package jo.accountant.auth.service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jo.accountant.auth.entity.MfaSecret;
import jo.accountant.auth.repository.MfaSecretRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service MFA TOTP (RFC 6238) —Finding MOYENNE (suite).
 *
 * <p>Implémente le setup, la validation et la désactivation de la MFA TOTP. Compatible avec
 * Google Authenticator, Authy, FreeOTP, Microsoft Authenticator.
 *
 * <h2>Flux de setup</h2>
 * <ol>
 * <li>{@link #initiateSetup(UUID)} : génère un secret aléatoire, le chiffre, le persiste
 * ({@code enabledAt=null}). Retourne l'URL otpauth:// pour le QR code.</li>
 * <li>L'utilisateur scanne le QR code avec son app TOTP.</li>
 * <li>{@link #confirmSetup(UUID, int)} : l'utilisateur fournit un code TOTP. Si valide,
 * {@code enabledAt=now()} — la MFA est activée. Génère aussi 10 codes de récupération.</li>
 * </ol>
 *
 * <h2>Flux de login (à intégrer dans AuthService)</h2>
 * <ol>
 * <li>Login + password → retourne {@code MFA_REQUIRED} + challenge.</li>
 * <li>Client fournit le code TOTP → {@link #verifyCode(UUID, int)} valide.</li>
 * <li>Si code TOTP perdu, l'utilisateur peut utiliser un code de récupération :
 * {@link #consumeRecoveryCode(UUID, String)}.</li>
 * </ol>
 *
 * <h2>Sécurité</h2>
 * <ul>
 * <li>Secret TOTP chiffré AES-256-GCM en base (clé dans {@code app.mfa.encryption-key}).</li>
 * <li>Codes de récupération hashés SHA-256 (jamais stockés en clair).</li>
 * <li>Fenêtre TOTP de ±1 période (±30s) pour tolérer le drift d'horloge.</li>
 * <li>SecureRandom pour la génération du secret (32 bytes d'entropie).</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
@Service
public class MfaService {

 private static final Logger LOG = LoggerFactory.getLogger(MfaService.class);
 private static final int SECRET_BYTES = 20; // 160 bits — recommandé RFC 6238
 private static final int RECOVERY_CODE_COUNT = 10;
 private static final int RECOVERY_CODE_LENGTH = 10; // caractères alphanumériques
 private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
 private static final int GCM_TAG_LENGTH = 128; // bits
 private static final int GCM_IV_LENGTH = 12; // bytes

 /**
 * Valeur par défaut de {@code app.mfa.encryption-key} (commitée dans Git pour le dev/test).
 * Utilisée pour détecter au démarrage si la variable d'env n'a pas été surchargée en prod.
 *fail-fast pour empêcher un démarrage prod avec une clé publique.
 */
 private static final String DEFAULT_DEV_ENCRYPTION_KEY = "dev-only-mfa-key-please-override-32-chars";

 /** Longueur minimale de la clé de chiffrement MFA (SHA-256 → 256 bits de sortie, mais
 * on exige 32 caractères minimum en entrée pour assurer l'entropie). */
 private static final int MIN_ENCRYPTION_KEY_LENGTH = 32;

 private final MfaSecretRepository mfaSecretRepository;
 private final byte[] encryptionKey;
 private final String rawEncryptionKey;
 private final Environment environment;

 public MfaService(MfaSecretRepository mfaSecretRepository,
 @Value("${app.mfa.encryption-key:dev-only-mfa-key-please-override-32-chars}") String encryptionKey,
 Environment environment) {
 this.mfaSecretRepository = mfaSecretRepository;
 this.rawEncryptionKey = encryptionKey;
 this.environment = environment;
 // Dériver une clé AES-256 depuis la clé configurée (SHA-256 → 32 bytes)
 try {
 MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
 this.encryptionKey = sha256.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
 } catch (Exception ex) {
 throw new IllegalStateException("Impossible d'initialiser la clé de chiffrement MFA", ex);
 }
 }

 /**
 *Validation au démarrage de la clé de chiffrement MFA.
 *
 * <p>Sans ce garde-fou, l'application démarre silencieusement avec la clé publique
 * {@code dev-only-mfa-key-please-override-32-chars} (commitée dans le code) si
 * {@code APP_MFA_ENCRYPTION_KEY} n'est pas positionnée en production. N'importe quel
 * attaquant connaissant le code source peut alors déchiffrer tous les secrets TOTP
 * stockés en base ({@code mfa_secret.secret_encrypted}), puis générer des codes TOTP
 * valides pour n'importe quel utilisateur → contournement total de la MFA.
 *
 * <p>Règles (inspirées du pattern {@code JwtService.validateSecret()} §6.1) :
 * <ul>
 * <li>Si la clé fait moins de 32 caractères : refus systématique (même en dev/test).</li>
 * <li>Si aucun profil actif n'est {@code dev} ou {@code test} : refus si la clé
 * correspond au pattern public {@code dev-only-mfa-key-please-override-32-chars}.</li>
 * </ul>
 */
 @PostConstruct
 void validateEncryptionKey() {
 if (rawEncryptionKey == null || rawEncryptionKey.length() < MIN_ENCRYPTION_KEY_LENGTH) {
 throw new IllegalStateException(
 "Clé de chiffrement MFA trop courte (" + (rawEncryptionKey == null ? 0 : rawEncryptionKey.length())
 + " caractères). Minimum " + MIN_ENCRYPTION_KEY_LENGTH
 + " caractères requis. Positionner APP_MFA_ENCRYPTION_KEY avec une valeur aléatoire "
 + "d'au moins 256 bits d'entropie.");
 }

 boolean isDevOrTest = environment.matchesProfiles("dev", "test", "h2", "demo");
 if (!isDevOrTest && constantTimeEquals(rawEncryptionKey, DEFAULT_DEV_ENCRYPTION_KEY)) {
 throw new IllegalStateException(
 "Clé de chiffrement MFA inacceptable en production : la valeur par défaut "
 + "'dev-only-mfa-key-please-override-32-chars' (commitée dans Git) est utilisée. "
 + "Positionner APP_MFA_ENCRYPTION_KEY avec une valeur aléatoire d'au moins 256 bits "
 + "d'entropie. Profil actif détecté="
 + (environment.getActiveProfiles().length == 0 ? "default"
 : String.join(",", environment.getActiveProfiles()))
 + ".");
 }

 LOG.info("MfaService initialisé (clé {} caractères, profil={})",
 rawEncryptionKey.length(),
 environment.getActiveProfiles().length == 0 ? "default"
 : String.join(",", environment.getActiveProfiles()));
 }

 /**
 * Comparaison en temps constant — empêche un timing attack sur la comparaison de la clé.
 * {@link String#equals(Object)} court-circuite au premier byte différent, ce qui rend
 * la durée dépendante du préfixe commun (théoriquement exploitable pour reconstruire
 * la clé byte par byte si l'attaquant peut observer le temps de démarrage).
 */
 private static boolean constantTimeEquals(String a, String b) {
 if (a == null || b == null) return false;
 byte[] ba = a.getBytes(StandardCharsets.UTF_8);
 byte[] bb = b.getBytes(StandardCharsets.UTF_8);
 return MessageDigest.isEqual(ba, bb);
 }

 /**
 * Initialise le setup MFA pour un utilisateur.
 *
 * <p>Génère un secret TOTP aléatoire, le chiffre AES-256-GCM, le persiste avec
 * {@code enabledAt=null} (en attente de confirmation). Retourne l'URL otpauth:// pour
 * générer le QR code côté client.
 *
 * @return URL otpauth:// (ex: {@code otpauth://totp/JOAccountant:user@example.com?secret=...&issuer=JOAccountant})
 */
 @Transactional
 public MfaSetupResult initiateSetup(UUID userId, String userEmail) {
 // Générer un secret aléatoire Base32
 byte[] secretBytes = new byte[SECRET_BYTES];
 new SecureRandom().nextBytes(secretBytes);
 String secretBase32 = base32Encode(secretBytes);

 // Chiffrer le secret avant stockage
 String encrypted = encrypt(secretBase32);

 // Persister (ou update si l'utilisateur avait déjà un setup en attente)
 MfaSecret existing = mfaSecretRepository.findByUserId(userId).orElse(null);
 MfaSecret mfa = existing != null ? existing : new MfaSecret();
 if (mfa.getId() == null) mfa.setId(UUID.randomUUID());
 mfa.setUserId(userId);
 mfa.setSecretEncrypted(encrypted);
 mfa.setEnabledAt(null); // en attente de confirmation
 mfa.setCreatedAt(Instant.now());
 mfa.setUpdatedAt(Instant.now());
 mfaSecretRepository.save(mfa);

 // Construire l'URL otpauth:// (RFC 6238 — format standard pour QR code TOTP)
 String otpauthUrl = String.format(
 "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
 "JOAccountant",
 userEmail,
 secretBase32,
 "JOAccountant"
 );

 LOG.info("Setup MFA initié pour user {} — en attente de confirmation", userId);
 return new MfaSetupResult(secretBase32, otpauthUrl);
 }

 /**
 * Confirme le setup MFA en validant un premier code TOTP fourni par l'utilisateur.
 *
 * <p>Si le code est valide, active la MFA ({@code enabledAt=now()}) et génère 10 codes
 * de récupération à usage unique.
 *
 * @return les 10 codes de récupération (à afficher UNE seule fois à l'utilisateur)
 * @throws jo.accountant.core.exception.ValidationException si le code TOTP est invalide
 * ou si aucun setup n'est en attente
 */
 @Transactional
 public List<String> confirmSetup(UUID userId, int totpCode) {
 MfaSecret mfa = mfaSecretRepository.findByUserId(userId)
 .orElseThrow(() -> new jo.accountant.core.exception.ValidationException(
 "MFA_SETUP_NOT_INITIATED",
 "Aucun setup MFA en attente. Appelez d'abord POST /api/v1/auth/mfa/setup."));

 String secret = decrypt(mfa.getSecretEncrypted());
 if (!verifyTotpCode(secret, totpCode, Instant.now())) {
 throw new jo.accountant.core.exception.ValidationException(
 "MFA_CODE_INVALID",
 "Code TOTP invalide. Vérifiez l'heure de votre téléphone et réessayez.");
 }

 // Activer la MFA + générer les codes de récupération
 mfa.setEnabledAt(Instant.now());
 mfa.setUpdatedAt(Instant.now());
 List<String> recoveryCodes = generateRecoveryCodes();
 mfa.setRecoveryCodes(serializeRecoveryCodes(recoveryCodes));
 mfaSecretRepository.save(mfa);

 LOG.info("MFA activée pour user {} — {} codes de récupération générés",
 userId, recoveryCodes.size());
 return recoveryCodes;
 }

 /**
 * Vérifie un code TOTP fourni par l'utilisateur (login ou opération sensible).
 *
 * <p>Fenêtre de tolérance : ±1 période (±30s) pour accommoder le drift d'horloge entre
 * le serveur et le téléphone de l'utilisateur.
 *
 * @return true si le code est valide
 */
 public boolean verifyCode(UUID userId, int totpCode) {
 MfaSecret mfa = mfaSecretRepository.findByUserId(userId).orElse(null);
 if (mfa == null || !mfa.isEnabled()) return false;
 String secret = decrypt(mfa.getSecretEncrypted());
 return verifyTotpCode(secret, totpCode, Instant.now());
 }

 /**
 * Consomme un code de récupération (à usage unique).
 *
 * <p>Si le code est valide, il est marqué comme utilisé et ne peut plus être réutilisé.
 *
 * @return true si le code était valide et a été consommé
 */
 @Transactional
 public boolean consumeRecoveryCode(UUID userId, String code) {
 MfaSecret mfa = mfaSecretRepository.findByUserId(userId).orElse(null);
 if (mfa == null || !mfa.isEnabled() || mfa.getRecoveryCodes() == null) return false;

 String codeHash = sha256Hex(code);
 List<Map<String, Object>> codes = deserializeRecoveryCodes(mfa.getRecoveryCodes());
 for (Map<String, Object> entry : codes) {
 String hash = (String) entry.get("hash");
 if (hash != null && hash.equals(codeHash) && entry.get("usedAt") == null) {
 entry.put("usedAt", Instant.now().toString());
 mfa.setRecoveryCodes(serializeRecoveryCodesFromMaps(codes));
 mfa.setUpdatedAt(Instant.now());
 mfaSecretRepository.save(mfa);
 LOG.info("Code de récupération utilisé pour user {}", userId);
 return true;
 }
 }
 return false;
 }

 /**
 * Désactive la MFA pour un utilisateur (révoque le secret + les codes de récupération).
 */
 @Transactional
 public void disable(UUID userId) {
 mfaSecretRepository.deleteByUserId(userId);
 LOG.info("MFA désactivée pour user {}", userId);
 }

 public boolean isMfaEnabled(UUID userId) {
 return mfaSecretRepository.isMfaEnabled(userId);
 }

 // --- Helpers TOTP (RFC 6238) ---

 private boolean verifyTotpCode(String secretBase32, int code, Instant now) {
 long currentTimeStep = now.getEpochSecond() / 30;
 // Fenêtre de ±1 période (±30s) pour drift d'horloge
 for (long offset = -1; offset <= 1; offset++) {
 long step = currentTimeStep + offset;
 int expected = computeTotp(secretBase32, step);
 if (expected == code) return true;
 }
 return false;
 }

 private int computeTotp(String secretBase32, long timeStep) {
 byte[] secret = base32Decode(secretBase32);
 byte[] timeBytes = new byte[8];
 for (int i = 7; i >= 0; i--) {
 timeBytes[i] = (byte) (timeStep & 0xFF);
 timeStep >>= 8;
 }
 try {
 Mac mac = Mac.getInstance("HmacSHA1");
 mac.init(new SecretKeySpec(secret, "HmacSHA1"));
 byte[] hash = mac.doFinal(timeBytes);
 int offset = hash[hash.length - 1] & 0x0F;
 int truncated = ((hash[offset] & 0x7F) << 24)
 | ((hash[offset + 1] & 0xFF) << 16)
 | ((hash[offset + 2] & 0xFF) << 8)
 | (hash[offset + 3] & 0xFF);
 return truncated % 1_000_000; // 6 digits
 } catch (Exception ex) {
 throw new IllegalStateException("TOTP computation failed", ex);
 }
 }

 // --- Helpers Base32 ---

 private String base32Encode(byte[] bytes) {
 StringBuilder sb = new StringBuilder();
 int buffer = 0;
 int bitsLeft = 0;
 for (byte b : bytes) {
 buffer = (buffer << 8) | (b & 0xFF);
 bitsLeft += 8;
 while (bitsLeft >= 5) {
 int index = (buffer >> (bitsLeft - 5)) & 0x1F;
 sb.append(BASE32_ALPHABET.charAt(index));
 bitsLeft -= 5;
 }
 }
 if (bitsLeft > 0) {
 int index = (buffer << (5 - bitsLeft)) & 0x1F;
 sb.append(BASE32_ALPHABET.charAt(index));
 }
 return sb.toString();
 }

 private byte[] base32Decode(String s) {
 s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
 List<Byte> bytes = new ArrayList<>();
 int buffer = 0;
 int bitsLeft = 0;
 for (char c : s.toCharArray()) {
 int val = BASE32_ALPHABET.indexOf(c);
 if (val < 0) continue;
 buffer = (buffer << 5) | val;
 bitsLeft += 5;
 if (bitsLeft >= 8) {
 bytes.add((byte) ((buffer >> (bitsLeft - 8)) & 0xFF));
 bitsLeft -= 8;
 }
 }
 byte[] result = new byte[bytes.size()];
 for (int i = 0; i < bytes.size(); i++) result[i] = bytes.get(i);
 return result;
 }

 // --- Helpers chiffrement AES-256-GCM ---

 private String encrypt(String plaintext) {
 try {
 byte[] iv = new byte[GCM_IV_LENGTH];
 new SecureRandom().nextBytes(iv);
 Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
 cipher.init(Cipher.ENCRYPT_MODE,
 new SecretKeySpec(encryptionKey, "AES"),
 new GCMParameterSpec(GCM_TAG_LENGTH, iv));
 byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
 byte[] combined = new byte[iv.length + ciphertext.length];
 System.arraycopy(iv, 0, combined, 0, iv.length);
 System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
 return Base64.getEncoder().encodeToString(combined);
 } catch (Exception ex) {
 throw new IllegalStateException("AES encryption failed", ex);
 }
 }

 private String decrypt(String encryptedB64) {
 try {
 byte[] combined = Base64.getDecoder().decode(encryptedB64);
 byte[] iv = new byte[GCM_IV_LENGTH];
 byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
 System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
 System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
 Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
 cipher.init(Cipher.DECRYPT_MODE,
 new SecretKeySpec(encryptionKey, "AES"),
 new GCMParameterSpec(GCM_TAG_LENGTH, iv));
 return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
 } catch (Exception ex) {
 throw new IllegalStateException("AES decryption failed", ex);
 }
 }

 // --- Helpers codes de récupération ---

 private List<String> generateRecoveryCodes() {
 SecureRandom random = new SecureRandom();
 String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
 List<String> codes = new ArrayList<>();
 for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
 StringBuilder sb = new StringBuilder();
 for (int j = 0; j < RECOVERY_CODE_LENGTH; j++) {
 sb.append(chars.charAt(random.nextInt(chars.length())));
 }
 codes.add(sb.toString());
 }
 return codes;
 }

 private String sha256Hex(String input) {
 try {
 MessageDigest digest = MessageDigest.getInstance("SHA-256");
 byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
 return Base64.getEncoder().encodeToString(hash);
 } catch (Exception ex) {
 throw new IllegalStateException("SHA-256 failed", ex);
 }
 }

 @SuppressWarnings("unchecked")
 private List<Map<String, Object>> deserializeRecoveryCodes(String json) {
 if (json == null || json.isBlank()) return new ArrayList<>();
 try {
 return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
 } catch (Exception ex) {
 LOG.warn("Impossible de désérialiser les codes de récupération", ex);
 return new ArrayList<>();
 }
 }

 private String serializeRecoveryCodes(List<String> codes) {
 List<Map<String, Object>> entries = new ArrayList<>();
 for (String code : codes) {
 Map<String, Object> e = new HashMap<>();
 e.put("hash", sha256Hex(code));
 e.put("usedAt", null);
 entries.add(e);
 }
 return serializeRecoveryCodesFromMaps(entries);
 }

 private String serializeRecoveryCodesFromMaps(List<Map<String, Object>> entries) {
 try {
 return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entries);
 } catch (Exception ex) {
 throw new IllegalStateException("JSON serialization failed", ex);
 }
 }

 /** Résultat du setup MFA — URL otpauth + secret en clair (à afficher en fallback du QR code). */
 public record MfaSetupResult(String secret, String otpauthUrl) {}
}

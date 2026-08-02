package jo.accountant.auth.validator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import jo.accountant.core.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Règles de complexité des mots de passe (§13 Phase 1 + audit v4.7 §6.3 + ).
 *
 * <p>Règles :
 * <ul>
 * <li>longueur ≥ 12 (configurable via {@code app.password.min-length})</li>
 * <li>longueur ≤ 128 (audit v4.7 §6.3 — max length anti-DoS Argon2)</li>
 * <li>au moins une lettre majuscule</li>
 * <li>au moins une lettre minuscule</li>
 * <li>au moins un chiffre</li>
 * <li>au moins un caractère spécial</li>
 * <li>non présent dans la blacklist locale (50+ mots de passe courants)</li>
 * <li><b></b> : si HIBP activé ({@code app.password.hibp.enabled=true}), vérifie
 * que le hash SHA-1 du mot de passe n'apparaît pas dans la base HIBP (600M+ de
 * leaks) en utilisant le protocole k-anonymity de Troy Hunt
 * (https://haveibeenpwned.com/API/v3#PwnedPasswords).</li>
 * </ul>
 *
 * <p><b>Audit v4.7 §6.3 Finding MOYENNE — FIX</b> :
 * <ul>
 * <li><b>Max length 128</b> : sans max, un attaquant peut soumettre un mot de passe de
 * plusieurs MB → Argon2 consomme 19 MiB de mémoire par hash → DoS (OOM). Désormais,
 * max 128 caractères (suffisant pour tous les gestionnaires de mots de passe).</li>
 * <li><b>Blacklist locale</b> : 50+ mots de passe courants. Couverture limitée.</li>
 * </ul>
 *
 * <p><b> — Intégration HIBP (Have I Been Pwned)</b> :
 * <ul>
 * <li>Active la vérification contre 600M+ de hashes de mots de passe leakés (vs 50
 * dans la blacklist locale).</li>
 * <li>Protocole k-anonymity : seuls les 5 premiers caractères du SHA-1 sont envoyés
 * à l'API HIBP. Le serveur HIBP renvoie tous les suffixes correspondants (environ
 * 500-800 entrées par préfixe). Le client compare localement le suffixe complet —
 * HIBP ne sait jamais quel mot de passe exact est vérifié.</li>
 * <li>Cache Caffeine (TTL 1h, max 10000 préfixes) : un même préfixe n'est pas
 * re-téléchargé pendant 1h. Réduit la latence de validation (50ms réseau → cache
 * hit <1ms) et la charge sur l'API HIBP (qui est rate-limitée).</li>
 * <li>Timeout HTTP de 2s (configurable) : ne bloque pas l'enregistrement utilisateur
 * si HIBP est lent.</li>
 * <li><b>Résilience</b> : si HIBP est indisponible (réseau, DNS, 5xx), la vérification
 * est skippée (log WARNING) — la blacklist locale reste active. L'utilisateur peut
 * toujours s'enregistrer (fail-open côté HIBP, fail-closed côté blacklist).</li>
 * <li>Désactivé par défaut ({@code app.password.hibp.enabled=false}) pour backward
 * compat et pour permettre les env sans accès Internet (on-premise air-gapped).</li>
 * </ul>
 *
 * <p>Échec → 422 avec un code stable par règle échue, pour que le frontend puisse afficher une
 * indication précise plutôt qu'un générique « mot de passe trop faible ».
 */
@Component
public class PasswordValidator {

 private static final Logger LOG = LoggerFactory.getLogger(PasswordValidator.class);

 /** Max length anti-DoS Argon2 — 128 caractères suffisent pour tous les password managers. */
 private static final int MAX_LENGTH = 128;

 /** User-Agent envoyé à l'API HIBP (requis par leur politique d'utilisation). */
 private static final String HIBP_USER_AGENT = "joaccountant-password-validator";

 /** Longueur du préfixe k-anonymity SHA-1 (5 caractères hex = 20 bits = ~1M préfixes possibles). */
 private static final int HIBP_PREFIX_LENGTH = 5;

 /** Blacklist locale des mots de passe les plus courants (top 50 OWASP). */
 private static final java.util.Set<String> BLACKLIST = java.util.Set.of(
 "password", "password1", "password12", "password123",
 "123456", "1234567", "12345678", "123456789", "1234567890",
 "qwerty", "qwerty12", "qwerty123", "azerty", "azerty12",
 "admin", "admin123", "administrator", "root", "root123",
 "letmein", "welcome", "welcome1", "monkey", "monkey123",
 "dragon", "dragon123", "master", "master123", "shadow",
 "sunshine", "princess", "football", "baseball", "superman",
 "michael", "jennifer", "jordan", "hunter", "ranger",
 "P@ssw0rd", "P@ssword1", "P@ssword12", "P@ssword123",
 "Passw0rd", "Passw0rd1", "Passw0rd12", "Passw0rd123",
 "Welcome1", "Welcome12", "Welcome123", "Admin123", "Admin@123"
 );

 private final int minLength;
 private final boolean hibpEnabled;
 private final String hibpApiUrl;
 private final int hibpTimeoutMs;

 /**
 * HttpClient JDK natif (depuis Java 11). Pas de dépendance externe (pas de Apache HttpClient,
 * pas de OkHttp). Initialisé lazy pour permettre l'injection d'un mock côté tests via
 * {@link #setHttpClientForTests(HttpClient)}.
 */
 private volatile HttpClient httpClient;

 /**
 * Cache Caffeine des réponses HIBP par préfixe (TTL 1h, max 10000 entrées).
 * <p>Key : préfixe 5 chars (ex: "5BAA6").
 * <p>Value : corps de la réponse HIBP (ex: "1B7E3C4B5D6E7F8A9:42\n2C3D4E5F6A7B8C9D0:1\n...").
 * <p>Une entrée absente du cache déclenche un appel HTTP ; une entrée présente évite l'appel.
 * <p>Le TTL de 1h est un compromis : assez court pour capter les nouvelles fuites HIBP
 * (mises à jour mensuelles), assez long pour amortir l'appel réseau sur un flux
 * d'enregistrement (typiquement plusieurs users/min se font compromised par le même préfixe).
 */
 private final Cache<String, String> hibpCache = Caffeine.newBuilder()
 .expireAfterWrite(1, TimeUnit.HOURS)
 .maximumSize(10_000)
 .recordStats()
 .build();

 public PasswordValidator(
 @Value("${app.password.min-length:12}") int minLength,
 @Value("${app.password.hibp.enabled:false}") boolean hibpEnabled,
 @Value("${app.password.hibp.api-url:https://api.pwnedpasswords.com/range/}") String hibpApiUrl,
 @Value("${app.password.hibp.timeout-ms:2000}") int hibpTimeoutMs) {
 this.minLength = minLength;
 this.hibpEnabled = hibpEnabled;
 this.hibpApiUrl = hibpApiUrl;
 this.hibpTimeoutMs = hibpTimeoutMs;
 if (hibpEnabled) {
 LOG.info("HIBP password breach check enabled (api-url={}, timeout={}ms, cache TTL=1h)",
 hibpApiUrl, hibpTimeoutMs);
 } else {
 LOG.debug("HIBP password breach check disabled (app.password.hibp.enabled=false) — "
 + "only local blacklist (50 entries) is applied");
 }
 }

 public void validate(String password) {
 if (password == null || password.length() < minLength) {
 throw new ValidationException("PASSWORD_TOO_SHORT",
 "Password must be at least " + minLength + " characters long");
 }
 // Audit v4.7 §6.3 — max length anti-DoS Argon2
 if (password.length() > MAX_LENGTH) {
 throw new ValidationException("PASSWORD_TOO_LONG",
 "Password must be at most " + MAX_LENGTH + " characters long (anti-DoS Argon2)");
 }
 if (password.chars().noneMatch(Character::isUpperCase)) {
 throw new ValidationException("PASSWORD_NO_UPPERCASE",
 "Password must contain at least one uppercase letter");
 }
 if (password.chars().noneMatch(Character::isLowerCase)) {
 throw new ValidationException("PASSWORD_NO_LOWERCASE",
 "Password must contain at least one lowercase letter");
 }
 if (password.chars().noneMatch(Character::isDigit)) {
 throw new ValidationException("PASSWORD_NO_DIGIT",
 "Password must contain at least one digit");
 }
 if (password.chars().noneMatch(c -> !Character.isLetterOrDigit(c))) {
 throw new ValidationException("PASSWORD_NO_SPECIAL",
 "Password must contain at least one special character");
 }
 // Audit v4.7 §6.3 — blacklist locale (50 mots de passe courants)
 if (BLACKLIST.contains(password.toLowerCase())) {
 throw new ValidationException("PASSWORD_BLACKLISTED",
 "Password is in the blacklist of commonly used passwords. Choose a more unique password.");
 }
 // — HIBP check (activé uniquement si app.password.hibp.enabled=true)
 if (hibpEnabled) {
 checkHibpBreaches(password);
 }
 }

 /**
 * Vérifie si le mot de passe a été compromis dans une fuite de données référencée par
 * Have I Been Pwned (HIBP).
 *
 * <p>Protocole k-anonymity (Troy Hunt, 2018) :
 * <ol>
 * <li>Calcule SHA-1(password) en hex majuscules (40 caractères).</li>
 * <li>Préfixe = 5 premiers caractères, suffixe = 35 caractères restants.</li>
 * <li>Appelle {@code GET https://api.pwnedpasswords.com/range/{prefix}} — HIBP renvoie
 * tous les suffixes commençant par ce préfixe (500-800 lignes au format
 * {@code SUFFIX:COUNT}).</li>
 * <li>Compare localement le suffixe du password contre la liste reçue.</li>
 * </ol>
 *
 * <p>HIBP ne sait jamais quel password exact est vérifié — il ne voit que le préfixe 5 chars
 * (qui correspond à ~1M de hashes possibles). Confidentialité préservée.
 *
 * <p><b>Résilience</b> : en cas d'erreur réseau (HIBP indisponible, timeout, 5xx), la méthode
 * loggue WARNING et retourne sans lever d'exception. La blacklist locale (50 mots) reste
 * active en amont. Ce comportement fail-open côté HIBP est un compromis délibéré :
 * préférer permettre l'enregistrement (avec blacklist locale) plutôt que bloquer tous les
 * nouveaux utilisateurs si HIBP tombe. Pour un mode fail-closed, positionner
 * {@code app.password.hibp.enabled=false} (ce qui désactive aussi la résilience — seul le
 * mode fail-open a du sens).
 *
 * @param password le mot de passe en clair (déjà validé par les règles précédentes)
 * @throws ValidationException avec le code {@code PASSWORD_COMPROMISED} si le suffixe SHA-1
 * du password est trouvé dans la réponse HIBP avec un count > 0
 */
 void checkHibpBreaches(String password) {
 String sha1 = sha1HexUpper(password);
 String prefix = sha1.substring(0, HIBP_PREFIX_LENGTH);
 String suffix = sha1.substring(HIBP_PREFIX_LENGTH);

 // cache hit évite l'appel HTTP. Caffeine.get(key, mapping) exécute le mapping
 // uniquement si la key est absente du cache. Thread-safe (atomic compute).
 //
 // Note : la signature de fetchHibpRange déclare `throws IOException,
 // InterruptedException` — Caffeine.get attend une `Function<K,V>` dont `apply` ne lance
 // pas de checked exceptions. On wrappe donc l'appel dans une lambda qui convertit les
 // checked en unchecked (RuntimeException). Le catch (Exception) ci-dessous attrape
 // toujours l'originale via getCause() — stratégie fail-open préservée.
 String responseBody;
 try {
 responseBody = hibpCache.get(prefix, key -> {
 try {
 return fetchHibpRange(key);
 } catch (IOException | InterruptedException ex) {
 throw new RuntimeException(ex);
 }
 });
 } catch (Exception e) {
 // Fail-open : HIBP indisponible → on ne bloque pas l'enregistrement.
 // La blacklist locale (50 mots) reste active en amont.
 LOG.warn("HIBP API call failed for prefix {} — skipping breach check (resilience). "
 + "Local blacklist (50 entries) still active. Cause: {}", prefix, e.toString());
 return;
 }
 if (responseBody == null || responseBody.isEmpty()) {
 LOG.warn("HIBP API returned empty body for prefix {} — skipping breach check", prefix);
 return;
 }

 // Parse la réponse : une ligne par suffixe, format "SUFFIX:COUNT"
 // Ex : "00A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6E7F8A9:42"
 for (String line : responseBody.split("\n")) {
 int colon = line.indexOf(':');
 if (colon <= 0) {
 continue;
 }
 String lineSuffix = line.substring(0, colon).trim().toUpperCase();
 String countStr = line.substring(colon + 1).trim();
 int count;
 try {
 count = Integer.parseInt(countStr);
 } catch (NumberFormatException nfe) {
 continue; // ligne mal formée — on ignore
 }
 if (lineSuffix.equals(suffix) && count > 0) {
 LOG.warn("Password rejected : found in HIBP database with {} occurrences (prefix={})",
 count, prefix);
 throw new ValidationException("PASSWORD_COMPROMISED",
 "Password found in HIBP database, please choose another");
 }
 }
 // Password non trouvé dans la base HIBP → OK
 }

 /**
 * Appelle l'API HIBP pour récupérer tous les suffixes SHA-1 correspondant à un préfixe.
 * Méthode package-private pour permettre les tests unitaires (mock HttpClient).
 *
 * @param prefix les 5 premiers caractères hex majuscules du SHA-1
 * @return le corps de la réponse HIBP (lignes "SUFFIX:COUNT")
 * @throws IOException en cas d'erreur réseau ou de statut HTTP non-200
 */
 private String fetchHibpRange(String prefix) throws IOException, InterruptedException {
 HttpRequest req = HttpRequest.newBuilder()
 .uri(URI.create(hibpApiUrl + prefix))
 .timeout(Duration.ofMillis(hibpTimeoutMs))
 .header("User-Agent", HIBP_USER_AGENT)
 .header("Accept", "text/plain")
 .GET()
 .build();
 HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
 int status = resp.statusCode();
 if (status != 200) {
 throw new IOException("HIBP API returned HTTP " + status + " for prefix " + prefix);
 }
 return resp.body();
 }

 /**
 * Initialise lazy le HttpClient (JDK 11+ natif, pas de dépendance externe).
 * Thread-safe via double-checked locking.
 */
 private HttpClient httpClient() {
 HttpClient local = this.httpClient;
 if (local == null) {
 synchronized (this) {
 local = this.httpClient;
 if (local == null) {
 local = HttpClient.newBuilder()
 .connectTimeout(Duration.ofMillis(hibpTimeoutMs))
 .build();
 this.httpClient = local;
 }
 }
 }
 return local;
 }

 /**
 * Hook pour tests unitaires : permet d'injecter un HttpClient mocké (Mockito) afin de
 * simuler les réponses de l'API HIBP sans effectuer de vrai appel réseau.
 *
 * <p>Usage dans un test :
 * <pre>
 * HttpClient mockClient = mock(HttpClient.class);
 * HttpResponse&lt;String&gt; mockResp = mock(HttpResponse.class);
 * when(mockResp.statusCode()).thenReturn(200);
 * when(mockResp.body()).thenReturn("ABCDEF:1");
 * when(mockClient.send(any(), any())).thenReturn(mockResp);
 * validator.setHttpClientForTests(mockClient);
 * </pre>
 */
 void setHttpClientForTests(HttpClient httpClient) {
 this.httpClient = httpClient;
 }

 /**
 * Calcule le SHA-1 d'une chaîne en hexadécimal majuscules (format attendu par HIBP).
 * Package-private pour les tests.
 */
 static String sha1HexUpper(String input) {
 try {
 MessageDigest md = MessageDigest.getInstance("SHA-1");
 byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
 StringBuilder sb = new StringBuilder(hash.length * 2);
 for (byte b : hash) {
 sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
 sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
 }
 return sb.toString();
 } catch (NoSuchAlgorithmException e) {
 // SHA-1 est garanti présent dans toute JVM conforme (JCA spec)
 throw new IllegalStateException("SHA-1 algorithm not available in JCA", e);
 }
 }
}

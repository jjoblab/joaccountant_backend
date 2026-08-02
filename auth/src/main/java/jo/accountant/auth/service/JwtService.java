package jo.accountant.auth.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Émetteur de JWT access-token (§3.4 : access token 15 min).
 *
 * <p>Algorithmes supportésFinding MOYENNE) :
 * <ul>
 * <li><b>HS256</b> (défaut, rétro-compat) — secret partagé. Utilisé pour mono-instance / dev.</li>
 * <li><b>RS256</b> — clé privée RSA pour signer, clé publique pour vérifier. Utilisé pour
 * multi-instances / microservices : la clé publique peut être distribuée sans risque,
 * ce qui réduit le blast radius en cas de fuite (un attaquant qui vole la clé publique
 * ne peut pas forger de JWT).</li>
 * </ul>
 *
 * <p>Configuration via {@code app.jwt.algorithm} :
 * <pre>
 * # HS256 (défaut)
 * app.jwt.algorithm=HS256
 * app.jwt.secret=${APP_JWT_SECRET:dev-only-secret-...}
 *
 * # RS256 (recommandé prod)
 * app.jwt.algorithm=RS256
 * app.jwt.rsa.private-key-path=/etc/joaccountant/keys/jwt-private.pem
 * app.jwt.rsa.key-id=joaccountant-prod-2026
 * </pre>
 *
 * <p>Pour RS256, la clé publique correspondante doit être exposée via JWKS
 * (à configurer dans SecurityConfig pour le JwtDecoder — Non implémenté : endpoint
 * {@code /.well-known/jwks.json} automatique).
 *
 * <p>Claims : {@code sub} (userId), {@code email}, {@code companies} (liste de {companyId, role}
 * acceptés par l'utilisateur), {@code iat}, {@code exp}, {@code iss}, {@code aud}.
 *
 * <p><b>Sécurité</b> : au démarrage, fail-fast si le secret JWT correspond à un
 * pattern dev/test alors que l'environnement n'est ni dev ni test. Empêche un déploiement prod
 * accidentel avec un secret public commité dans Git.
 *
 * <p><b></b> : ajout de {@link #parseAndVerifyClaims(String)} qui vérifie
 * explicitement la signature JWT. L'ancienne méthode {@link #parseClaims(String)} est conservée
 * (dépréciée) pour compat, mais ne doit plus être utilisée pour toute décision de sécurité.
 
 *
 * @author jo@Dev


*/
@Service
public class JwtService {

 private static final Logger LOG = LoggerFactory.getLogger(JwtService.class);

 /** Patterns de secrets faibles connus (committés dans Git). */
 private static final List<String> WEAK_SECRET_PATTERNS = List.of(
 "dev-only-secret-please-override-in-production",
 "test-secret-please-do-not-use-in-production"
 );

 /** Issuer par défaut —: claim iss obligatoire pour anti-rejeu cross-env. */
 private static final String DEFAULT_ISSUER = "joaccountant";

 /** Audience par défaut —: claim aud obligatoire pour anti-rejeu cross-env. */
 private static final String DEFAULT_AUDIENCE = "joaccountant-api";

 private final JWSSigner signer;
 private final JWSVerifier verifier;
 private final JWSAlgorithm algorithm;
 private final long accessTokenTtlSeconds;
 private final String secret;
 private final Environment environment;
 private final String issuer;
 private final String audience;

 public JwtService(@Value("${app.jwt.secret:}") String secret,
 @Value("${app.jwt.algorithm:HS256}") String algorithmStr,
 @Value("${app.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds,
 @Value("${app.jwt.issuer:joaccountant}") String issuer,
 @Value("${app.jwt.audience:joaccountant-api}") String audience,
 @Value("${app.jwt.rsa.private-key-path:}") String rsaPrivateKeyPath,
 @Value("${app.jwt.rsa.key-id:joaccountant-default}") String rsaKeyId,
 Environment environment) {
 this.secret = secret;
 this.environment = environment;
 this.accessTokenTtlSeconds = accessTokenTtlSeconds;
 this.issuer = issuer;
 this.audience = audience;

 //— sélection de l'algorithme selon la config
 String algoUpper = algorithmStr.trim().toUpperCase();
 switch (algoUpper) {
 case "HS256":
 this.algorithm = JWSAlgorithm.HS256;
 this.signer = createHs256Signer(secret);
 this.verifier = createHs256Verifier(secret);
 LOG.info("JwtService : algorithme HS256 (secret partagé)");
 break;
 case "RS256":
 this.algorithm = JWSAlgorithm.RS256;
 RSAPrivateKey privateKey = loadRs256PrivateKey(rsaPrivateKeyPath, rsaKeyId);
 this.signer = createRs256Signer(privateKey, rsaKeyId);
 this.verifier = createRs256Verifier(privateKey);
 LOG.info("JwtService : algorithme RS256 (clé privée RSA, keyId={})", rsaKeyId);
 break;
 default:
 throw new IllegalStateException(
 "Algorithme JWT non supporté : " + algorithmStr + ". Valeurs acceptées : HS256, RS256.");
 }
 }

 /**
 * Validation au démarrage : refuse un secret JWT faible en profil non-dev/test.
 *
 * <p>sans ce garde-fou, l'application démarre silencieusement
 * avec un secret public si {@code APP_JWT_SECRET} n'est pas positionné en production. N'importe
 * quel attaquant connaissant le code source peut alors forger des JWT valides.
 */
 @PostConstruct
 void validateSecret() {
 boolean isDevOrTest = environment.matchesProfiles("dev", "test");
 if (algorithm == JWSAlgorithm.HS256) {
 if (!isDevOrTest) {
 for (String weak : WEAK_SECRET_PATTERNS) {
 if (secret.startsWith(weak)) {
 throw new IllegalStateException(
 "JWT secret inacceptable en production : correspond au pattern faible '" + weak
 + "...'. Positionner APP_JWT_SECRET avec une valeur d'au moins 256 bits "
 + "d'entropie (32+ caractères aléatoires). Détecté en profil="
 + String.join(",", environment.getActiveProfiles()) + ".");
 }
 }
 }
 if (secret.length() < 32) {
 throw new IllegalStateException(
 "JWT secret trop court (" + secret.length() + " caractères). Minimum 32 caractères "
 + "(256 bits) requis pour HS256.");
 }
 }
 // Pour RS256, la clé est déjà validée dans loadRs256PrivateKey (fichier doit exister + lisible)
 LOG.info("JwtService initialisé (algo={}, ttl={}s, iss={}, aud={}, profil={})",
 algorithm, accessTokenTtlSeconds, issuer, audience,
 environment.getActiveProfiles().length == 0 ? "default" : String.join(",", environment.getActiveProfiles()));
 }

 private static JWSSigner createHs256Signer(String secret) {
 try {
 return new MACSigner(secret);
 } catch (JOSEException ex) {
 throw new IllegalStateException("Invalid JWT secret (must be ≥ 256 bits)", ex);
 }
 }

 /**
 * Crée le verifier HS256 (HMAC-SHA256) avec le même secret partagé que le signer.
 *la vérification de signature est OBLIGATOIRE pour le
 * {@code mfaChallengeToken} présenté à {@code /api/v1/auth/login/mfa}.
 */
 private static JWSVerifier createHs256Verifier(String secret) {
 try {
 return new MACVerifier(secret);
 } catch (JOSEException ex) {
 throw new IllegalStateException("Invalid JWT secret (must be ≥ 256 bits)", ex);
 }
 }

 /**
 * Charge une clé privée RSA depuis un fichier PEM PKCS#8.
 *
 * <p>Format attendu : PKCS#8 PEM (header {@code -----BEGIN PRIVATE KEY-----}). Générable avec :
 * <pre>
 * # Générer une clé privée RSA 2048 bits
 * openssl genrsa -out jwt-private.pem 2048
 * # Convertir en PKCS#8
 * openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -out jwt-private-pkcs8.pem
 * # Extraire la clé publique pour JWKS
 * openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
 * </pre>
 *
 * @return la clé privée RSA (typiquement sous forme CRT — Chinese Remainder Theorem)
 * @throws IllegalStateException si le fichier est illisible, introuvable ou au format inattendu
 */
 private static RSAPrivateKey loadRs256PrivateKey(String privateKeyPath, String keyId) {
 if (privateKeyPath == null || privateKeyPath.isBlank()) {
 throw new IllegalStateException(
 "RS256 sélectionné mais app.jwt.rsa.private-key-path n'est pas configuré. " +
 "Générer une clé avec openssl genrsa -out jwt-private.pem 2048 && " +
 "openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -out jwt-private-pkcs8.pem, " +
 "puis positionner app.jwt.rsa.private-key-path=/path/to/jwt-private-pkcs8.pem");
 }
 try {
 String pemContent = Files.readString(Path.of(privateKeyPath)).trim();
 // Strip PEM headers (PKCS#8 ou PKCS#1) — garde uniquement le body Base64
 String pemBody = pemContent
 .replaceAll("-{5}BEGIN [A-Z ]+-{5}", "")
 .replaceAll("-{5}END [A-Z ]+-{5}", "")
 .replaceAll("\\s+", "");

 byte[] keyBytes = java.util.Base64.getDecoder().decode(pemBody);
 KeyFactory keyFactory = KeyFactory.getInstance("RSA");
 PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
 RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
 LOG.info("JwtService : clé privée RSA chargée depuis {} (keyId={})", privateKeyPath, keyId);
 return privateKey;
 } catch (IOException ex) {
 throw new IllegalStateException("Impossible de lire le fichier de clé privée RSA : " + privateKeyPath, ex);
 } catch (Exception ex) {
 throw new IllegalStateException("Clé privée RSA invalide : " + privateKeyPath + ". " +
 "Format attendu : PKCS#8 PEM (header BEGIN PRIVATE KEY).", ex);
 }
 }

 /** Crée le signer RS256 depuis une clé privée RSA déjà chargée. */
 private static JWSSigner createRs256Signer(RSAPrivateKey privateKey, String keyId) {
 // Le keyId est déjà loggé par loadRs256PrivateKey — pas besoin de re-logguer ici.
 return new RSASSASigner(privateKey);
 }

 /**
 * Crée le verifier RS256 en dérivant la clé publique depuis la clé privée.
 *
 * <p>Les clés privées RSA générées par OpenSSL sont en forme CRT (Chinese Remainder Theorem),
 * ce qui expose l'exposant public via {@link RSAPrivateCrtKey#getPublicExponent()}.
 * On peut donc reconstruire la clé publique sans avoir besoin d'un fichier JWKS séparé —
 * le signer et le verifier utilisent la même paire de clés.
 *
 * <p>Si la clé privée n'est PAS en forme CRT (cas rare — clé PKCS#8 non-CRT), une
 * {@link IllegalStateException} est levée au démarrage avec un message indiquant comment
 * régénérer la clé au bon format.
 */
 private static JWSVerifier createRs256Verifier(RSAPrivateKey privateKey) {
 if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
 throw new IllegalStateException(
 "Clé privée RSA non-CRT détectée — impossible de dériver la clé publique pour le verifier. " +
 "Régénérer la clé avec OpenSSL (qui produit des clés CRT par défaut) : " +
 "openssl genrsa -out jwt-private.pem 2048 && " +
 "openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -out jwt-private-pkcs8.pem");
 }
 try {
 RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
 RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(pubSpec);
 return new RSASSAVerifier(publicKey);
 } catch (Exception ex) {
 throw new IllegalStateException("Impossible de créer le verifier RS256 depuis la clé privée", ex);
 }
 }

 public String issueAccessToken(UUID userId, String email, List<Map<String, Object>> companies) {
 Instant now = Instant.now();
 //— ajout des claims iss + aud pour anti-rejeu cross-environnement
 JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
 .subject(userId.toString())
 .issuer(issuer)
 .audience(audience)
 .claim("email", email)
 .claim("companies", companies)
 .issueTime(Date.from(now))
 .expirationTime(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
 .jwtID(UUID.randomUUID().toString());

 SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims.build());
 try {
 jwt.sign(signer);
 } catch (JOSEException ex) {
 throw new IllegalStateException("Failed to sign JWT", ex);
 }
 return jwt.serialize();
 }

 public long getAccessTokenTtlSeconds() {
 return accessTokenTtlSeconds;
 }

 /**
 * Parse les claims d'un JWT <b>sans vérifier la signature</b> — utilisé uniquement pour
 * inspecter le contenu d'un JWT déjà vérifié ailleurs (ex: décodage côté logger).
 *
 * <p><b>ATTENTION</b> — ne JAMAIS utiliser cette méthode pour prendre une décision de sécurité
 * (authentification, autorisation, extraction d'userId). Un attaquant peut forger un JWT
 * non signé (alg: none) ou avec une signature invalide qui passera cette méthode. Pour toute
 * décision de sécurité, utiliser {@link #parseAndVerifyClaims(String)}.
 *
 * <p>Historiquement utilisée pour le {@code mfaChallengeToken}— *corrige ce bypass en remplaçant l'appel par {@link #parseAndVerifyClaims(String)}.
 * La méthode est conservée pour compat (pas d'autre appelant actuellement), mais marquée
 * déconseillée pour tout usage de sécurité.
 *
 * @deprecated utiliser {@link #parseAndVerifyClaims(String)} qui valide la signature.
 */
 @Deprecated(since = "", forRemoval = false)
 public java.util.Map<String, Object> parseClaims(String jwt) {
 try {
 com.nimbusds.jwt.SignedJWT signedJWT = com.nimbusds.jwt.SignedJWT.parse(jwt);
 com.nimbusds.jwt.JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
 java.util.Map<String, Object> result = new java.util.HashMap<>();
 result.put("sub", claims.getSubject());
 result.put("email", claims.getStringClaim("email"));
 result.put("companies", claims.getJSONObjectClaim("companies"));
 return result;
 } catch (Exception ex) {
 throw new IllegalStateException("Failed to parse JWT claims", ex);
 }
 }

 /**
 *Parse ET vérifie la signature d'un JWT.
 *
 * <p>Utilisé par {@code AuthController.loginMfa()} pour valider le {@code mfaChallengeToken}
 * avant de délivrer les tokens d'accès. Sans cette vérification, un attaquant pouvait forger
 * un JWT non signé (alg: none) ou avec une signature HMAC invalide — le parser Nimbus accepte
 * silencieusement les JWT non signés tant qu'on n'appelle pas {@code verify()}.
 *
 * <p>Vérifications effectuées :
 * <ol>
 * <li><b>Parse syntaxique</b> — {@link SignedJWT#parse(String)} lève une exception si le
 * JWT est mal formé (3 segments attendus, Base64URL valide, JSON valide).</li>
 * <li><b>Vérification de signature</b> — {@link SignedJWT#verify(JWSVerifier)} valide la
 * signature avec la clé configurée (HMAC pour HS256, RSA publique pour RS256).
 * Un JWT non signé (alg: none) retourne {@code false} → {@link InvalidJwtException}.</li>
 * <li><b>Vérification d'expiration</b> — {@code exp} comparé à {@code Instant.now()}.
 * Le TTL du challenge token MFA est configuré via {@code app.jwt.access-token-ttl-seconds}
 * (défaut 900s = 15 min). Un token expiré → {@link InvalidJwtException}.</li>
 * <li><b>Vérification issuer + audience</b> — anti-rejeu cross-environnement
 * (prod ↔ staging). Un token émis par un autre environnement est rejeté.</li>
 * </ol>
 *
 * @return les claims validés (sub, email, companies) — structure identique à {@link #parseClaims}
 * pour préserver la compatibilité du code appelant.
 * @throws InvalidJwtException si la signature est invalide, le JWT expiré, ou mal formé.
 * Le code de l'exception est {@code MFA_CHALLENGE_TOKEN_INVALID} (HTTP 403).
 */
 public java.util.Map<String, Object> parseAndVerifyClaims(String jwt) {
 if (jwt == null || jwt.isBlank()) {
 throw new InvalidJwtException("mfaChallengeToken manquant ou vide.");
 }
 try {
 SignedJWT signedJWT = SignedJWT.parse(jwt);

 // 1. Vérification de signature — fix central.
 // signedJWT.verify() retourne false pour un JWT non signé (alg: none) ou une
 // signature HMAC/RSA invalide. On lève InvalidJwtException dans les deux cas.
 if (!signedJWT.verify(verifier)) {
 throw new InvalidJwtException(
 "Signature du mfaChallengeToken invalide — token rejeté (possible forgery).",
 null);
 }

 JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

 // 2. Vérification d'expiration — les claims exp sont positionnés par issueAccessToken().
 // On laisse une tolérance d'horloge nulle (le TTL est de 15 min, pas besoin de
 // tolérance pour drift). Si l'horloge serveur est décalée, c'est un problème
 // d'infra — pas un problème applicatif.
 Date expirationTime = claims.getExpirationTime();
 if (expirationTime != null && expirationTime.before(Date.from(Instant.now()))) {
 throw new InvalidJwtException(
 "mfaChallengeToken expiré — le client doit redemander un login (step 1) pour " +
 "obtenir un nouveau challenge.",
 null);
 }

 // 3. Vérification de l'issuer — anti-rejeu cross-environnement (prod ↔ staging).
 // Le claim iss est positionné par issueAccessToken() depuis la config app.jwt.issuer.
 String tokenIssuer = claims.getIssuer();
 if (tokenIssuer != null && !tokenIssuer.equals(issuer)) {
 throw new InvalidJwtException(
 "Issuer du mfaChallengeToken inattendu : " + tokenIssuer + " (attendu : " + issuer + "). " +
 "Possible rejeu cross-environnement — token rejeté.",
 null);
 }

 // 4. Vérification de l'audience — même logique que l'issuer.
 // Le claim aud peut être une String ou une List<String> — Nimbus expose les deux.
 java.util.List<String> tokenAudiences = claims.getAudience();
 if (tokenAudiences != null && !tokenAudiences.isEmpty()
 && !tokenAudiences.contains(audience)) {
 throw new InvalidJwtException(
 "Audience du mfaChallengeToken inattendue : " + tokenAudiences
 + " (attendu : " + audience + "). Token rejeté.",
 null);
 }

 // Claims validés — retourner sous la même forme que parseClaims() pour préserver
 // la compatibilité du code appelant (AuthController.loginMfa).
 java.util.Map<String, Object> result = new java.util.HashMap<>();
 result.put("sub", claims.getSubject());
 result.put("email", claims.getStringClaim("email"));
 result.put("companies", claims.getJSONObjectClaim("companies"));
 return result;
 } catch (InvalidJwtException ex) {
 // Re-lancer telle quelle — ne pas wrapper pour préserver le code métier.
 throw ex;
 } catch (com.nimbusds.jose.JOSEException | java.text.ParseException ex) {
 // ParseException : JWT mal formé (segments non Base64, JSON invalide, claims illisibles).
 // JOSEException : erreur interne à la vérification crypto (ne devrait pas arriver
 // car le verifier est déjà validé au démarrage).
 throw new InvalidJwtException(
 "mfaChallengeToken mal formé ou illisible : " + ex.getMessage(), ex);
 } catch (Exception ex) {
 // Toute autre erreur (NullPointerException sur un claim manquant, etc.) — par défaut,
 // on refuse le token. Fail-closed.
 throw new InvalidJwtException(
 "Erreur lors de la validation du mfaChallengeToken : " + ex.getMessage(), ex);
 }
 }

 /** Algorithme actif — utilisé par SecurityConfig pour configurer le JwtDecoder. */
 public JWSAlgorithm getAlgorithm() {
 return algorithm;
 }
}

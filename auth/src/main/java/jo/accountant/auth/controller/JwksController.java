package jo.accountant.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint JWKS ({@code /.well-known/jwks.json}) pour exposer la clé publique RSA utilisée
 * pour vérifier les JWT signés en RS256 (audit v4.7 §6.3 Finding MOYENNE — suite).
 *
 * <p><b>Problème</b> : en RS256, les services qui vérifient les JWT (API gateway, microservices,
 * clients tiers) ont besoin de la clé publique pour valider la signature. Sans endpoint JWKS
 * standardisé (RFC 7517), chaque consommateur doit être configuré manuellement avec la clé
 * publique — opération fastidieuse et propice aux erreurs.
 *
 * <p><b>Solution</b> : endpoint standard {@code /.well-known/jwks.json} qui expose la clé publique
 * au format JWK (JSON Web Key). Les consommateurs peuvent découvrir automatiquement la clé via
 * cet endpoint (OIDC Discovery).
 *
 * <p><b>Sécurité</b> : la clé PRIVÉE n'est JAMAIS exposée. Seule la clé publique est retournée.
 *
 * <p><b>Activation</b> : cet endpoint n'est actif QUE si {@code app.jwt.algorithm=RS256} ET
 * {@code app.jwt.rsa.public-key-path} est configuré. Sinon, l'endpoint retourne 404 (la clé
 * publique HS256 est symétrique — elle ne doit pas être exposée).
 */
@RestController
@Profile("!dev & !test")  // JWKS endpoint seulement en prod/staging — pas en dev (HS256)
@Tag(name = "JWKS", description = "JSON Web Key Set pour validation JWT RS256")
public class JwksController {

    private static final Logger LOG = LoggerFactory.getLogger(JwksController.class);

    @Value("${app.jwt.algorithm:HS256}")
    private String algorithm;

    @Value("${app.jwt.rsa.public-key-path:}")
    private String rsaPublicKeyPath;

    @Value("${app.jwt.rsa.key-id:joaccountant-default}")
    private String rsaKeyId;

    private RSAKey rsaKey;

    @PostConstruct
    void init() {
        if (!"RS256".equalsIgnoreCase(algorithm)) {
            LOG.info("JwksController désactivé — algorithme JWT = {} (pas RS256)", algorithm);
            return;
        }
        if (rsaPublicKeyPath == null || rsaPublicKeyPath.isBlank()) {
            LOG.warn("JwksController : algorithme RS256 actif mais app.jwt.rsa.public-key-path n'est pas configuré. "
                + "L'endpoint /.well-known/jwks.json retournera 404. Pour activer, générer la clé publique avec "
                + "'openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem' et configurer le path.");
            return;
        }
        try {
            String pemContent = Files.readString(Path.of(rsaPublicKeyPath)).trim();
            String pemBody = pemContent
                .replaceAll("-{5}BEGIN [A-Z ]+-{5}", "")
                .replaceAll("-{5}END [A-Z ]+-{5}", "")
                .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pemBody);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(keySpec);

            // Construire la JWK RSA avec keyId pour que les consommateurs puissent la référencer
            rsaKey = new RSAKey.Builder(publicKey)
                .keyID(rsaKeyId)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();

            LOG.info("JwksController initialisé : clé publique RSA chargée depuis {} (keyId={})",
                rsaPublicKeyPath, rsaKeyId);
        } catch (Exception ex) {
            LOG.error("JwksController : impossible de charger la clé publique RSA depuis {}",
                rsaPublicKeyPath, ex);
        }
    }

    /**
     * Endpoint JWKS standard — expose les clés publiques de vérification JWT.
     *
     * <p>Format de réponse (RFC 7517) :
     * <pre>
     * {
     *   "keys": [
     *     {
     *       "kty": "RSA",
     *       "use": "sig",
     *       "alg": "RS256",
     *       "kid": "joaccountant-prod-2026",
     *       "n": "...",
     *       "e": "AQAB"
     *     }
     *   ]
     * }
     * </pre>
     *
     * <p>Si RS256 n'est pas configuré ou si la clé publique n'est pas chargée, retourne un
     * ensemble vide (clés : []). Les consommateurs doivent gérer ce cas.
     */
    @Operation(summary = "JSON Web Key Set (JWKS) — clés publiques de vérification JWT",
        description = "Endpoint standard RFC 7517 pour découvrir automatiquement les clés publiques RSA " +
                      "utilisées pour signer les JWT en RS256.\n\n" +
                      "**Accès public** : aucun Bearer requis — c'est la clé publique, librement diffusable.\n\n" +
                      "**Activation** : cet endpoint n'est actif QUE si `app.jwt.algorithm=RS256` ET " +
                      "`app.jwt.rsa.public-key-path` est configuré. En dev/test (HS256 par défaut), " +
                      "l'endpoint retourne un JWKS vide `{\"keys\":[]}`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "JWKS — ensemble de clés publiques (peut être vide si RS256 non configuré)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = {
                    @ExampleObject(name = "RS256 activé (clé publique présente)",
                        value = """
                            {
                              "keys": [
                                {
                                  "kty": "RSA",
                                  "use": "sig",
                                  "alg": "RS256",
                                  "kid": "joaccountant-prod-2026",
                                  "n": "0Z7Yll0a5tVq5xq8mF3NpR2sK8qLx5uW9vXbC4dE6gT7yU8iO9pA1bC2dE3fG4hI5jK6lM7nO8pQ9rS0tU1vW2xY3zA4bC5dE6fG7hI8jK9lM0nO1pQ2rS3tU4vW5xY6zA7bC8dE9fG0hI1jK2lM3nO4pQ5rS6tU7vW8xY9zA0bC1dE2fG3hI4jK5lM6nO7pQ8rS9tU0vW1xY2zA3bC4dE5fG6hI7jK8lM9nO0pQ1rS2tU3vW4xY5zA6bC7dE8fG9hI0jK1lM2nO3pQ4rS5tU6vW7xY8zA9bC0dE1fG2hI3jK4lM5nO6pQ7rS8tU9vW0xY1zA2bC3dE4fG5hI6jK7lM8nO9pQ0rS1tU2vW3xY4zA5bC6dE7fG8hI9jK0lM1nO2pQ3rS4tU5vW6xY7zA8bC9dE0fG1hI2jK3lM4nO5pQ6rS7tU8vW9xY0zA1bC2dE3fG4hI5jK6lM7nO8pQ9rS0tU1vW2xY3zA4bC5dE6fG7hI8jK9lM0nO1pQ2rS3tU4vW5xY6zA7bC8dE9fG0hI1jK2lM3nO4pQ5rS6tU7vW8xY9zA0bC1dE2fG3hI4jK5lM6nO7pQ8rS9tU0vW1xY2zA3bC4dE5fG6hI7jK8lM9nO0pQ",
                                  "e": "AQAB"
                                }
                              ]
                            }
                            """),
                    @ExampleObject(name = "HS256 (défaut dev) — JWKS vide",
                        value = """
                            {"keys":[]}
                            """)
                }))
    })
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getJwks() {
        if (rsaKey == null) {
            // RS256 non configuré ou clé publique manquante — retourner un JWKS vide
            return "{\"keys\":[]}";
        }
        JWKSet jwkSet = new JWKSet(rsaKey);
        // JWKSet.toJSONObject() retourne un Map<String,Object> — sérialiser en JSON via Jackson
        // (déjà dans le classpath via spring-boot-starter-web)
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(jwkSet.toJSONObject());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            LOG.error("Échec de sérialisation JWKS", ex);
            return "{\"keys\":[]}";
        }
    }
}

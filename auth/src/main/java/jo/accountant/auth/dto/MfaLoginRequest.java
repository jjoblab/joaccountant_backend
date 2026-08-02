package jo.accountant.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * (lot-A-securite) — DTO pour {@code POST /api/v1/auth/login/mfa}.
 *
 * <p>Remplace les {@code @RequestParam} précédents qui exposaient le
 * {@code mfaChallengeToken} dans la query string (et donc dans les logs nginx/Tomcat,
 * voire dans les Referer envoyés à des tiers). En passant le token dans le body JSON,
 * on évite cette fuite : nginx ne logue par défaut que la query string (access_log
 * `$request`), pas le body.
 *
 * <p>Le {@code code} TOTP reste un entier 6 chiffres (RFC 6238). Sa validation effective
 * (valeur dans [0, 999999] + correspondance avec le secret TOTP de l'utilisateur) est
 * déléguée à {@code MfaService.verifyCode(UUID, int)} — pas de duplication de la logique
 * métier côté DTO.
 *
 * @param mfaChallengeToken JWT court (5 min TTL) délivré par {@code POST /api/v1/auth/login}
 * quand l'utilisateur a activé la MFA. Non vide.
 * @param code code TOTP 6 chiffres saisi par l'utilisateur depuis son app
 * authenticator (Google Authenticator, Authy, etc.).
 */
public record MfaLoginRequest(
 @NotBlank String mfaChallengeToken,
 int code
) {}

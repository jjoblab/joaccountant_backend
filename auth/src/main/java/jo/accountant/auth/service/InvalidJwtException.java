package jo.accountant.auth.service;

import jo.accountant.core.exception.ForbiddenException;

/**
 * R-01 (lot-A-securite) — Levée par {@link JwtService#parseAndVerifyClaims(String)} quand
 * un JWT refusé (signature invalide, expiré, mal formé) est présenté à l'endpoint MFA.
 *
 * <p>Étend {@link ForbiddenException} pour être automatiquement mappée en HTTP 403 par
 * {@link jo.accountant.core.exception.GlobalExceptionHandler} (au même titre qu'un mot de
 * passe invalide). On n'utilise PAS 401 (Unauthorized) car l'authentification a déjà
 * réussi à l'étape 1 du login MFA — c'est le second facteur qui échoue, ce qui correspond
 * sémantiquement à un refus d'accès (403).
 *
 * <p>Le code {@code MFA_CHALLENGE_TOKEN_INVALID} est stable et peut être branché côté
 * frontend pour différencier ce cas d'un code TOTP invalide ({@code MFA_CODE_INVALID}).
 */
public class InvalidJwtException extends ForbiddenException {

    private static final long serialVersionUID = 1L;

    public InvalidJwtException(String message) {
        super("MFA_CHALLENGE_TOKEN_INVALID", message);
    }

    public InvalidJwtException(String message, Throwable cause) {
        super("MFA_CHALLENGE_TOKEN_INVALID", message, cause);
    }
}

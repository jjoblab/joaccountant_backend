package jo.accountant.auth.dto;

import java.util.List;
import java.util.Map;

/**
 * Réponse de login.
 *
 * <p><b>(session 14) — MFA login 2-step</b> : ajout des champs
 * {@code mfaRequired} et {@code mfaChallengeToken}. Si l'utilisateur a activé la MFA,
 * le login retourne {@code mfaRequired=true} + un {@code mfaChallengeToken} (JWT court 5min)
 * au lieu des tokens normaux. Le client doit alors envoyer
 * {@code POST /auth/login/mfa?challenge=...&code=123456} pour obtenir les tokens normaux.
 
 *
 * @author jo@Dev


*/
public record LoginResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    java.util.UUID userId,
    String email,
    String fullName,
    List<Map<String, Object>> companies,
    //— MFA 2-step
    boolean mfaRequired,
    String mfaChallengeToken
) {
    /**
     * Constructeur de rétro-compatibilité — pour les logins sans MFA.
     */
    public LoginResponse(String accessToken, String refreshToken, String tokenType,
                         long expiresIn, java.util.UUID userId, String email,
                         String fullName, List<Map<String, Object>> companies) {
        this(accessToken, refreshToken, tokenType, expiresIn, userId, email, fullName,
             companies, false, null);
    }
}

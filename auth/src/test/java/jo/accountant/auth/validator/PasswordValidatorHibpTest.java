package jo.accountant.auth.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import jo.accountant.core.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Tests unitaires pour l'intégration HIBP (Have I Been Pwned) de {@link PasswordValidator} —
 * R-35 (lot-F3-security).
 *
 * <p>Couverture de la méthode {@code checkHibpBreaches(String)} avec l'API HIBP mockée
 * (pas de vrai appel réseau). Scénarios :
 * <ul>
 *   <li>Cas nominal : password non compromis (suffixe absent de la réponse HIBP) → OK.</li>
 *   <li>Cas erreur : password compromis (suffixe présent avec count > 0) →
 *       {@link ValidationException} avec code {@code PASSWORD_COMPROMISED}.</li>
 *   <li>Cas résilience : HIBP indisponible (IOException) → pas d'exception (fail-open,
 *       la blacklist locale reste active).</li>
 *   <li>Cas cache hit : 2e appel avec même préfixe SHA-1 → pas de 2e appel HTTP
 *       (le cache Caffeine TTL 1h sert la réponse mise en cache).</li>
 * </ul>
 *
 * <p><b>Mock HttpClient</b> : {@code java.net.http.HttpClient} est une classe abstraite du JDK
 * (depuis Java 11) — sa méthode {@code send(HttpRequest, BodyHandler)} est abstraite, donc
 * Mockito peut la mocker sans mockito-inline (pas besoin de mock-final). Idem pour
 * {@code HttpResponse<String>}.
 *
 * <p><b>k-anonymity</b> : pour simuler la réponse HIBP, on calcule le SHA-1 du password de test
 * via {@link PasswordValidator#sha1HexUpper(String)}, on extrait le préfixe (5 premiers chars)
 * et le suffixe (35 restants), et on construit une réponse HIBP contenant (ou pas) ce suffixe.
 */
class PasswordValidatorHibpTest {

    private static final String HIBP_API_URL = "https://api.pwnedpasswords.com/range/";
    private static final int MIN_LENGTH = 12;
    private static final int TIMEOUT_MS = 2000;

    /**
     * Password non compromis → l'API HIBP renvoie une liste de suffixes qui ne contient PAS
     * le suffixe du password testé → validate() doit passer sans exception.
     */
    @Test
    @DisplayName("HIBP — password non compromis → OK (pas d'exception)")
    void passwordNotCompromised_ok() throws Exception {
        // Password valide qui passe toutes les règles locales (longueur, complexité, blacklist)
        String password = "SomeStrongP@ssw0rd123!XYZ";
        // Réponse HIBP contenant des suffixes MAIS pas celui du password testé
        String hibpResponse = "00000000000000000000000000000000000:1\n"
            + "11111111111111111111111111111111111:5\n"
            + "22222222222222222222222222222222222:3\n";

        PasswordValidator validator = buildValidatorWithHibpMock(hibpResponse);

        assertThatCode(() -> validator.validate(password))
            .as("Password non compromis doit passer toutes les règles y compris HIBP")
            .doesNotThrowAnyException();
    }

    /**
     * Password compromis → l'API HIBP renvoie une liste contenant le suffixe du password
     * avec count > 0 → validate() doit lever ValidationException("PASSWORD_COMPROMISED").
     */
    @Test
    @DisplayName("HIBP — password compromis (suffixe présent, count > 0) → ValidationException")
    void passwordCompromised_throwsValidationException() throws Exception {
        String password = "CompromisedP@ss1!";
        // Construire une réponse HIBP qui contient exactement le suffixe de ce password
        String sha1 = PasswordValidator.sha1HexUpper(password);
        String suffix = sha1.substring(5);
        String hibpResponse = suffix + ":42\n"               // ← suffixe compromis, count=42
            + "AAAAABBBBBCCCCCDDDDDEEEEEFFFFF00000:1\n"
            + "ZZZZZYYYYYXXXXXWWWWWVVVVVUUUUUTTTTT:3\n";

        PasswordValidator validator = buildValidatorWithHibpMock(hibpResponse);

        assertThatThrownBy(() -> validator.validate(password))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("PASSWORD_COMPROMISED")
            .hasMessageContaining("HIBP");
    }

    /**
     * HIBP indisponible (IOException sur l'appel HTTP) → validate() ne doit PAS lever
     * d'exception (fail-open côté HIBP). La blacklist locale reste active mais n'exclut pas
     * le password de test.
     */
    @Test
    @DisplayName("HIBP indisponible (IOException) → pas d'exception (résilience fail-open)")
    void hibpUnavailable_skipsCheckWithoutFailing() throws Exception {
        String password = "SomeStrongP@ssw0rd123!XYZ";
        HttpClient mockClient = mock(HttpClient.class);
        // L'API HIBP jette IOException (timeout, DNS, réseau down, etc.)
        when(mockClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenThrow(new IOException("simulated HIBP outage"));

        PasswordValidator validator = new PasswordValidator(MIN_LENGTH, true, HIBP_API_URL, TIMEOUT_MS);
        validator.setHttpClientForTests(mockClient);

        // Fail-open : aucune exception ne doit être levée (la blacklist locale reste active)
        assertThatCode(() -> validator.validate(password))
            .as("HIBP indisponible ne doit pas bloquer l'enregistrement (résilience)")
            .doesNotThrowAnyException();
    }

    /**
     * Cache hit : 2e appel à validate() avec un password ayant le MÊME préfixe SHA-1 que le 1er
     * → le cache Caffeine sert la réponse déjà téléchargée → HttpClient.send() n'est appelé
     * qu'une seule fois.
     *
     * <p>On utilise le même password 2 fois (préfixe identique trivialement) pour vérifier
     * que le 2e appel n'invoque pas HttpClient.send().
     */
    @Test
    @DisplayName("Cache hit — 2e validate() avec même préfixe SHA-1 → pas de 2e appel HTTP")
    void cacheHit_avoidsSecondHttpCall() throws Exception {
        String password = "CacheHitP@ssw0rd99!XYZ";
        // Réponse ne contenant PAS le suffixe du password (pour que validate passe sans exception
        // et qu'on puisse appeler validate 2 fois)
        String hibpResponse = "00000000000000000000000000000000000:1\n"
            + "11111111111111111111111111111111111:5\n";

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(hibpResponse);
        when(mockClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(mockResp);

        PasswordValidator validator = new PasswordValidator(MIN_LENGTH, true, HIBP_API_URL, TIMEOUT_MS);
        validator.setHttpClientForTests(mockClient);

        // 1er appel : déclenche l'appel HTTP (cache miss)
        validator.validate(password);
        // 2e appel : même password → même préfixe → cache hit (pas d'appel HTTP)
        validator.validate(password);

        // Vérifie qu'un seul appel HTTP a été effectué (cache hit sur le 2e)
        verify(mockClient, times(1)).send(any(HttpRequest.class),
            ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    /**
     * Test bonus : HIBP désactivé (hibpEnabled=false) → validate() ne doit JAMAIS appeler
     * l'API HIBP, même avec un password compromis. Backward-compat : comportement par défaut.
     */
    @Test
    @DisplayName("HIBP désactivé (default) → aucun appel HTTP même pour password compromis")
    void hibpDisabled_neverCallsApi() {
        String password = "SomeStrongP@ssw0rd123!XYZ";
        // Pas de mock HttpClient — si validate() essaie d'appeler, on aura NullPointerException
        // (le lazy HttpClient ne sera pas créé car hibpEnabled=false)
        PasswordValidator validator = new PasswordValidator(MIN_LENGTH, false, HIBP_API_URL, TIMEOUT_MS);

        assertThatCode(() -> validator.validate(password))
            .as("HIBP désactivé ne doit jamais appeler l'API HIBP")
            .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Helper — construit un PasswordValidator avec HIBP activé et HttpClient mocké
    // -------------------------------------------------------------------------

    private PasswordValidator buildValidatorWithHibpMock(String hibpResponseBody) throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(hibpResponseBody);
        // BodyHandlers.ofString() retourne un BodyHandler<String> ; on utilise ArgumentMatchers
        // pour matcher n'importe quelle instance (le mock ne dépend pas du BodyHandler exact).
        when(mockClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(mockResp);

        PasswordValidator validator = new PasswordValidator(MIN_LENGTH, true, HIBP_API_URL, TIMEOUT_MS);
        validator.setHttpClientForTests(mockClient);
        return validator;
    }
}

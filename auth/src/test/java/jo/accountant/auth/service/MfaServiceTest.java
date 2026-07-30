package jo.accountant.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jo.accountant.auth.repository.MfaSecretRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Tests unitaires pour {@link MfaService} — R-16 (lot-D-qualite-arch).
 *
 * <p>Couverture de la méthode {@code @PostConstruct validateEncryptionKey()} (R-02 —
 * lot-A-securite) qui fait du fail-fast au démarrage si la clé de chiffrement MFA est faible.
 *
 * <p>Scénarios :
 * <ul>
 *   <li>Cas nominal : clé forte de 32+ caractères en profil prod → OK.</li>
 *   <li>Cas nominal : clé par défaut en profil dev/test → OK (réservée au dev).</li>
 *   <li>Cas erreur : clé trop courte (&lt; 32 chars) → IllegalStateException (tout profil).</li>
 *   <li>Cas erreur : clé par défaut en profil prod → IllegalStateException.</li>
 *   <li>Cas edge : clé par défaut en profil sans dev ni test → IllegalStateException.</li>
 * </ul>
 */
class MfaServiceTest {

    private static final String DEFAULT_DEV_KEY = "dev-only-mfa-key-please-override-32-chars";
    private static final String STRONG_KEY = "prod-strong-key-0123456789-abcdefghijklmnopqrstuvwxyz-0123";

    private MfaSecretRepository repository;
    private Environment environment;

    private MfaService buildService(String encryptionKey, boolean isDevOrTest, String... activeProfiles) {
        repository = mock(MfaSecretRepository.class);
        environment = mock(Environment.class);
        when(environment.matchesProfiles("dev", "test")).thenReturn(isDevOrTest);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        MfaService service = new MfaService(repository, encryptionKey, environment);
        // Le @PostConstruct est appelé manuellement (pas de Spring ici).
        service.validateEncryptionKey();
        return service;
    }

    @Test
    @DisplayName("validateEncryptionKey — nominal : clé forte (32+ chars) en profil prod → OK")
    void validateEncryptionKey_strongKeyInProd() {
        assertThatCode(() ->
            buildService(STRONG_KEY, false, "prod"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateEncryptionKey — nominal : clé dev par défaut en profil dev → OK")
    void validateEncryptionKey_defaultKeyInDev() {
        assertThatCode(() ->
            buildService(DEFAULT_DEV_KEY, true, "dev"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateEncryptionKey — error : clé trop courte → IllegalStateException (tout profil)")
    void validateEncryptionKey_shortKeyThrowsEvenInDev() {
        // Même en dev/test, une clé trop courte doit échouer (entropie insuffisante pour AES-256)
        assertThatThrownBy(() ->
            buildService("short", true, "test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trop courte");

        // Et également en prod
        assertThatThrownBy(() ->
            buildService("short", false, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trop courte");
    }

    @Test
    @DisplayName("validateEncryptionKey — error : clé dev par défaut en profil prod → IllegalStateException")
    void validateEncryptionKey_defaultKeyInProdThrows() {
        assertThatThrownBy(() ->
            buildService(DEFAULT_DEV_KEY, false, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inacceptable en production");
    }

    @Test
    @DisplayName("validateEncryptionKey — edge : profil sans dev ni test (default profile) → rejet de la clé dev")
    void validateEncryptionKey_defaultKeyWithDefaultProfileThrows() {
        // isDevOrTest=false simule un démarrage sans profil actif OU avec un profil non-dev
        assertThatThrownBy(() ->
            buildService(DEFAULT_DEV_KEY, false, new String[0]))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inacceptable en production");
    }
}

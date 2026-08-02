package jo.accountant.invoicing.signature;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés de configuration pour {@link XAdESSignatureService} —.
 *
 * <p>Liées au préfixe {@code app.signature.xades} dans {@code application.yml} :
 *
 * <pre>
 * app:
 * signature:
 * xades:
 * enabled: ${XADES_ENABLED:false}
 * keystore-path: ${XADES_KEYSTORE_PATH:}
 * keystore-password: ${XADES_KEYSTORE_PASSWORD:}
 * keystore-type: ${XADES_KEYSTORE_TYPE:PKCS12}
 * tsa-url: ${XADES_TSA_URL:}
 * </pre>
 *
 * <p><b>Sécurité</b> : {@code keystorePassword} contient un secret. Ne JAMAIS le logger.
 * En production, utiliser un secret K8s/Docker plutôt qu'une variable d'environnement en clair.
 *
 * @see XAdESSignatureService
 * @see SignatureConfig
 
 *
 * @author jo@Dev


*/
@ConfigurationProperties(prefix = "app.signature.xades")
public class SignatureProperties {

 /** Active l'implémentation XAdES (false = NoOp par défaut). */
 private boolean enabled = false;

 /**
 * Chemin vers le keystore PKCS12 contenant le certificat qualifié et la clé privée.
 * Ex: {@code /etc/joaccountant/keystore/cert.p12}.
 */
 private String keystorePath = "";

 /** Mot de passe du keystore (SECRET — ne pas logger). */
 private String keystorePassword = "";

 /** Type de keystore : PKCS12 (défaut), JKS, ou PKCS11 (HSM). */
 private String keystoreType = "PKCS12";

 /**
 * URL d'un TSA (Time Stamp Authority) qualifié pour XAdES-T.
 * Ex: {@code https://timestamp.digicert.com} (gratuit) ou
 * {@code https://tsa.universign.com} (payant).
 * Vide = pas de timestamp (XAdES-BES seulement).
 */
 private String tsaUrl = "";

 public boolean isEnabled() {
 return enabled;
 }

 public void setEnabled(boolean enabled) {
 this.enabled = enabled;
 }

 public String getKeystorePath() {
 return keystorePath;
 }

 public void setKeystorePath(String keystorePath) {
 this.keystorePath = keystorePath;
 }

 public String getKeystorePassword() {
 return keystorePassword;
 }

 public void setKeystorePassword(String keystorePassword) {
 this.keystorePassword = keystorePassword;
 }

 public String getKeystoreType() {
 return keystoreType;
 }

 public void setKeystoreType(String keystoreType) {
 this.keystoreType = keystoreType;
 }

 public String getTsaUrl() {
 return tsaUrl;
 }

 public void setTsaUrl(String tsaUrl) {
 this.tsaUrl = tsaUrl;
 }
}

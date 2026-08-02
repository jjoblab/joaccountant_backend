package jo.accountant.invoicing.signature;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implémentation NoOp (no-operation) de {@link ElectronicSignatureService} —.
 *
 * <p>Implémentation par défaut, activée via {@link SignatureConfig} lorsque aucune autre
 * implémentation n'est configurée. Retourne le document non signé avec des métadonnées
 * marquées {@code "noop"}.
 *
 * <p><b>Usage</b> :
 * <ul>
 * <li>Environnements dev/test : permet de tester le endpoint {@code /sign} sans dépendre
 * d'un partenaire CA.</li>
 * <li>Production sans partenaire CA : l'application démarre sans crasher, le endpoint
 * {@code /sign} retourne le document non signé (avec un WARNING dans les logs).</li>
 * </ul>
 *
 * <p><b>Sécurité</b> : cette implémentation NE SIGNE PAS le document. Le document retourné
 * n'a AUCUNE valeur juridique. Pour activer une vraie signature, configurer
 * {@code app.signature.xades.enabled=true} + {@code app.signature.xades.keystore-path=...}
 * (voir {@link XAdESSignatureService}).
 *
 * <p><b>Activation</b> : via {@link SignatureConfig#noOpElectronicSignatureService()} avec
 * l'annotation {@code @ConditionalOnMissingBean(ElectronicSignatureService.class)}. Dès qu'une
 * autre implémentation (XAdES, PAdES, etc.) est enregistrée comme bean, NoOp est désactivée
 * automatiquement.
 */
public class NoOpElectronicSignatureService implements ElectronicSignatureService {

 private static final Logger LOG = LoggerFactory.getLogger(NoOpElectronicSignatureService.class);

 /**
 * Constructeur — loggue un WARNING au démarrage pour signaler que la signature n'est
 * pas active. Le WARNING est répété à chaque appel {@link #sign} pour rappeler
 * l'absence de valeur juridique.
 */
 public NoOpElectronicSignatureService() {
 // v2.5.2 — baissé à INFO : c'est un état attendu en démo/dev (pas un bug).
 // En prod, si signature réelle est requise, configurer app.signature.xades.enabled=true.
 LOG.info("Electronic signature not configured — using NoOp implementation. "
 + "Documents will NOT be legally signed (expected in demo/dev). To enable "
 + "real signature, set app.signature.xades.enabled=true and configure keystore.");
 }

 @Override
 public SignatureResult sign(byte[] documentBytes, SignableDocumentType documentType, UUID companyId) {
 LOG.warn("NoOp sign called for {} (company={}) — returning UNSIGNED document. "
 + "This document has NO legal value. Configure XAdES signature for production.",
 documentType, companyId);
 return new SignatureResult(
 documentBytes, // document non signé (bytes originaux)
 "noop", // numéro de série factice
 "NoOp Electronic Signature Service", // issuer factice
 Instant.now(), // date de "signature" (pour audit)
 null, // pas de timestamp TSA
 "noop"); // algorithme factice
 }

 @Override
 public boolean verify(byte[] signedDocumentBytes) {
 LOG.warn("NoOp verify called — returning false (no real signature can be present "
 + "with NoOp implementation)");
 // Aucune signature réelle n'est jamais posée → verify retourne toujours false.
 // Ceci empêche un attaquant de soumettre un document non signé et de le faire
 // passer pour valide.
 return false;
 }
}

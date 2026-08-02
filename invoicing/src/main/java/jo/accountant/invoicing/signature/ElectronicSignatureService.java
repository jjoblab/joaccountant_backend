package jo.accountant.invoicing.signature;

import java.util.UUID;

/**
 * Service de signature électronique de documents.
 *
 * <p>Framework extensible pour la signature électronique conforme au Décret du 12 février 2002
 * (Haïti) et à l'arrêté DGI du 4 octobre 2017. Permet de signer des documents PDF (factures,
 * avoirs, bulletins de paie) ou XML (Factur-X, états financiers) avec un certificat qualifié.
 *
 * <p><b>Implémentations fournies</b> :
 * <ul>
 * <li>{@link NoOpElectronicSignatureService} — implémentation par défaut qui retourne le
 * document non signé (pour les environnements dev/test et la production sans partenaire
 * CA configuré). Activée automatiquement via {@code @ConditionalOnMissingBean}.</li>
 * <li>{@link XAdESSignatureService} — squelette pour une implémentation réelle XAdES
 * (XML Advanced Electronic Signature, ETSI EN 319 132). Désactivée par défaut via
 * {@code @ConditionalOnProperty(name="app.signature.xades.enabled", havingValue="true")}.
 * Nécessite l'intégration d'une lib (xades4j, jsign) et d'un certificat qualifié.</li>
 * </ul>
 *
 * <p><b>Extensibilité</b> : une nouvelle implémentation (ex: PAdES pour PDF, CAdES pour
 * binary) peut être ajoutée en créant un {@code @Service} qui implémente cette interface.
 * Le {@code @ConditionalOnMissingBean} de {@link NoOpElectronicSignatureService} garantit
 * que le NoOp est remplacé automatiquement par la nouvelle implémentation.
 *
 * <p><b>Sécurité</b> : la méthode {@link #sign} ne doit JAMAIS logger le contenu du document
 * ni le certificat privé. Les métadonnées retournées (numéro de série, issuer) sont publiques
 * et peuvent être loggées pour audit.
 *
 * <p><b>Cadre légal Haïti</b> :
 * <ul>
 * <li>Décret du 12 février 2002 — reconnaît la signature électronique avec certificat
 * qualifié comme équivalente à la signature manuscrite (art. 5).</li>
 * <li>Arrêté DGI du 4 octobre 2017 — impose la signature électronique pour les factures
 * électroniques transmises à la DGI (art. 3).</li>
 * <li>Code Fiscal Haïtien (art. 196) — mentions obligatoires sur les factures
 * (numéro, date, identification du vendeur/acheteur, montant HT/TTC/TVA).</li>
 * </ul>
 *
 * <p>Voir {@code docs/ELECTRONIC_SIGNATURE.md} pour le détail du cadre légal, les autorités
 * de certification reconnues (eIDAS qualifiées : Universign, DocuSign, Chronodoc), et les
 * étapes d'intégration (obtention certificat, configuration keystore, TSA).
 */
public interface ElectronicSignatureService {

 /**
 * Signe un document PDF ou XML avec un certificat qualifié.
 *
 * <p><b>Processus</b> (implémentation XAdES réelle) :
 * <ol>
 * <li>Charger le keystore PKCS12 ({@code app.signature.xades.keystore-path}).</li>
 * <li>Charger la clé privée + certificat X.509 qualifié.</li>
 * <li>Calculer le digest SHA-256 du document (canonicalization C14N pour XML).</li>
 * <li>Signer le digest avec la clé privée (RSA-SHA256 ou ECDSA-SHA256).</li>
 * <li>Embarquer la signature XAdES-BES dans le document (enveloppé pour XML,
 * /EmbeddedFile pour PDF/A-3 PAdES).</li>
 * <li>(Optionnel) Demander un timestamp RFC 3161 à un TSA qualifié
 * ({@code app.signature.xades.tsa-url}) → XAdES-T.</li>
 * <li>Retourner le document signé + métadonnées.</li>
 * </ol>
 *
 * @param documentBytes le document à signer (bytes bruts : PDF, XML, etc.)
 * @param documentType le type de document (INVOICE, CREDIT_NOTE, PAYSLIP, FINANCIAL_STATEMENT)
 * @param companyId l'entreprise émettrice (pour audit trail + sélection du certificat
 * si plusieurs entreprises partagent l'instance)
 * @return {@link SignatureResult} contenant le document signé + métadonnées
 * (certificat, timestamp, TSA)
 * @throws jo.accountant.core.exception.BusinessException si la signature échoue
 * (certificat expiré, keystore illisible, TSA indisponible, etc.)
 */
 SignatureResult sign(byte[] documentBytes, SignableDocumentType documentType, UUID companyId);

 /**
 * Vérifie la signature d'un document.
 *
 * <p><b>Processus</b> :
 * <ol>
 * <li>Extraire la signature XAdES/PAdES du document.</li>
 * <li>Récupérer le certificat X.509 signataire (embarqué ou via URL d'embed).</li>
 * <li>Vérifier la chaîne de confiance jusqu'à une CA qualifiée reconnue.</li>
 * <li>Vérifier la non-révocation (OCSP ou CRL).</li>
 * <li>Vérifier le digest du document contre la signature.</li>
 * <li>(Optionnel) Vérifier le timestamp TSA.</li>
 * </ol>
 *
 * @param signedDocumentBytes le document signé (avec signature embarquée)
 * @return {@code true} si la signature est valide (certificat信任, document non modifié,
 * timestamp OK), {@code false} sinon
 */
 boolean verify(byte[] signedDocumentBytes);
}

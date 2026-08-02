package jo.accountant.invoicing.signature;

import java.time.Instant;

/**
 * Résultat d'une signature électronique.
 *
 * <p>Contient le document signé (bytes) + les métadonnées de signature nécessaires
 * pour la vérification ultérieure et l'audit trail.
 *
 * <p><b>Champs</b> :
 * <ul>
 * <li>{@link #signedBytes()} — le document original augmenté de la signature
 * (PDF signé avec PAdES, XML signé avec XAdES enveloppé, etc.).</li>
 * <li>{@link #certificateSerialNumber()} — numéro de série du certificat qualifié
 * utilisé pour signer (format X.509, ex: {@code "2D:9E:8A:1F:..."}).</li>
 * <li>{@link #certificateIssuer()} — DN (Distinguished Name) de l'autorité de
 * certification émettrice (ex: {@code "CN=Universign Qualified CA 2027,O=Universign,C=FR"}).</li>
 * <li>{@link #signedAt()} — instant de signature (UTC, ISO-8601).</li>
 * <li>{@link #tsaTimestamp()} — timestamp qualifié RFC 3161 délivré par un TSA
 * (Time Stamp Authority) — preuve d'existence du document à cet instant.
 * Peut être {@code null} si la signature est XAdES-BES (sans timestamp) ;
 * obligatoire pour XAdES-T / XAdES-LT / XAdES-A (niveaux supérieurs eIDAS).</li>
 * <li>{@link #signatureAlgorithm()} — algorithme de signature
 * (ex: {@code "RSA-SHA256"}, {@code "ECDSA-SHA256"}, {@code "Ed25519"}).</li>
 * </ul>
 *
 * <p><b>Cadre légal Haïti</b> : Décret du 12 février 2002 sur la signature électronique
 * (équivalent de la directive européenne 1999/93/CE) reconnaît la signature électronique
 * avec certificat qualifié comme ayant la même valeur juridique que la signature manuscrite.
 * L'arrêté DGI du 4 octobre 2017 impose la signature électronique pour les factures
 * électroniques transmises à la DGI. Voir {@code docs/ELECTRONIC_SIGNATURE.md}.
 *
 * @param certificateSerialNumber numéro de série X.509 du certificat signataire
 * @param certificateIssuer DN de l'autorité de certification émettrice
 * @param signedAt instant de signature (UTC)
 * @param tsaTimestamp timestamp RFC 3161 qualifié (peut être null en XAdES-BES)
 * @param signatureAlgorithm algorithme de signature (ex: "RSA-SHA256")
 */
public record SignatureResult(
 byte[] signedBytes,
 String certificateSerialNumber,
 String certificateIssuer,
 Instant signedAt,
 Instant tsaTimestamp,
 String signatureAlgorithm) {
}

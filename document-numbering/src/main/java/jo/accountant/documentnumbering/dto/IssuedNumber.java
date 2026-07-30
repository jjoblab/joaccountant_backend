package jo.accountant.documentnumbering.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.documentnumbering.entity.DocumentType;

/**
 * Réponse de {@link jo.accountant.documentnumbering.service.DocumentNumberingService#nextNumber}
 * — résultat de la consommation effective d'un numéro.
 *
 * <p>Contrairement à {@link NextNumberPreview}, cet objet n'est renvoyé que lorsque le compteur
 * a réellement été incrémenté. Le {@link #issuedAt} est l'horodatage exact de l'incrémentation
 * (utile pour l'audit trail).
 */
public record IssuedNumber(
    UUID companyId,
    DocumentType documentType,
    String scopeKey,
    String periodKey,
    String number,
    long value,
    Instant issuedAt
) {}

package jo.accountant.documentnumbering.dto;

import java.util.UUID;
import jo.accountant.documentnumbering.entity.DocumentType;

/**
 * Réponse de {@code GET .../sequences/{documentType}/next-preview}.
 *
 * <p>Aperçu NON consommateur (§6) : {@link #nextNumber} reflète la valeur que prendrait le
 * prochain numéro si l'émission était déclenchée maintenant, mais le compteur n'est PAS
 * incrémenté. Aucun verrou n'est posé — deux appels consécutifs à {@code next-preview}
 * retournent donc le même numéro.
 *
 * <p>Cet aperçu peut être utilisé par l'UI pour afficher "Prochain numéro : FAC-2026-000143"
 * avant que l'utilisateur ne valide. L'utilisateur n'a aucune garantie que ce sera le numéro
 * réellement attribué : si une autre émission se produit entre l'aperçu et la validation, le
 * numéro réel sera différent. C'est acceptable : l'aperçu est purement informatif.
 */
public record NextNumberPreview(
    UUID companyId,
    DocumentType documentType,
    String scopeKey,
    String periodKey,
    String nextNumber,
    long nextValue
) {}

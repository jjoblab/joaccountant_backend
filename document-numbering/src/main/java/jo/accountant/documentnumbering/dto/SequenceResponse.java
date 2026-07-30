package jo.accountant.documentnumbering.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;

/**
 * Réponse pour {@code GET .../sequences} et {@code POST .../sequences}.
 */
public record SequenceResponse(
    UUID id,
    UUID companyId,
    DocumentType documentType,
    String scopeKey,
    String prefix,
    boolean includeYear,
    int padding,
    ResetPolicy resetPolicy,
    Instant createdAt,
    Instant updatedAt
) {}

package jo.accountant.documentgeneration.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;

/**
 * GeneratedDocumentResponse.
 *
 * @author jo@Dev


 */

public record GeneratedDocumentResponse(
    UUID id,
    UUID companyId,
    GeneratedDocumentType documentType,
    UUID resourceId,
    String storageKey,
    Instant generatedAt,
    UUID generatedBy,
    String checksum
) {}

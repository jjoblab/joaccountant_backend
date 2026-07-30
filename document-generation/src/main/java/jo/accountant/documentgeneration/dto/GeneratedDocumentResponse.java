package jo.accountant.documentgeneration.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.DocumentType;

public record GeneratedDocumentResponse(
    UUID id,
    UUID companyId,
    DocumentType documentType,
    UUID resourceId,
    String storageKey,
    Instant generatedAt,
    UUID generatedBy,
    String checksum
) {}

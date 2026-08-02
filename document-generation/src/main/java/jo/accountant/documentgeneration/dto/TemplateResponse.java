package jo.accountant.documentgeneration.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;

public record TemplateResponse(
    UUID id,
    UUID companyId,
    GeneratedDocumentType documentType,
    boolean active,
    boolean isDefault,
    Instant createdAt,
    Instant updatedAt
) {}

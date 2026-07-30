package jo.accountant.documentgeneration.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.DocumentType;

public record TemplateResponse(
    UUID id,
    UUID companyId,
    DocumentType documentType,
    boolean active,
    boolean isDefault,
    Instant createdAt,
    Instant updatedAt
) {}

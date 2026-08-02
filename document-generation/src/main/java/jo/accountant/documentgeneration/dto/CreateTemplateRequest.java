package jo.accountant.documentgeneration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;

public record CreateTemplateRequest(
    @NotNull GeneratedDocumentType documentType,
    @NotBlank String htmlTemplate,
    Boolean isDefault
) {
    public CreateTemplateRequest {
        if (isDefault == null) isDefault = true;
    }
}

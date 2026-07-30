package jo.accountant.documentgeneration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jo.accountant.documentgeneration.entity.DocumentType;

public record CreateTemplateRequest(
    @NotNull DocumentType documentType,
    @NotBlank String htmlTemplate,
    Boolean isDefault
) {
    public CreateTemplateRequest {
        if (isDefault == null) isDefault = true;
    }
}

package jo.accountant.documentnumbering.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;

/**
 * Corps de requête pour {@code POST /api/v1/companies/{companyId}/document-numbering/sequences}.
 *
 * <p>Crée ou met à jour la configuration d'une séquence documentaire. Un enregistrement
 * préexistant pour le même couple (documentType, scopeKey) déclenche un 409 — il n'y a pas
 * d'édition soft des paramètres (prefix, padding, etc.) pour éviter une incohérence de format
 * avec les numéros déjà émis. Pour modifier, supprimer et recréer (avec audit).
 
 *
 * @author jo@Dev


*/
public record CreateSequenceRequest(
    @NotNull DocumentType documentType,

    /** Clé de portée. Chaîne vide autorisée (= séquence unique par type). */
    @NotBlank @Size(max = 30) String scopeKey,

    @NotBlank @Size(max = 20) String prefix,

    @NotNull Boolean includeYear,

    @NotNull @Min(1) @Max(12) Integer padding,

    @NotNull ResetPolicy resetPolicy
) {
    /** Constructeur canonique avec valeurs par défaut pour includeYear et padding. */
    public CreateSequenceRequest {
        if (scopeKey == null) scopeKey = "";
        if (includeYear == null) includeYear = true;
        if (padding == null) padding = 6;
    }
}

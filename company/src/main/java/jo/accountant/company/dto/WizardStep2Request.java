package jo.accountant.company.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import jo.accountant.company.entity.Sector;

/**
 * V8.2 — Wizard étape 2 : Activité & type métier (refondu).
 *
 * <p>Fusionne les anciennes étapes 3 (sector), 4 (business type), 5 (activity),
 * 7 (required fields) et 8 (module selection) en une seule étape.
 */
public record WizardStep2Request(
    @NotBlank @Size(min = 5, max = 300) String primaryActivityLabel,
    @NotBlank String businessTypeCode,
    Sector sector,  // optionnel — défaut = BusinessType.defaultSector
    Map<String, Object> extraAttributes,
    List<String> customModules  // requis si businessTypeCode == "CUSTOM"
) {}

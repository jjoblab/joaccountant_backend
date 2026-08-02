package jo.accountant.company.dto;

import jo.accountant.company.entity.BusinessTypeRequiredField;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.entity.BusinessType;
import java.util.List;

/**
 * Catalogue public d'un type métier + ses champs requiset 7 du wizard).
 *
 * <p>Sert de payload de réponse pour :
 * <ul>
 * <li>{@code GET /api/v1/business-types} — liste de tous les types actifs ;</li>
 * <li>{@code GET /api/v1/business-types/{code}} — détail d'un type + ses champs requis.</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public record BusinessTypeResponse(
    String code,
    String label,
    OrganizationNature defaultOrganizationNature,
    Sector defaultSector,
    String description,
    boolean active,
    List<ModuleCodeSummary> suggestedModules,
    List<BusinessTypeRequiredField> requiredFields
) {
    /** Résumé d'un {@link jo.accountant.company.entity.ModuleCode} suggéré par le type métier. */
    public record ModuleCodeSummary(String code, String label) {}

    public static BusinessTypeResponse from(BusinessType bt,
                                            List<ModuleCodeSummary> suggestedModules,
                                            List<BusinessTypeRequiredField> requiredFields) {
        return new BusinessTypeResponse(
            bt.getCode(), bt.getLabel(), bt.getDefaultOrganizationNature(),
            bt.getDefaultSector(), bt.getDescription(), bt.isActive(),
            suggestedModules, requiredFields);
    }
}

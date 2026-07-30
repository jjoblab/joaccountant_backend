package jo.accountant.company.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jo.accountant.company.dto.BusinessTypeResponse;
import jo.accountant.company.entity.BusinessType;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.mapping.BusinessTypeModuleService;
import jo.accountant.company.repository.BusinessTypeRequiredFieldRepository;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogue public des types métier (étapes 4 et 7 du wizard — restructuration :company).
 *
 * <p>Endpoints <strong>non scopés par companyId</strong> car il s'agit d'un catalogue de
 * référence global (toutes les sociétés voient la même liste de types métier actifs).
 *
 * <p>Restructuration 2026-07-24 (Partie A §1.1) : le endpoint {@code GET /api/v1/business-types}
 * accepte désormais un paramètre optionnel {@code sector}. Si présent, seuls les types métier
 * dont {@code defaultSector == sector} sont renvoyés. Le mobile appelle ce endpoint avec le
 * {@code sector} choisi à l'étape 3 du wizard pour peupler l'étape 4.
 */
@RestController
@RequestMapping("/api/v1/business-types")
@Tag(name = "BusinessType", description = "Catalogue des types métier (wizard étapes 4 et 7)")
public class BusinessTypeController {

    private final BusinessTypeModuleService businessTypeModuleService;
    private final BusinessTypeRequiredFieldRepository businessTypeRequiredFieldRepository;

    public BusinessTypeController(BusinessTypeModuleService businessTypeModuleService,
                                  BusinessTypeRequiredFieldRepository businessTypeRequiredFieldRepository) {
        this.businessTypeModuleService = businessTypeModuleService;
        this.businessTypeRequiredFieldRepository = businessTypeRequiredFieldRepository;
    }

    @Operation(summary = "List all active business types",
        description = "Catalogue complet des types métier actifs — utilisé par l'étape 4 du wizard. " +
                      "Chaque entrée inclut la liste des modules suggérés. " +
                      "Filtre optionnel `sector` (Partie A §1.1) : si présent, ne renvoie que les " +
                      "types métier dont `defaultSector == sector`. Le mobile appelle ce endpoint " +
                      "avec le `sector` choisi à l'étape 3 pour peupler l'étape 4.")
    @GetMapping
    public List<BusinessTypeResponse> list(@CurrentUser java.util.UUID userId,
                                            @RequestParam(value = "sector", required = false) String sector) {
        Sector sectorFilter = parseSector(sector);
        return businessTypeModuleService.listActive(sectorFilter).stream()
            .map(this::toResponse)
            .toList();
    }

    @Operation(summary = "Get a single business type by code",
        description = "Retourne un type métier + la liste de ses modules suggérés + la liste " +
                      "de ses champs additionnels obligatoires (formulaire dynamique étape 7).")
    @GetMapping("/{code}")
    public BusinessTypeResponse get(@CurrentUser java.util.UUID userId, @PathVariable String code) {
        BusinessType bt = businessTypeModuleService.getActiveByCode(code);
        return toResponse(bt);
    }

    private BusinessTypeResponse toResponse(BusinessType bt) {
        List<ModuleCode> moduleCodes = businessTypeModuleService.modulesFor(bt.getCode());
        List<BusinessTypeResponse.ModuleCodeSummary> modules = moduleCodes.stream()
            .map(mc -> new BusinessTypeResponse.ModuleCodeSummary(mc.name(), mc.name()))
            .toList();
        var required = businessTypeRequiredFieldRepository
            .findByBusinessTypeCodeOrderByDisplayOrderAsc(bt.getCode());
        return BusinessTypeResponse.from(bt, modules, required);
    }

    /**
     * Parse la valeur du paramètre {@code sector} en {@link Sector}. Lève
     * {@code 422 SECTOR_INVALID} si la valeur ne correspond à aucune valeur de l'enum.
     * {@code null} ou chaîne vide → {@code null} (pas de filtre, comportement inchangé).
     */
    private Sector parseSector(String sector) {
        if (sector == null || sector.isBlank()) {
            return null;
        }
        try {
            return Sector.valueOf(sector.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("SECTOR_INVALID",
                "Valeur de secteur invalide : " + sector
                + ". Valeurs attendues : COMMERCE, SERVICE, SANTE, EDUCATION, AGRICULTURE, "
                + "INDUSTRIE, ADMINISTRATION_PUBLIQUE, ONG_HUMANITAIRE, CABINET_COMPTABLE, AUTRE.");
        }
    }
}

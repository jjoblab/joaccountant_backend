package jo.accountant.app.wizard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import jo.accountant.company.dto.CompanyWizardResult;
import jo.accountant.company.dto.CompleteWizardRequest;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V8.2 — Controller pour le wizard refondu (activation atomique).
 *
 * <p>Endpoint POST /api/v1/companies/{companyId}/wizard/complete/v2 qui utilise
 * le WizardOrchestrationService pour exécuter l'activation atomique.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/wizard")
@Tag(name = "WizardV2",
     description = "V8.2 — Wizard refondu à 4 étapes avec activation atomique")
public class WizardController {

    private final WizardOrchestrationService wizardService;
    private final RoleChecker roleChecker;

    public WizardController(WizardOrchestrationService wizardService, RoleChecker roleChecker) {
        this.wizardService = wizardService;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "V8.2 — Activation atomique du wizard (étape 4)")
    @PostMapping(value = "/complete/v2", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CompanyWizardResult> completeWizardV2(
            @PathVariable UUID companyId,
            @CurrentUser UUID userId,
            @RequestBody(required = false) CompleteWizardRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        CompanyWizardResult result = wizardService.completeWizard(companyId, userId);
        return ResponseEntity.ok(result);
    }
}

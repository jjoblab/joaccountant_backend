package jo.accountant.company.security;

import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.service.CompanyModuleService;
import jo.accountant.core.exception.ForbiddenException;
import org.springframework.stereotype.Component;

/**
 * Garde-fou d'activation de module par société (§7.2 — restructuration :company).
 *
 * <p>Composant unique dans {@code :company} (au même titre que {@code RoleChecker} dans
 * {@code :core}) — doit être appelé en tête de chaque endpoint des 6 modules sectoriels
 * concernés ({@code :inventory}, {@code :time-billing}, {@code :funds-grants}, {@code :tax},
 * {@code :fixed-assets}, {@code :bank-reconciliation}).
 *
 * <p>Lève 403 {@code MODULE_NOT_ENABLED} si {@link CompanyModuleService#isEnabled} renvoie
 * {@code false} pour la société et le module concernés. Le message indique explicitement
 * que le module peut être activé via {@code POST /companies/{id}/wizard/complete} ou
 * l'étape 8 du wizard (sélection manuelle pour le type métier {@code CUSTOM}).
 *
 * <p>Les modules <em>always-on</em> (chart-of-accounts, accounting-engine, third-parties,
 * invoicing, document-numbering, approval-workflow, document-generation, notifications,
 * audit-trail, financial-statements, analytics, reporting) ne sont <strong>pas</strong>
 * concernés par ce garde-fou — ils sont toujours activés, le check y serait un no-op
 * permanent (§7.2 du prompt).
 */
@Component
public class ModuleAccessGuard {

    private final CompanyModuleService companyModuleService;

    public ModuleAccessGuard(CompanyModuleService companyModuleService) {
        this.companyModuleService = companyModuleService;
    }

    /**
     * Lève 403 {@code MODULE_NOT_ENABLED} si le module n'est pas activé pour la société.
     *
     * @param companyId identifiant du tenant
     * @param module    code du module à vérifier (ex. {@link ModuleCode#INVENTORY})
     */
    public void ensureEnabled(UUID companyId, ModuleCode module) {
        if (!companyModuleService.isEnabled(companyId, module)) {
            throw new ForbiddenException("MODULE_NOT_ENABLED",
                "Le module " + module + " n'est pas activé pour cette société. "
                + "Il peut être activé via POST /api/v1/companies/" + companyId
                + "/wizard/complete (completion du wizard) ou via l'étape 8 du wizard "
                + "(sélection manuelle pour le type métier CUSTOM).");
        }
    }
}

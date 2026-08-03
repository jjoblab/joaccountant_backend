package jo.accountant.core.port;

import java.util.UUID;

/**
 * Fix Dim 2 H4 (audit v9.4) — Port d'accès aux données actives d'un module pour vérifier
 * qu'un module peut être désactivé sans laisser de données orphelines.
 *
 * <p><b>Problème d'architecture</b> : {@code :company} (qui héberge
 * {@code CompanyModuleService.disable}) ne dépend d'aucun module sectoriel. Pour vérifier
 * s'il existe des données actives (immobilisations non cédées, grants ouverts, stocks non
 * nuls, etc.), il faut accéder aux repositories des modules sectoriels. Une dépendance
 * directe casserait la séparation des couches.
 *
 * <p><b>Solution</b> : pattern hexagonal — port dans {@code :core} (qui ne dépend de rien),
 * implémenté par un adapter dans {@code :app} (qui dépend de tout). {@code :company} injecte
 * ce port et obtient un booléen "le module a-t-il des données actives ?".
 *
 * <p><b>Note</b> : on utilise un {@code String} pour {@code moduleCode} plutôt que
 * {@code jo.accountant.company.entity.ModuleCode} afin de garder {@code :core} indépendant
 * de {@code :company}. L'adapter convertit le String en ModuleCode au runtime.
 *
 * <p><b>Utilisation</b> :
 * <pre>
 * &#64;Autowired
 * private ModuleActiveDataPort moduleActiveDataPort;
 *
 * if (moduleActiveDataPort.hasActiveData(companyId, "FIXED_ASSETS")) {
 *     throw new ConflictException("MODULE_HAS_ACTIVE_DATA", "...");
 * }
 * </pre>
 *
 * @author jo@Dev
 */
public interface ModuleActiveDataPort {

    /**
     * Vérifie si un module a des données actives pour une entreprise donnée.
     *
     * <p>"Données actives" = données qui empêcheraient une désactivation propre du module :
     * <ul>
     *   <li>{@code FIXED_ASSETS} : immobilisations non cédées (status = ACTIVE)</li>
     *   <li>{@code FUNDS_GRANTS} : grants ouverts (status = OPEN)</li>
     *   <li>{@code INVENTORY} : stocks avec quantité non nulle</li>
     *   <li>{@code TIME_BILLING} : timesheets en cours (non facturées)</li>
     * </ul>
     *
     * @param companyId identifiant du tenant
     * @param moduleCode code du module à vérifier (ex. "FIXED_ASSETS", "FUNDS_GRANTS")
     * @return {@code true} si le module a des données actives, {@code false} sinon
     *         (ou si le module n'est pas couvert par la vérification)
     */
    boolean hasActiveData(UUID companyId, String moduleCode);
}


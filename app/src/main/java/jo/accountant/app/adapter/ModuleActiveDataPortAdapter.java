package jo.accountant.app.adapter;

import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.port.ModuleActiveDataPort;
import jo.accountant.fixedassets.entity.AssetStatus;
import jo.accountant.fixedassets.repository.AssetRepository;
import jo.accountant.fundsgrants.repository.GrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fix Dim 2 H4 (audit v9.4) — Implémentation de référence du port {@link ModuleActiveDataPort}.
 *
 * <p>Cette implémentation vit dans {@code :app} (le module final qui dépend de tous les
 * modules sectoriels). Elle vérifie les données actives pour les modules sectoriels les
 * plus sensibles :
 * <ul>
 *   <li>{@code FIXED_ASSETS} : immobilisations non cédées (status = ACTIVE)</li>
 *   <li>{@code FUNDS_GRANTS} : grants non clôturés (endDate &gt; aujourd'hui ou null)</li>
 * </ul>
 *
 * <p>Pour les autres modules sectoriels (INVENTORY, TIME_BILLING, etc.), on retourne
 * {@code false} par défaut — à étendre au besoin. La méthode est défensive : toute
 * exception est catchée et logged, et on retourne {@code false} (la désactivation est
 * autorisée) pour ne pas bloquer l'utilisateur en cas d'erreur technique.
 *
 * <p>Bean Spring détecté automatiquement via {@code @Component}. {@code :company} injecte
 * le port via constructeur — Spring résout l'implémentation au runtime.
 *
 * @author jo@Dev
 */
@Component
public class ModuleActiveDataPortAdapter implements ModuleActiveDataPort {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleActiveDataPortAdapter.class);

    private final AssetRepository assetRepository;
    private final GrantRepository grantRepository;

    public ModuleActiveDataPortAdapter(AssetRepository assetRepository,
                                       GrantRepository grantRepository) {
        this.assetRepository = assetRepository;
        this.grantRepository = grantRepository;
    }

    @Override
    public boolean hasActiveData(UUID companyId, String moduleCode) {
        if (companyId == null || moduleCode == null) {
            return false;
        }
        try {
            switch (moduleCode) {
                case "FIXED_ASSETS":
                    // Immobilisations non cédées (status = ACTIVE)
                    return !assetRepository
                        .findByCompanyIdAndStatus(companyId, AssetStatus.ACTIVE)
                        .isEmpty();
                case "FUNDS_GRANTS":
                    // Grants non clôturés (endDate > aujourd'hui ou null)
                    LocalDate today = LocalDate.now();
                    return grantRepository.findByCompanyId(companyId).stream()
                        .anyMatch(g -> g.getEndDate() == null || !g.getEndDate().isBefore(today));
                default:
                    // Module non couvert par la vérification — autoriser la désactivation
                    return false;
            }
        } catch (Exception e) {
            LOG.warn("Erreur lors de la vérification des données actives pour module={} company={} : {} — autorisation par défaut",
                moduleCode, companyId, e.getMessage());
            return false;
        }
    }
}

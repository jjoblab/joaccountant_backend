package jo.accountant.company.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.company.entity.CompanyModule;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.mapping.BusinessTypeModuleService;
import jo.accountant.company.repository.CompanyModuleRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.ModuleActiveDataPort;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Active / interroge / désactive les modules par société (§11).
 *
 * <p>Utilisé par tout module métier qui doit vérifier si une feature spécifique à un secteur est
 * activée (par ex. :inventory avant de poster un mouvement de stock). Le check se fait TOUJOURS
 * via ce service, jamais en lisant {@code Company.sector} directement (principe 7).
 *
 * <p>(suite — feature toggle) : la méthode {@link #disable}
 * permet à un administrateur de désactiver un module sectoriel que son entreprise n'utilise
 * pas (ex. une boutique de détail sans warehouse peut désactiver {@code INVENTORY}). Les
 * modules always-on (socle commun) ne peuvent PAS être désactivés — ils sont nécessaires
 * au fonctionnement transverse du système (ex. {@code ACCOUNTING_ENGINE} est requis par
 * tous les modules qui génèrent des écritures).
 
 *
 * @author jo@Dev


*/
@Service
public class CompanyModuleService {

    private final CompanyModuleRepository repository;
    private final BusinessTypeModuleService businessTypeModuleService;
    private final ModuleActiveDataPort moduleActiveDataPort;

    public CompanyModuleService(CompanyModuleRepository repository,
                                  BusinessTypeModuleService businessTypeModuleService,
                                  ModuleActiveDataPort moduleActiveDataPort) {
        this.repository = repository;
        this.businessTypeModuleService = businessTypeModuleService;
        this.moduleActiveDataPort = moduleActiveDataPort;
    }

    @Transactional
    public CompanyModule enable(UUID companyId, ModuleCode code) {
        TenantContext.setCompanyId(companyId);
        Optional<CompanyModule> existing = repository.findByCompanyIdAndModuleCode(companyId, code);
        if (existing.isPresent()) {
            CompanyModule cm = existing.get();
            if (!cm.isEnabled()) {
                cm.setEnabled(true);
                cm.setActivatedAt(Instant.now());
                cm = repository.save(cm);
            }
            return cm;
        }
        CompanyModule cm = new CompanyModule();
        cm.setModuleCode(code);
        cm.setEnabled(true);
        cm.setActivatedAt(Instant.now());
        return repository.save(cm);
    }

    /**
     * Désactive un module pour une société.
     *
     * <p>(suite — feature toggle) : permet à un administrateur
     * de désactiver un module sectoriel non utilisé. Refuse la désactivation d'un module
     * always-on (le socle commun est nécessaire au fonctionnement transverse du système).
     *
     * @throws ConflictException si le module est always-on ({@code MODULE_CANNOT_BE_DISABLED})
     * @throws ValidationException si le module n'est pas activé ({@code MODULE_NOT_ENABLED})
     */
    @Transactional
    public CompanyModule disable(UUID companyId, ModuleCode code) {
        TenantContext.setCompanyId(companyId);

        if (businessTypeModuleService.isAlwaysOn(code)) {
            throw new ConflictException("MODULE_CANNOT_BE_DISABLED",
                "Le module " + code + " fait partie du socle always-on et ne peut pas être " +
                "désactivé. Les modules always-on sont nécessaires au fonctionnement transverse " +
                "du système (ex. ACCOUNTING_ENGINE est requis par tous les modules qui génèrent " +
                "des écritures comptables).");
        }

        // Fix Dim 2 H4 (audit v9.4) — Garde-fou contre données orphelines.
        // Avant ce fix, disable() mettait enabled=false sans vérifier s'il existait des
        // données dépendantes. Les données existaient toujours en DB mais devenaient
        // invisibles/inaccessibles via l'API (403 MODULE_NOT_ENABLED). Exemples :
        // - désactiver INVENTORY alors que des stocks ont un solde non nul → bilan faussé
        // - désactiver FUNDS_GRANTS alors qu'un Grant est ouvert → clôture bailleur impossible
        // - désactiver FIXED_ASSETS alors qu'un Asset a un plan d'amortissement en cours
        if (moduleActiveDataPort.hasActiveData(companyId, code.name())) {
            throw new ConflictException("MODULE_HAS_ACTIVE_DATA",
                "Le module " + code + " contient des données actives (immobilisations non cédées, " +
                "grants ouverts, stocks non nuls, etc.) et ne peut pas être désactivé. " +
                "Veuillez clôturer ou céder ces données avant désactivation, ou contacter " +
                "l'administrateur pour une procédure d'archive.");
        }

        CompanyModule cm = repository.findByCompanyIdAndModuleCode(companyId, code)
            .orElseThrow(() -> new ValidationException("MODULE_NOT_ENABLED",
                "Le module " + code + " n'est pas activé pour cette société."));
        if (!cm.isEnabled()) {
            throw new ValidationException("MODULE_ALREADY_DISABLED",
                "Le module " + code + " est déjà désactivé.");
        }
        cm.setEnabled(false);
        cm.setActivatedAt(null);
        return repository.save(cm);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID companyId, ModuleCode code) {
        return repository.findByCompanyIdAndModuleCode(companyId, code)
            .map(CompanyModule::isEnabled)
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<CompanyModule> listForCompany(UUID companyId) {
        return repository.findByCompanyId(companyId);
    }
}

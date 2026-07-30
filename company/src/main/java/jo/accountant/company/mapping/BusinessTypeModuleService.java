package jo.accountant.company.mapping;

import java.util.List;
import jo.accountant.company.entity.BusinessType;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.repository.BusinessTypeModuleRepository;
import jo.accountant.company.repository.BusinessTypeRepository;
import jo.accountant.core.exception.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Service de résolution « type métier → modules activés » (§6 — remplace l'ancien
 * {@code SectorModuleMapping.modulesFor(Sector)} par un mécanisme piloté par données).
 *
 * <p>Le mapping est désormais lu en base (table {@code business_type_module}) plutôt que codé
 * dans un {@code switch} Java — ajouter un nouveau type d'organisation ne nécessite
 * <strong>qu'une insertion de données de référence</strong>, pas une modification de code +
 * redéploiement (objectif explicite §2 du prompt de restructuration).
 *
 * <p>Les modules <em>always-on</em> (socle commun + infrastructure transverse) restent codés
 * en dur ici : ils sont stables par construction (le socle ne change pas selon le type métier)
 * et ne justifieraient pas un détour par la base.
 */
@Component
public class BusinessTypeModuleService {

    private final BusinessTypeRepository businessTypeRepository;
    private final BusinessTypeModuleRepository businessTypeModuleRepository;

    public BusinessTypeModuleService(BusinessTypeRepository businessTypeRepository,
                                      BusinessTypeModuleRepository businessTypeModuleRepository) {
        this.businessTypeRepository = businessTypeRepository;
        this.businessTypeModuleRepository = businessTypeModuleRepository;
    }

    /**
     * Modules toujours activés quel que soit le type métier (socle commun + infrastructure
     * transverse). Identique à {@code SectorModuleMapping.alwaysOnModules()} précédent —
     * inchangé par la restructuration.
     *
     * <p>Restructuration 2026-07-24 (suite — 4 nouveaux modules bonus) : {@code EMPLOYEES},
     * {@code EXPENSES} et {@code PAYROLL} sont ajoutés au socle always-on. Ce sont des modules
     * transverses (toute entreprise a des employés, des dépenses et paie des salaires), au
     * même titre qu'{@code INVOICING} (toute entreprise facture). {@code PURCHASING} reste
     * sectoriel car tous les types métier ne réalisent pas d'achats externalisés (ex. un
     * cabinet de conseil pur peut ne pas activer le module achats).
     */
    public List<ModuleCode> alwaysOnModules() {
        return List.of(
            ModuleCode.CHART_OF_ACCOUNTS,
            ModuleCode.ACCOUNTING_ENGINE,
            ModuleCode.THIRD_PARTIES,
            ModuleCode.INVOICING,
            ModuleCode.DOCUMENT_NUMBERING,
            ModuleCode.APPROVAL_WORKFLOW,
            ModuleCode.DOCUMENT_GENERATION,
            ModuleCode.NOTIFICATIONS,
            ModuleCode.AUDIT_TRAIL,
            ModuleCode.FINANCIAL_STATEMENTS,
            ModuleCode.ANALYTICS,
            ModuleCode.REPORTING,
            // Restructuration 2026-07-24 (suite) — modules transverses toujours actifs
            ModuleCode.EMPLOYEES,
            ModuleCode.EXPENSES,
            ModuleCode.PAYROLL
        );
    }

    /**
     * Renvoie la liste des modules sectoriels activés automatiquement pour un type métier
     * donné. Pour le type {@code CUSTOM} (qui remplace l'ancien secteur {@code MIXTE}), cette
     * liste est vide par construction — c'est l'utilisateur qui sélectionne manuellement les
     * modules à l'étape 8 du wizard (correction du bug documenté « MIXTE non testé »).
     */
    public List<ModuleCode> modulesFor(String businessTypeCode) {
        if (businessTypeCode == null) {
            return List.of();
        }
        return businessTypeModuleRepository.findByBusinessTypeCode(businessTypeCode).stream()
            .map(jo.accountant.company.entity.BusinessTypeModule::getModuleCode)
            .toList();
    }

    /**
     * Charge l'entité {@link BusinessType} par code (active uniquement). Lève
     * {@code NotFoundException} si le code n'existe pas ou est désactivé.
     */
    public BusinessType getActiveByCode(String businessTypeCode) {
        return businessTypeRepository.findByCodeAndActiveTrue(businessTypeCode)
            .orElseThrow(() -> new NotFoundException("BUSINESS_TYPE_NOT_FOUND",
                "Type métier introuvable ou inactif : " + businessTypeCode));
    }

    /** Liste tous les types métier actifs (pour l'étape 4 du wizard). */
    public List<BusinessType> listActive() {
        return businessTypeRepository.findByActiveTrueOrderByCodeAsc();
    }

    /**
     * Liste les types métier actifs dont le secteur par défaut correspond au secteur demandé.
     * Utilisé par le filtre {@code GET /api/v1/business-types?sector=...} (Partie A §1.1).
     * Si {@code sector == null}, retourne le catalogue complet (comportement inchangé).
     */
    public List<BusinessType> listActive(jo.accountant.company.entity.Sector sector) {
        if (sector == null) {
            return listActive();
        }
        return businessTypeRepository.findByActiveTrueAndDefaultSectorOrderByCodeAsc(sector);
    }

    /**
     * Indique si un module fait partie du socle always-on (non désactivable par l'utilisateur).
     *
     * <p>Restructuration 2026-07-24 (suite — feature toggle) : utilisé par
     * {@code CompanyModuleService.deactivate} pour refuser la désactivation d'un module
     * always-on. La désactivation d'un module always-on casserait des dépendances
     * transverses (ex. désactiver {@code ACCOUNTING_ENGINE} empêcherait toute écriture
     * comptable, y compris celles des autres modules).
     */
    public boolean isAlwaysOn(ModuleCode code) {
        return alwaysOnModules().contains(code);
    }
}

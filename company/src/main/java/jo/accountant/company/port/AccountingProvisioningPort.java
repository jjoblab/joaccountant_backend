package jo.accountant.company.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.company.entity.Company;

/**
 * Port d'orchestration de l'activation comptable atomique (V8.2 — audit Z.ai 2026-07-31).
 *
 * <p>Interface définie dans {@code :company} et implémentée dans {@code :app} (ou un module
 * qui peut dépendre de {@code :chart-of-accounts}, {@code :accounting-engine},
 * {@code :document-numbering}, {@code :tax}). Cette inversion de dépendance évite les
 * dépendances circulaires : de nombreux modules (:tax, :invoicing, :purchasing, etc.)
 * dépendent déjà de {@code :company}, donc {@code :company} ne peut pas dépendre d'eux.
 *
 * <p>Appelé par {@link jo.accountant.company.service.CompanyService#completeWizard} pour
 * initialiser en une seule transaction :
 * <ol>
 *   <li>Plan comptable (classes SYSCOHADA/PCG + seed sectoriel BusinessType)</li>
 *   <li>Exercice fiscal + 12 périodes mensuelles</li>
 *   <li>Journaux standards VT/AC/BQ/CA/OD/PA/DP/FX</li>
 *   <li>Séquences de numérotation par défaut</li>
 *   <li>Règles TVA par défaut si pays non couvert par seeds globaux</li>
 * </ol>
 *
 * <p><b>Idempotence</b> : l'implémentation doit être ré-entrante. Si {@code provision} est
 * rappelée (suite à un retry), les objets existants ne doivent pas être recréés.
 *
 * <p><b>Atomicité</b> : l'implémentation doit être {@code @Transactional}. Si une sous-étape
 * échoue avec une exception non-récupérable, toute la transaction doit être rollbackée.
 */
public interface AccountingProvisioningPort {

    /**
     * Orchestre l'initialisation comptable complète d'une société.
     *
     * @param company           la société à provisionner (doit avoir businessTypeCode,
     *                          accountingFrameworkId, fiscalYearStartMonth positionnés)
     * @param vatMode           mode TVA ("DEBIT" ou "ENCAISSEMENT") — stocké dans extraAttributes
     * @param fiscalYearStartYear année de début d'exercice (0 = année courante)
     * @param fiscalYearLabel   libellé de l'exercice (null = défaut calculé)
     * @param numberingPrefixes map des préfixes de numérotation par DocumentType (null = defaults)
     * @return un récapitulatif des objets créés (ou existants si idempotent)
     */
    ProvisioningResult provision(Company company,
                                  String vatMode,
                                  int fiscalYearStartYear,
                                  String fiscalYearLabel,
                                  Map<String, String> numberingPrefixes);

    /**
     * Récapitulatif des objets créés (ou existants) par {@link #provision}.
     *
     * @param chartOfAccountsCreated nombre de comptes du plan comptable créés (0 si déjà initialisé)
     * @param fiscalYearId           id de l'exercice fiscal créé
     * @param journalCodesCreated    liste des codes journaux créés (VT, AC, BQ, CA, OD, PA, DP, FX)
     * @param sequencesCreated       nombre de séquences de numérotation créées
     * @param taxRulesCreated        nombre de règles TVA créées (0 si seeds globaux suffisent)
     */
    record ProvisioningResult(
        int chartOfAccountsCreated,
        UUID fiscalYearId,
        List<String> journalCodesCreated,
        int sequencesCreated,
        int taxRulesCreated
    ) {}
}

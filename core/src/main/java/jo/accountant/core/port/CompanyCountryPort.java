package jo.accountant.core.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Port d'accès au pays d'une entreprise ({@code Company.country}) pour les modules qui ne
 * peuvent pas dépendre de {@code :company} directement.
 *
 * <p><b>Problème d'architecture</b> : {@code :document-generation} ne dépend que de
 * {@code :core} (principe 5 — infrastructure transverse). Pour sélectionner le bon template
 * PDF selon le pays de l'entreprise (mentions légales Haïti vs France), il faut accéder à
 * {@code Company.country}. Une dépendance directe {@code :document-generation → :company}
 * casserait la séparation des couches.
 *
 * <p><b>Solution</b> : pattern hexagonal — port dans {@code :core} (qui ne dépend de rien),
 * implémenté par un adapter dans {@code :app} (qui dépend de tout). {@code :document-generation}
 * injecte ce port et obtient le code pays ISO 3166-1 alpha-2.
 *
 * <p><b>Utilisation</b> :
 * <pre>
 * &#64;Autowired
 * private CompanyCountryPort companyCountryPort;
 *
 * String countryCode = companyCountryPort.resolveCountryCode(companyId).orElse(null);
 * </pre>
 *
 * <p><b>Fix Dim 3 C1 (audit v9.4)</b> : ce port a été ajouté pour permettre à
 * {@code DocumentGenerationService} de sélectionner les templates PDF Haïti
 * (country_code='HT') au lieu de toujours tomber sur les templates France par défaut.
 *
 * @author jo@Dev
 */
public interface CompanyCountryPort {

    /**
     * Résout le code pays ISO 3166-1 alpha-2 d'une entreprise.
     *
     * @param companyId identifiant du tenant
     * @return le code pays (ex. "HT", "FR") ou empty si l'entreprise n'existe pas
     */
    Optional<String> resolveCountryCode(UUID companyId);
}

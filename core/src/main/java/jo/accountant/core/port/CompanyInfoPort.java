package jo.accountant.core.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Fix PDF v9.4 — Port d'accès aux informations d'identité d'une entreprise pour la génération PDF.
 *
 * <p>Permet à {@code :document-generation} de récupérer le nom, l'adresse, le NIF et le logo
 * d'une entreprise sans dépendre de {@code :company} compile-time (principe 5 — infrastructure
 * transverse).
 *
 * <p>Avant ce port, tous les rapports Reports Hub passait {@code companyName = ""} (vide) dans
 * les variables Thymeleaf — bug identifié par l'audit PDF. De même, le logo entreprise était
 * promis en Javadoc mais jamais implémenté.
 *
 * <p>Implémenté par {@code CompanyInfoPortAdapter} dans {@code :company} (qui dépend de {@code :core}).
 *
 * @author jo@Dev
 */
public interface CompanyInfoPort {

    /**
     * Résout les informations d'identité d'une entreprise pour la génération PDF.
     *
     * @param companyId identifiant du tenant
     * @return un {@link CompanyInfo} contenant name/address/nif/country, ou empty si l'entreprise n'existe pas
     */
    Optional<CompanyInfo> resolveCompanyInfo(UUID companyId);

    /**
     * DTO immuable portant les infos d'identité d'une entreprise pour la génération PDF.
     *
     * @param name nom de l'entreprise (ex: "Boulangerie du Marché SARL")
     * @param address adresse postale (ex: "123 Rue Capois, Port-au-Prince, Haïti")
     * @param nif numéro d'identification fiscale (ex: "HT123456789")
     * @param siret SIRET (France) ou null si non applicable
     * @param vatNumber numéro de TVA intracommunautaire (France) ou null
     * @param countryCode code pays ISO 3166-1 alpha-2 (ex: "HT", "FR")
     * @param logoBase64 logo encodé en base64 (PNG/JPEG, sans préfixe data:), ou null si pas de logo
     */
    record CompanyInfo(
        String name,
        String address,
        String nif,
        String siret,
        String vatNumber,
        String countryCode,
        String logoBase64
    ) {}
}

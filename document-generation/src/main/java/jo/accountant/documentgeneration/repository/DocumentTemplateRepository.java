package jo.accountant.documentgeneration.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.DocumentTemplate;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA DocumentTemplate.
 *
 * @author jo@Dev


 */

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

    /** Gabarit par défaut actif pour un (companyId, documentType). */
    Optional<DocumentTemplate> findByCompanyIdAndDocumentTypeAndIsDefaultTrueAndActiveTrue(
        UUID companyId, GeneratedDocumentType documentType);

    /** Gabarit global par défaut (companyId=null) pour un documentType. */
    Optional<DocumentTemplate> findByCompanyIdIsNullAndDocumentTypeAndIsDefaultTrueAndActiveTrue(
        GeneratedDocumentType documentType);

    /**
     * Gabarit global par défaut (companyId=null) pour un (documentType, countryCode).
     * Fix Dim 3 C1 (audit v9.4) — permet de sélectionner les templates Haïti (country_code='HT')
     * au lieu de toujours tomber sur les templates France (country_code IS NULL).
     */
    Optional<DocumentTemplate> findByCompanyIdIsNullAndDocumentTypeAndCountryCodeAndIsDefaultTrueAndActiveTrue(
        GeneratedDocumentType documentType, String countryCode);

    /** Tous les gabarits d'une entreprise. */
    List<DocumentTemplate> findByCompanyIdOrderByDocumentType(UUID companyId);
}

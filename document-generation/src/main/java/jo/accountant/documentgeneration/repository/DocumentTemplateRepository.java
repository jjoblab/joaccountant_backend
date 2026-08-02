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

    /** Tous les gabarits d'une entreprise. */
    List<DocumentTemplate> findByCompanyIdOrderByDocumentType(UUID companyId);
}

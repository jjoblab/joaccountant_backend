package jo.accountant.documentgeneration.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.DocumentTemplate;
import jo.accountant.documentgeneration.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

    /** Gabarit par défaut actif pour un (companyId, documentType). */
    Optional<DocumentTemplate> findByCompanyIdAndDocumentTypeAndIsDefaultTrueAndActiveTrue(
        UUID companyId, DocumentType documentType);

    /** Gabarit global par défaut (companyId=null) pour un documentType. */
    Optional<DocumentTemplate> findByCompanyIdIsNullAndDocumentTypeAndIsDefaultTrueAndActiveTrue(
        DocumentType documentType);

    /** Tous les gabarits d'une entreprise. */
    List<DocumentTemplate> findByCompanyIdOrderByDocumentType(UUID companyId);
}

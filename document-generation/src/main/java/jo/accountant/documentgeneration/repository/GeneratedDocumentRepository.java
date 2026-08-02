package jo.accountant.documentgeneration.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.documentgeneration.entity.GeneratedDocumentType;
import jo.accountant.documentgeneration.entity.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, UUID> {

    /** Document déjà généré pour un (companyId, resourceId) — sert le PDF existant. */
    Optional<GeneratedDocument> findByCompanyIdAndResourceId(UUID companyId, UUID resourceId);
}

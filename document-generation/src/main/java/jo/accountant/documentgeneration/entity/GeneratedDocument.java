package jo.accountant.documentgeneration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Document PDF généré et stocké (§8, §13 Phase 11).
 *
 * <p>Permet de re-servir un PDF déjà émis sans le régénérer — cohérent avec
 * {@code FinancialStatementSnapshot.frozen} (Phase 6) et la règle "une facture ISSUED
 * n'est jamais éditée" (Phase 12).
 *
 * <p>Un PDF lié à un document déjà définitif (ISSUED, POSTED) est <strong>immuable</strong> :
 * si un {@link GeneratedDocument} existe déjà pour ce {@code resourceId}, on le sert tel
 * quel, pas de régénération.
 *
 * <p>Le {@code storageKey} est opaque — référencé via {@link jo.accountant.core.port.FileStoragePort}.
 * L'accès au contenu est toujours médié par un endpoint applicatif qui vérifie
 * l'appartenance tenant.
 */
@Entity
@Table(name = "generated_document")
public class GeneratedDocument extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 25)
    private GeneratedDocumentType documentType;

    /** ID de l'entité cible (ex. ID d'une facture, d'un reçu de don, etc.). */
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    /** Clé opaque vers le fichier PDF stocké via FileStoragePort. */
    @Column(name = "storage_key", nullable = false, length = 200)
    private String storageKey;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by")
    private UUID generatedBy;

    /** Checksum SHA-256 du PDF — pour vérifier l'intégrité et détecter les doublons. */
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    public GeneratedDocumentType getDocumentType() { return documentType; }
    public void setDocumentType(GeneratedDocumentType documentType) { this.documentType = documentType; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public UUID getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(UUID generatedBy) { this.generatedBy = generatedBy; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
}

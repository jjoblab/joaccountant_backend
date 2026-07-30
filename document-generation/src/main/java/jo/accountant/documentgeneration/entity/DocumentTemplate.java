package jo.accountant.documentgeneration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Gabarit de document PDF (§8, §13 Phase 11).
 *
 * <p><strong>NOT</strong> a {@link jo.accountant.core.tenant.TenantAwareEntity} —
 * {@code companyId} est nullable (gabarit global par défaut quand null). Le mécanisme
 * TenantAwareEntity exige un companyId non-null, ce qui ne convient pas ici.
 */
@Entity
@Table(name = "document_template")
public class DocumentTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id")
    private UUID companyId;

    /**
     * Lot B R-08 — code pays ISO 3166-1 alpha-2 pour filtrer les templates par pays.
     * <ul>
     *   <li>{@code null} = template international / France (comportement historique avant R-08).</li>
     *   <li>{@code "HT"} = template spécifique Haïti (mentions Code Fiscal art. 196, NIF émetteur,
     *       pénalités 1.5%/mois, indemnité 5 000 HTG).</li>
     *   <li>{@code "FR"} = template spécifique France (CGI art. 289, C. com. L441-10, 40 EUR).</li>
     * </ul>
     * Les seeds V56 positionnent country_code='HT' sur les templates Haïti ; les templates
     * existants (V35/V39) restent NULL = France/historique.
     */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 25)
    private DocumentType documentType;

    /** Template HTML Thymeleaf — sera rendu avec les variables fournies à generateDocument. */
    @Column(name = "html_template", nullable = false, columnDefinition = "TEXT")
    private String htmlTemplate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }

    public String getHtmlTemplate() { return htmlTemplate; }
    public void setHtmlTemplate(String htmlTemplate) { this.htmlTemplate = htmlTemplate; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

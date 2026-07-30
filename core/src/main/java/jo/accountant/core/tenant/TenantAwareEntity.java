package jo.accountant.core.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Classe de base de toute entité métier (§3.3).
 *
 * <p>{@code companyId} est injecté depuis {@link TenantContext} par {@link
 * TenantAwareEntityListener} — il n'est JAMAIS accepté dans le corps d'une requête entrante
 * (validé par les tests d'intégration).
 *
 * <p>Utilise des UUID v7 (ordonnés dans le temps) pour une meilleure localité d'index que les v4.
 * Le générateur délègue à la fonction PL/pgSQL {@code uuidv7()} installée par la migration V0_000.
 *
 * <p><b>Audit v4.7 §5.1 Finding #1 — défense en profondeur multi-tenant</b> :
 * {@link TenantDefenseInDepthListener} est ajouté aux listeners pour valider au moment du flush
 * que le {@code companyId} de l'entité correspond au {@link TenantContext#getCompanyId()} courant.
 * Empêche les INSERT/UPDATE cross-tenant même si le service appelant oublie le guard.
 * Combine avec ArchUnit Rule 42 (défense SELECT) et les guards applicatifs {@code companyId.equals()}.
 */
@MappedSuperclass
@EntityListeners({AuditingEntityListener.class, TenantAwareEntityListener.class, TenantDefenseInDepthListener.class})
public abstract class TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    // R-36 (lot-F3-security) — passé de `private` à `protected` pour permettre aux entités
    // filles (JournalEntry, etc.) d'implémenter des méthodes métier riches (post(), voidEntry())
    // qui référencent this.id dans les messages d'erreur. Les getters/setters publics restent
    // la voie d'accès pour les services externes. Pattern standard JPA @MappedSuperclass.
    protected UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    // R-36 (lot-F3-security) — passé de `private` à `protected` (même raison que id ci-dessus).
    protected UUID companyId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

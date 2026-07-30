package jo.accountant.auth.entity;

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
 * Rôle par société pour un utilisateur (§3.4). Un utilisateur peut avoir un rôle différent dans
 * chaque société.
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} — c'est une entité JOIN
 * qui doit être lisible des DEUX côtés : « lister mes sociétés » (filtre par user_id, sans tenir
 * compte du contexte company) ET « lister les utilisateurs d'une société » (filtre par
 * company_id, dans le contexte tenant). Le filtre Hibernate {@code @TenantId} casserait le
 * premier cas d'usage.
 *
 * <p>L'isolation est enforced au niveau service : un utilisateur ne peut voir une ligne
 * {@code UserCompanyRole} que s'il est lui-même l'utilisateur concerné OU s'il détient un rôle
 * dans la société de la ligne.
 *
 * <p>{@code acceptedAt} est null jusqu'à ce que l'utilisateur invité accepte effectivement
 * l'invitation ; avant cela, la ligne existe mais l'utilisateur n'a aucun accès.
 */
@Entity
@Table(name = "user_company_role",
    uniqueConstraints = @UniqueConstraint(name = "uc_user_company", columnNames = {"user_id", "company_id"}))
public class UserCompanyRole {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant invitedAt) { this.invitedAt = invitedAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
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

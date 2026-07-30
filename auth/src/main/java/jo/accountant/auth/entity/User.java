package jo.accountant.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Utilisateur applicatif (§13 Phase 1).
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} : un utilisateur est
 * transverse — il peut appartenir à 1..N sociétés (N ≤
 * {@code app.subscription.max-companies-per-user}, défaut 3).
 *
 * <p>{@code maxCompaniesOverride} est nullable pour qu'un futur tier payant puisse lever la
 * limite par utilisateur sans redéployer le guard.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "locale", nullable = false)
    private String locale;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "max_companies_override")
    private Integer maxCompaniesOverride;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public User() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Integer getMaxCompaniesOverride() { return maxCompaniesOverride; }
    public void setMaxCompaniesOverride(Integer maxCompaniesOverride) { this.maxCompaniesOverride = maxCompaniesOverride; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

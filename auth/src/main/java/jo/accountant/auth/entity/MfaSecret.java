package jo.accountant.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Secret MFA TOTP (RFC 6238) d'un utilisateur —Finding MOYENNE (suite).
 *
 * <p>Stockage du secret partagé TOTP (Base32) chiffré en base. L'utilisateur active la MFA
 * en scannant un QR code (Google Authenticator, Authy, FreeOTP). À chaque login, un code TOTP
 * à 6 chiffres est demandé en plus du mot de passe.
 *
 * <p><b>Obligation</b> : la MFA est obligatoire pour les rôles {@code OWNER} et {@code ADMIN}
 *. Pour les autres rôles, elle est optionnelle.
 *
 * <p><b>Sécurité</b> :
 * <ul>
 * <li>Le secret est stocké chiffré (AES-256-GCM) — la clé de chiffrement est dans
 * {@code app.mfa.encryption-key} (à externaliser dans Vault/KMS en prod).</li>
 * <li>Les codes de récupération ({@code recoveryCodes}) permettent l'accès en cas de perte
 * du téléphone — 10 codes à usage unique, hashés SHA-256.</li>
 * <li>{@code enabledAt} null = MFA configurée mais pas encore activée (l'utilisateur doit
 * valider un premier code TOTP pour confirmer le setup).</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "mfa_secret")
public class MfaSecret {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /** Secret TOTP Base32 (ex: "JBSWY3DPEHPK3PXP") — stocké chiffré AES-256-GCM. */
    @Column(name = "secret_encrypted", nullable = false, length = 500)
    private String secretEncrypted;

    /** Issuer pour le QR code (ex: "JOAccountant"). */
    @Column(name = "issuer", nullable = false, length = 50)
    private String issuer = "JOAccountant";

    /** Période TOTP en secondes (30 par défaut, RFC 6238). */
    @Column(name = "period", nullable = false)
    private int period = 30;

    /** Nombre de digits du code TOTP (6 par défaut, RFC 6238). */
    @Column(name = "digits", nullable = false)
    private int digits = 6;

    /** Algorithme HMAC (HmacSHA1 par défaut, RFC 6238 — compatible Google Authenticator). */
    @Column(name = "algorithm", nullable = false, length = 20)
    private String algorithm = "HmacSHA1";

    /** Date d'activation — null tant que l'utilisateur n'a pas validé le setup avec un code. */
    @Column(name = "enabled_at")
    private Instant enabledAt;

    /**
     * Codes de récupération (10 codes à usage unique) — hashés SHA-256, stockés en JSONB.
     * Format: {@code [{"hash": "...", "usedAt": null}, ...]}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recovery_codes", columnDefinition = "jsonb")
    private String recoveryCodes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // --- Getters/Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getSecretEncrypted() { return secretEncrypted; }
    public void setSecretEncrypted(String secretEncrypted) { this.secretEncrypted = secretEncrypted; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public int getPeriod() { return period; }
    public void setPeriod(int period) { this.period = period; }
    public int getDigits() { return digits; }
    public void setDigits(int digits) { this.digits = digits; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public Instant getEnabledAt() { return enabledAt; }
    public void setEnabledAt(Instant enabledAt) { this.enabledAt = enabledAt; }
    public String getRecoveryCodes() { return recoveryCodes; }
    public void setRecoveryCodes(String recoveryCodes) { this.recoveryCodes = recoveryCodes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    /** Indique si la MFA est activée (l'utilisateur a validé le setup avec un premier code). */
    public boolean isEnabled() { return enabledAt != null; }
}

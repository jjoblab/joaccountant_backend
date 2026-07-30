package jo.accountant.bankreconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Import de relevé bancaire (§13 Phase 13).
 *
 * <p>Le fichier brut est conservé via {@link jo.accountant.core.port.FileStoragePort}
 * (storageKey opaque) pour audit — jamais de blob binaire en colonne PostgreSQL.
 */
@Entity
@Table(name = "bank_statement_import")
public class BankStatementImport extends TenantAwareEntity {

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 5)
    private BankStatementFormat format;

    /** Clé opaque vers le fichier brut stocké via FileStoragePort. */
    @Column(name = "storage_key", nullable = false, length = 200)
    private String storageKey;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    /** Nombre de lignes parsées. */
    @Column(name = "line_count", nullable = false)
    private int lineCount;

    public UUID getBankAccountId() { return bankAccountId; }
    public void setBankAccountId(UUID bankAccountId) { this.bankAccountId = bankAccountId; }

    public BankStatementFormat getFormat() { return format; }
    public void setFormat(BankStatementFormat format) { this.format = format; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }

    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }
}

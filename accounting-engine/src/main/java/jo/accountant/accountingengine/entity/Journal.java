package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Journal comptable (§13 Phase 5).
 *
 * <p>Exemples : VT (ventes), AC (achats), BQ (banque), OD (opérations diverses).
 *
 * <p>Ne porte plus de {@code sequenceFormat} depuis la v2.1 — la configuration du format de
 * numérotation vit désormais uniquement dans {@code :document-numbering}
 * ({@code DocumentSequenceConfig.scopeKey} = code journal).
 */
@Entity
@Table(name = "journal",
    uniqueConstraints = @UniqueConstraint(name = "uc_journal_company_code",
        columnNames = {"company_id", "code"}))
public class Journal extends TenantAwareEntity {

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}

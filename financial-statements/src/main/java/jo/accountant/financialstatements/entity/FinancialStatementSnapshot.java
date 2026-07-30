package jo.accountant.financialstatements.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Snapshot figé d'un état financier à la clôture (§13 Phase 6).
 *
 * <p>Le snapshot fige l'état au moment de la clôture pour qu'une modification ultérieure du
 * plan comptable ou des tiers ne réécrive jamais l'historique déjà produit — cohérent avec
 * la règle "une facture ISSUED n'est jamais éditée" (Phase 12) et l'immuabilité des écritures
 * POSTED (Phase 5).
 *
 * <p>Le {@code contentJson} contient l'état complet au format JSON (bilan ou compte de
 * résultat, avec tous les montants agrégés par {@code reportingClass} et
 * {@code reportingSubcategory}). Une fois figé, ce contenu ne change plus — d'où l'absence
 * de setter sur {@link #contentJson} après création.
 *
 * <p>Un snapshot par (companyId, type, periodId) — contrainte unique. Pour régénérer un
 * snapshot d'une période déjà figée, il faut explicitement supprimer l'ancien (opération
 * d'audit, à exposer via un endpoint dédié si besoin — non fait en Phase 6).
 */
@Entity
@Table(name = "financial_statement_snapshot",
    uniqueConstraints = @UniqueConstraint(name = "uc_fss_company_type_period",
        columnNames = {"company_id", "type", "period_id"}))
public class FinancialStatementSnapshot extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private FinancialStatementType type;

    /** Période fiscale pour laquelle le snapshot est figé. */
    @Column(name = "period_id", nullable = false)
    private UUID periodId;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /**
     * Indique si le snapshot est définitivement figé. {@code true} par défaut à la création —
     * un snapshot est immuable dès sa création. Le champ est posé pour une future extension
     * (par exemple : snapshots provisoires régénérables tant que l'exercice n'est pas CLOSED).
     */
    @Column(name = "frozen", nullable = false)
    private boolean frozen = true;

    /**
     * Contenu complet du snapshot au format JSON. Une fois figé, ne change plus.
     * Structuré comme : {@code {"assets": {...}, "liabilities": {...}, "equity": {...}}}
     * pour un bilan, ou {@code {"products": {...}, "charges": {...}, "netResult": ...}}
     * pour un compte de résultat.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    private String contentJson;

    /** Date « as of » pour un bilan (date à laquelle le bilan est calculé). */
    @Column(name = "as_of_date")
    private java.time.LocalDate asOfDate;

    /** Plage de dates pour un compte de résultat. */
    @Column(name = "from_date")
    private java.time.LocalDate fromDate;

    @Column(name = "to_date")
    private java.time.LocalDate toDate;

    // --- Getters / setters ---

    public FinancialStatementType getType() { return type; }
    public void setType(FinancialStatementType type) { this.type = type; }

    public UUID getPeriodId() { return periodId; }
    public void setPeriodId(UUID periodId) { this.periodId = periodId; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }

    public java.time.LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(java.time.LocalDate asOfDate) { this.asOfDate = asOfDate; }

    public java.time.LocalDate getFromDate() { return fromDate; }
    public void setFromDate(java.time.LocalDate fromDate) { this.fromDate = fromDate; }

    public java.time.LocalDate getToDate() { return toDate; }
    public void setToDate(java.time.LocalDate toDate) { this.toDate = toDate; }
}

package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tag analytique rattaché à une {@link JournalLine ligne d'écriture} (§5, §13.
 *
 * <p>Permet de ventiler une même ligne entre plusieurs valeurs analytiques (par exemple
 * 70% sur le fonds "Subvention CRS 2026" et 30% sur le fonds "Dons généraux").
 *
 * <p>Règle : la somme des {@link #allocationPercentage} par (plan, ligne) doit être 100%.
 * Vérifié en application au postage de l'écriture.
 *
 * <p><strong>NOT</strong> a {@link jo.accountant.core.tenant.TenantAwareEntity} — cette entité
 * est toujours manipulée dans le contexte d'une écriture qui est elle-même tenant-aware.
 * Le {@code companyId} est dénormalisé ici uniquement pour permettre des requêtes
 * d'agrégation par fonds/projet sans jointure avec journal_line + journal_entry.
 */
@Entity
@Table(name = "journal_line_analytical_tag",
    uniqueConstraints = @UniqueConstraint(name = "uc_jlat_line_plan_value",
        columnNames = {"journal_line_id", "plan_id", "value_id"}))
/**
 * JournalLineAnalyticalTag.
 *
 * @author jo@Dev


 */

public class JournalLineAnalyticalTag {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "journal_line_id", nullable = false)
    private UUID journalLineId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "value_id", nullable = false)
    private UUID valueId;

    /** Pourcentage de répartition (0 à 100). Somme = 100% par (plan, ligne). */
    @Column(name = "allocation_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal allocationPercentage;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getJournalLineId() { return journalLineId; }
    public void setJournalLineId(UUID journalLineId) { this.journalLineId = journalLineId; }

    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }

    public UUID getValueId() { return valueId; }
    public void setValueId(UUID valueId) { this.valueId = valueId; }

    public BigDecimal getAllocationPercentage() { return allocationPercentage; }
    public void setAllocationPercentage(BigDecimal allocationPercentage) {
        this.allocationPercentage = allocationPercentage;
    }
}

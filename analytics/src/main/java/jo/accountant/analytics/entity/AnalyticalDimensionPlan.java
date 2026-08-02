package jo.accountant.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Plan de dimension analytique pour une entreprise (§5).
 *
 * <p>Un axe d'analyse par entreprise — par exemple "Fonds/Projets" pour une ONG, "Projets
 * clients" pour une société de service, "Points de vente" pour le commerce.
 *
 * <p><strong>Recommandation</strong> : 2 à 4 plans actifs maximum par entreprise. Au-delà,
 * la saisie devient pénible et l'analyse illisible — constat récurrent des logiciels
 * comparables. Un avertissement (pas un blocage dur) est renvoyé au-delà de 4 plans actifs.
 *
 * <p>Entité {@link TenantAwareEntity} : le {@code companyId} est injecté depuis
 * {@link jo.accountant.core.tenant.TenantContext}, jamais accepté dans le corps d'une requête.
 */
@Entity
@Table(name = "analytical_dimension_plan",
    uniqueConstraints = @UniqueConstraint(name = "uc_adp_company_code",
        columnNames = {"company_id", "code"}))
/**
 * AnalyticalDimensionPlan.
 *
 * @author jo@Dev


 */

public class AnalyticalDimensionPlan extends TenantAwareEntity {

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

package jo.accountant.timebilling.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Taux horaire facturable (§13.
 *
 * <p>Le taux peut être défini à plusieurs niveaux de granularité :
 * <ul>
 * <li>Niveau entreprise : {@code projectId = null} ET {@code resourceUserId = null} —
 * taux par défaut pour tous les projets et toutes les ressources</li>
 * <li>Niveau projet : {@code projectId != null} ET {@code resourceUserId = null} —
 * taux spécifique à un projet</li>
 * <li>Niveau ressource : {@code projectId = null} ET {@code resourceUserId != null} —
 * taux spécifique à un utilisateur</li>
 * <li>Niveau projet + ressource : {@code projectId != null} ET {@code resourceUserId != null} —
 * taux le plus spécifique</li>
 * </ul>
 *
 * <p>La résolution du taux se fait du plus spécifique au moins spécifique.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "tb_billable_rate")
public class BillableRate extends TenantAwareEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "resource_user_id")
    private UUID resourceUserId;

    @Column(name = "hourly_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal hourlyRate;

    /** Code ISO 4217 de la devise du taux. En, devrait être la devise fonctionnelle. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public UUID getResourceUserId() { return resourceUserId; }
    public void setResourceUserId(UUID resourceUserId) { this.resourceUserId = resourceUserId; }

    public BigDecimal getHourlyRate() { return hourlyRate; }
    public void setHourlyRate(BigDecimal hourlyRate) { this.hourlyRate = hourlyRate; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}

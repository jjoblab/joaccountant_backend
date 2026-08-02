package jo.accountant.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Règle d'alerte configurable par entreprise (§9).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "ntf_alert_rule")
public class AlertRule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private AlertType type;

    @Column(name = "threshold_value", precision = 19, scale = 4)
    private BigDecimal thresholdValue;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }
    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }
    public BigDecimal getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(BigDecimal thresholdValue) { this.thresholdValue = thresholdValue; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

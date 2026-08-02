package jo.accountant.timebilling.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Entrée de feuille de temps (§13.
 *
 * <p>Une entrée représente du temps passé par une ressource sur un projet à une date donnée.
 *
 * <p>Champs clés :
 * <ul>
 * <li>{@link #billable} — si {@code false}, le temps est saisi pour information mais ne
 * sera jamais facturé (ex. formation interne, pause)</li>
 * <li>{@link #approved} — si {@code false}, l'entrée est en attente d'approbation par
 * un responsable. Seules les entrées {@code approved = true} ET {@code billable = true}
 * sont facturables (règle §13.</li>
 * <li>{@link #invoiced} — si {@code true}, l'entrée a déjà été facturée et ne peut pas
 * être réutilisée sur une autre facture (idempotence métier, §13.</li>
 * </ul>
 *
 * <p>Le WIP (travail en cours) = somme des heures approuvées, billables, non facturées,
 * multipliée par le taux applicable. Pas d'écriture comptable tant que non facturé — sauf
 * si l'entreprise active la reconnaissance de revenu à l'avancement (option,
 * désactivée par défaut, non implémentée).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "tb_timesheet_entry")
public class TimesheetEntry extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** Utilisateur (ressource) qui a passé le temps. */
    @Column(name = "resource_user_id", nullable = false)
    private UUID resourceUserId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    /** Heures passées — en décimal (ex. 1.5 = 1h30). */
    @Column(name = "hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal hours;

    @Column(name = "billable", nullable = false)
    private boolean billable = true;

    @Column(name = "approved", nullable = false)
    private boolean approved = false;

    @Column(name = "description", length = 500)
    private String description;

    /** Si {@code true}, l'entrée a été facturée — ne peut pas être réutilisée. */
    @Column(name = "invoiced", nullable = false)
    private boolean invoiced = false;

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public UUID getResourceUserId() { return resourceUserId; }
    public void setResourceUserId(UUID resourceUserId) { this.resourceUserId = resourceUserId; }

    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }

    public BigDecimal getHours() { return hours; }
    public void setHours(BigDecimal hours) { this.hours = hours; }

    public boolean isBillable() { return billable; }
    public void setBillable(boolean billable) { this.billable = billable; }

    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isInvoiced() { return invoiced; }
    public void setInvoiced(boolean invoiced) { this.invoiced = invoiced; }
}

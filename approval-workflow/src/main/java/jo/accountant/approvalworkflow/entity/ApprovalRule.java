package jo.accountant.approvalworkflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import jo.accountant.core.tenant.TenantAwareEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Règle de seuil d'approbation pour un type d'action donné (§7).
 *
 * <p>Une règle par (companyId, actionType) avec {@code active = true}. Si plusieurs règles
 * existent pour le même actionType (par exemple une ancienne désactivée et une nouvelle
 * active), seule la règle active est considérée par
 * {@link jo.accountant.approvalworkflow.service.ApprovalWorkflowService#evaluate}.
 *
 * <p><strong>Comportement par défaut</strong> : l'absence de règle active pour un actionType
 * signifie qu'<em>aucune approbation n'est requise</em> — pas de blocage surprise pour une
 * petite structure. C'est explicitement le choix du prompt maître (§7).
 *
 * <p>Règles métier :
 * <ul>
 * <li>Une seule règle {@code active = true} par (companyId, actionType). Tentative de
 * créer une deuxième règle active pour le même actionType → 409. Pour modifier une
 * règle, désactiver l'ancienne puis créer la nouvelle (avec audit trail).</li>
 * <li>{@code thresholdAmount} est en devise fonctionnelle de l'entreprise — le consommateur
 * doit convertir le montant de l'action en devise fonctionnelle avant d'appeler
 * {@code evaluate}.</li>
 * <li>{@code requiredApproverRoles} : liste des rôles (au sens {@link UserRole}) capables
 * d'approuver. La liste est stockée en JSONB. Tous les utilisateurs ayant un de ces
 * rôles dans l'entreprise sont notifiés à chaque création de
 * {@link ApprovalRequest}.</li>
 * <li>{@code minApprovals} : nombre minimum d'approbations requises. En, forcé à 1
 * (workflow multi-étapes non implémenté — champ prévu pour l'extensionsi
 * besoin). La validation en interdit les valeurs &gt; 1 pour l'instant.</li>
 * </ul>
 *
 * <p>Entité {@link TenantAwareEntity} : le {@code companyId} est injecté depuis
 * {@link jo.accountant.core.tenant.TenantContext}, jamais accepté dans le corps d'une requête.
 */
@Entity
@Table(name = "approval_rule",
    uniqueConstraints = @UniqueConstraint(name = "uc_approval_rule_active",
        columnNames = {"company_id", "action_type", "active"}))
/**
 * ApprovalRule.
 *
 * @author jo@Dev


 */

public class ApprovalRule extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private ApprovalActionType actionType;

    /**
     * Montant seuil en devise fonctionnelle. Toute action dont le montant est
     * <strong>strictement supérieur</strong> à ce seuil déclenche une demande d'approbation.
     * Montant égal au seuil = pas d'approbation (au-dessus du seuil = &gt;).
     */
    @Column(name = "threshold_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal thresholdAmount;

    /**
     * Liste des rôles capables d'approuver une demande de ce type, stockée en JSONB.
     * Exemple : {@code ["ADMIN","OWNER"]}. Au moins un rôle doit être présent.
     *
     * <p>Les rôles sont des chaînes (pas une référence directe à {@code UserRole} de
     * {@code :auth}) pour préserver l'indépendance de {@code :approval-workflow}
     * (principe 5 du prompt maître §10 : ce module ne dépend d'aucun module métier).
     * Les valeurs autorisées sont : {@code OWNER, ADMIN, ACCOUNTANT, BOOKKEEPER, VIEWER,
     * AUDITOR} — la même nomenclature que {@code UserRole}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_approver_roles", nullable = false, columnDefinition = "jsonb")
    private String requiredApproverRoles;

    /**
     * Nombre minimum d'approbations requises. En, forcé à 1.
     * Le champ est posé pour une future extension multi-étapessi besoin).
     */
    @Column(name = "min_approvals", nullable = false)
    private int minApprovals = 1;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    // --- Getters / setters ---

    public ApprovalActionType getActionType() { return actionType; }
    public void setActionType(ApprovalActionType actionType) { this.actionType = actionType; }

    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }

    public String getRequiredApproverRoles() { return requiredApproverRoles; }
    public void setRequiredApproverRoles(String requiredApproverRoles) {
        this.requiredApproverRoles = requiredApproverRoles;
    }

    public int getMinApprovals() { return minApprovals; }
    public void setMinApprovals(int minApprovals) { this.minApprovals = minApprovals; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

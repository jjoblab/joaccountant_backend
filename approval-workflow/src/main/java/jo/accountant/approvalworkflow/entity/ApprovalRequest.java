package jo.accountant.approvalworkflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Demande d'approbation créée par
 * {@link jo.accountant.approvalworkflow.service.ApprovalWorkflowService#evaluate}
 * lorsqu'une action financière dépasse le seuil d'une {@link ApprovalRule} active (§7).
 *
 * <p>Cycle de vie :
 * <ol>
 * <li>{@link ApprovalStatus#PENDING} à la création (par {@code evaluate}).</li>
 * <li>Puis transition vers un état terminal :
 * <ul>
 * <li>{@link ApprovalStatus#APPROVED} — l'action cible peut être finalisée par le
 * consommateur. La décision ne peut pas être prise par le demandeur lui-même
 * (règle des quatre yeux, §7).</li>
 * <li>{@link ApprovalStatus#REJECTED} — l'action cible revient à {@code DRAFT} côté
 * consommateur, avec motif horodaté.</li>
 * <li>{@link ApprovalStatus#CANCELLED} — typiquement par le demandeur lui-même avant
 * décision. L'action cible revient aussi à {@code DRAFT}.</li>
 * </ul>
 * </li>
 * </ol>
 *
 * <p>Une fois dans un état terminal, la demande est immuable. Toute tentative de re-décision
 * → 409.
 *
 * <p>Champs :
 * <ul>
 * <li>{@code actionType} — type d'action (ex. {@link ApprovalActionType#JOURNAL_ENTRY_POST}).
 * À comparer avec {@link ApprovalRule#getActionType()}.</li>
 * <li>{@code resourceType} + {@code resourceId} — identifie l'entité cible côté
 * consommateur (ex. {@code "JournalEntry"} + l'UUID de l'écriture). Le consommateur
 * est responsable de la transition d'état de cette entité en fonction du statut de la
 * demande.</li>
 * <li>{@code amount} — montant de l'action en devise fonctionnelle, tel que passé à
 * {@code evaluate}. Conservé pour audit.</li>
 * <li>{@code requestedBy} — auteur de la demande (l'utilisateur qui a déclenché l'action).</li>
 * <li>{@code decidedBy} — décideur (null tant que {@code status = PENDING}). Doit être
 * différent de {@code requestedBy} pour {@code APPROVED} et {@code REJECTED}
 * (règle des quatre yeux). Peut être égal à {@code requestedBy} pour
 * {@code CANCELLED} (le demandeur annule sa propre demande).</li>
 * <li>{@code comment} — motif de la décision (rejet, annulation). Null ou vide pour
 * approbation simple.</li>
 * </ul>
 *
 * <p>Entité {@link TenantAwareEntity} : le {@code companyId} est injecté depuis
 * {@link jo.accountant.core.tenant.TenantContext}, jamais accepté dans le corps d'une requête.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "approval_request")
public class ApprovalRequest extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private ApprovalActionType actionType;

    /** Type de l'entité cible côté consommateur (ex. "JournalEntry", "SalesInvoice"). */
    @Column(name = "resource_type", nullable = false, length = 60)
    private String resourceType;

    /** Identifiant de l'entité cible. */
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    /** Montant de l'action en devise fonctionnelle, tel que passé à {@code evaluate}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Auteur de la demande (l'utilisateur qui a déclenché l'action). */
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    /** Décideur (null tant que {@code status = PENDING}). */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Motif de la décision. Null ou vide pour approbation simple. */
    @Column(name = "comment", length = 500)
    private String comment;

    /** S1-FIN (fix) : compteur d'approbations reçues (pour minApprovals > 1). */
    @Column(name = "approval_count", nullable = false)
    private int approvalCount = 0;

    /** S1-FIN (fix) : liste des approbateurs ayant déjà approuvé (JSONB, pour éviter les doubles approbations). */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "approver_user_ids", columnDefinition = "jsonb")
    private String approverUserIds;

    // --- Getters / setters ---

    public ApprovalActionType getActionType() { return actionType; }
    public void setActionType(ApprovalActionType actionType) { this.actionType = actionType; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public ApprovalStatus getStatus() { return status; }
    public void setStatus(ApprovalStatus status) { this.status = status; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public int getApprovalCount() { return approvalCount; }
    public void setApprovalCount(int approvalCount) { this.approvalCount = approvalCount; }

    public String getApproverUserIds() { return approverUserIds; }
    public void setApproverUserIds(String approverUserIds) { this.approverUserIds = approverUserIds; }
}

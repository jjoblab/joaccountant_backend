package jo.accountant.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Ligne du journal d'audit persistée par {@link AuditEventListener}.
 *
 * <p>§3.6 : :audit-trail ne dépend d'AUCUN autre module métier. Il écoute génériquement les
 * événements {@link jo.accountant.core.audit.AuditableAction}.
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} parce que les lignes
 * d'audit DOIVENT survivre même si le tenant qui les a produites est supprimé plus tard —
 * {@code company_id} est ici une colonne simple, pas un discriminateur. C'est la seule exception
 * sanctionnée dans le projet.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "action", nullable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value_json", columnDefinition = "jsonb")
    private String oldValueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value_json", columnDefinition = "jsonb")
    private String newValueJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id")
    private String correlationId;

    // ── v9.4 fix — Champs alignés sur les standards NetSuite/Sage Intacct ──
    // 1. Execution context : comment l'action a été déclenchée (UI, API, import, cron).
    //    NetSuite "Execution Context", Sage Intacct "Source".
    @Column(name = "execution_context", length = 20)
    private String executionContext;

    // 2. Adresse IP de l'utilisateur — Sage Intacct Advanced Audit Trail,
    //    Odoo OCA auditlog. Essentiel pour forensique (source d'une fraude).
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // 3. User-Agent — pour distinguer mobile vs web vs API programmatique.
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    // 4. Nom du champ modifié — NetSuite "Field", SAP CDPOS "FNAME".
    //    Permet de filtrer "toutes les modifications du champ 'amount'" sans
    //    parser le JSONB old/new. null pour les actions whole-row (CREATE/DELETE).
    @Column(name = "field_name", length = 100)
    private String fieldName;

    // v9.4 fix — @Version retiré : l'audit trail doit être immutable (V27_002 installe
    // des triggers BEFORE UPDATE/DELETE qui bloquent toute modification). Le @Version
    // permettait à Hibernate de faire des UPDATE (optimistic locking) — mais ces UPDATE
    // seraient de toute façon bloqués par le trigger. Retirer @Version évite la confusion
    // et l'erreur Hibernate "Row was updated or deleted by another transaction" quand
    // le trigger bloque l'UPDATE que Hibernate tente après un changement d'entité.
    // (L'audit trail est INSERT-ONLY par design — aucun UPDATE n'est jamais légitime.)

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOldValueJson() { return oldValueJson; }
    public void setOldValueJson(String oldValueJson) { this.oldValueJson = oldValueJson; }
    public String getNewValueJson() { return newValueJson; }
    public void setNewValueJson(String newValueJson) { this.newValueJson = newValueJson; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    // v9.4 — Getters/setters pour les nouveaux champs
    public String getExecutionContext() { return executionContext; }
    public void setExecutionContext(String executionContext) { this.executionContext = executionContext; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
}

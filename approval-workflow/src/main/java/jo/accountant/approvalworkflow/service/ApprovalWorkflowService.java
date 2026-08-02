package jo.accountant.approvalworkflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jo.accountant.approvalworkflow.dto.EvaluateResult;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.approvalworkflow.entity.ApprovalRule;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import jo.accountant.approvalworkflow.event.ApprovalDecidedEvent;
import jo.accountant.approvalworkflow.event.ApprovalRequestedEvent;
import jo.accountant.approvalworkflow.event.ApprovalRuleCreatedEvent;
import jo.accountant.approvalworkflow.repository.ApprovalRequestRepository;
import jo.accountant.approvalworkflow.repository.ApprovalRuleRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service du workflow d'approbation "quatre yeux" (§7, §13.
 *
 * <p>Responsabilités :
 * <ul>
 * <li>Création / listing des {@link ApprovalRule règles} par entreprise</li>
 * <li><strong>Évaluation</strong> d'une action financière contre le seuil configuré
 * ({@link #evaluate}) — point d'extension principal appelé par les modules/12/14</li>
 * <li>Décision sur les demandes en attente : {@link #approve}, {@link #reject},
 * {@link #cancel}</li>
 * <li>Notification des approbateurs éligibles via {@link NotificationChannelPort}</li>
 * </ul>
 *
 * <p>Règles métier §7 (chacune testée par un test qui échouerait si la règle était retirée) :
 * <ol>
 * <li>Absence de règle active = aucune approbation requise (pas de blocage surprise
 * pour une petite structure).</li>
 * <li>Montant &le; seuil = postage/émission directs, sans passage par ce module.</li>
 * <li>Montant &gt; seuil = création d'une {@link ApprovalRequest} PENDING, l'action cible
 * doit être mise à l'état intermédiaire {@code PENDING_APPROVAL} côté consommateur.</li>
 * <li><strong>Règle des quatre yeux</strong> : l'auteur d'une demande ne peut jamais être
 * son propre approbateur ({@code requestedBy == decidedBy} → 403 sur approve/reject).</li>
 * <li>Rejet → l'action cible revient à {@code DRAFT} côté consommateur, avec motif
 * horodaté et visible.</li>
 * <li>Chaque création de {@link ApprovalRequest} notifie tous les utilisateurs ayant un
 * rôle listé dans {@code requiredApproverRoles} pour cette entreprise.</li>
 * </ol>
 *
 * <p>Note sur l'indépendance du module (principe 5) : {@code :approval-workflow} ne dépend
 * que de {@code :core} et {@code :audit-trail}. La notification des approbateurs est faite
 * via {@link NotificationChannelPort} — le port expose une méthode {@code sendEmail(to, ...)}
 * qui prend un email, pas un userId. La résolution userId → email est faite par l'appelant
 * (le contrôleur, qui a accès à {@code :auth}) AVANT d'appeler le service. En, le
 * service notifie via une méthode interne qui prend la liste des emails déjà résolus.
 
 *
 * @author jo@Dev


*/
@Service
public class ApprovalWorkflowService {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalWorkflowService.class);

    /** Rôles autorisés pour {@code requiredApproverRoles}. Même nomenclature que {@code UserRole} de :auth. */
    private static final Set<String> AUTHORIZED_ROLES = Set.of(
        "OWNER", "ADMIN", "ACCOUNTANT", "BOOKKEEPER", "VIEWER", "AUDITOR");

    private final ApprovalRuleRepository ruleRepository;
    private final ApprovalRequestRepository requestRepository;
    private final NotificationChannelPort notificationChannel;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public ApprovalWorkflowService(ApprovalRuleRepository ruleRepository,
                                   ApprovalRequestRepository requestRepository,
                                   NotificationChannelPort notificationChannel,
                                   ApplicationEventPublisher events,
                                   ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.requestRepository = requestRepository;
        this.notificationChannel = notificationChannel;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    // --- Règles ---

    /**
     * Crée une règle active pour un actionType. 409 s'il existe déjà une règle active.
     */
    @Transactional
    public ApprovalRule createRule(UUID companyId, ApprovalActionType actionType,
                                   BigDecimal thresholdAmount,
                                   List<String> requiredApproverRoles,
                                   int minApprovals) {
        validateRuleInputs(actionType, thresholdAmount, requiredApproverRoles, minApprovals);

        if (ruleRepository.existsByCompanyIdAndActionTypeAndActiveTrue(companyId, actionType)) {
            throw new ConflictException("APPROVAL_RULE_ALREADY_EXISTS",
                "Une règle active existe déjà pour actionType=" + actionType
                + " dans cette entreprise. Désactiver l'ancienne avant d'en créer une nouvelle.");
        }

        ApprovalRule rule = new ApprovalRule();
        rule.setCompanyId(companyId);
        rule.setActionType(actionType);
        rule.setThresholdAmount(thresholdAmount);
        rule.setRequiredApproverRoles(serializeRoles(requiredApproverRoles));
        rule.setMinApprovals(minApprovals);
        rule.setActive(true);
        ApprovalRule saved = ruleRepository.save(rule);

        events.publishEvent(new ApprovalRuleCreatedEvent(saved, TenantContext.getUserId()));
        return saved;
    }

    /** Liste les règles de l'entreprise (actives et inactives). */
    @Transactional(readOnly = true)
    public List<ApprovalRule> listRules(UUID companyId) {
        return ruleRepository.findByCompanyIdOrderByActionTypeAsc(companyId);
    }

    /**
     * Désactive une règle. Seul moyen de "supprimer" une règle — la suppression physique est
     * interdite (audit trail).
     */
    @Transactional
    public ApprovalRule deactivateRule(UUID companyId, UUID ruleId) {
        ApprovalRule rule = ruleRepository.findById(ruleId)
            .orElseThrow(() -> new NotFoundException("ApprovalRule", ruleId));
        if (!rule.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ApprovalRule", ruleId);
        }
        rule.setActive(false);
        return ruleRepository.save(rule);
    }

    // --- Évaluation ---

    /**
     * <strong>Point d'extension principal</strong> (§7) — évalue une action financière contre
     * le seuil configuré.
     *
     * <p>Appelé par les modules({@code JournalEntry.post}), 12
     * ({@code SalesInvoice.issue}), 14 ({@code Grant.close-fiscal-year}) avant la transition
     * qui rend l'action définitive.
     *
     * <p>Comportement :
     * <ul>
     * <li>Pas de règle active pour ce actionType → {@link EvaluateResult#autoApproved}.</li>
     * <li>Montant &le; seuil → {@link EvaluateResult#autoApproved}.</li>
     * <li>Montant &gt; seuil → crée une {@link ApprovalRequest} PENDING, notifie les
     * approbateurs éligibles (via {@link NotificationChannelPort}), publie un événement
     * audit, retourne {@link EvaluateResult#pending} avec le requestId.</li>
     * </ul>
     *
     * @param companyId identifiant du tenant
     * @param actionType type d'action
     * @param resourceType type de l'entité cible (ex. "JournalEntry")
     * @param resourceId ID de l'entité cible
     * @param amount montant de l'action en devise fonctionnelle
     * @param approverEmails emails des approbateurs éligibles (résolus par l'appelant à
     * partir de {@code requiredApproverRoles} et de la liste des utilisateurs de
     * l'entreprise — la résolution userId→email n'est pas faite ici pour préserver
     * l'indépendance du module vis-à-vis de :auth)
     */
    @Transactional
    public EvaluateResult evaluate(UUID companyId, ApprovalActionType actionType,
                                   String resourceType, UUID resourceId,
                                   BigDecimal amount, List<String> approverEmails) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new ValidationException("RESOURCE_TYPE_REQUIRED", "resourceType est requis");
        }
        if (resourceId == null) {
            throw new ValidationException("RESOURCE_ID_REQUIRED", "resourceId est requis");
        }
        if (amount == null || amount.signum() < 0) {
            throw new ValidationException("AMOUNT_INVALID", "amount doit être ≥ 0");
        }

        ApprovalRule rule = ruleRepository
            .findByCompanyIdAndActionTypeAndActiveTrue(companyId, actionType)
            .orElse(null);

        if (rule == null) {
            LOG.debug("Aucune règle active pour actionType={} dans l'entreprise {} → auto-approved",
                actionType, companyId);
            return EvaluateResult.autoApproved(actionType);
        }

        if (amount.compareTo(rule.getThresholdAmount()) <= 0) {
            LOG.debug("Montant {} ≤ seuil {} pour actionType={} → auto-approved",
                amount, rule.getThresholdAmount(), actionType);
            return EvaluateResult.autoApproved(actionType);
        }

        // Montant > seuil → créer une demande PENDING
        // Audit M12 (corrigé) : même si approverEmails est vide (ce qui arrive pour toutes les
        // écritures automatiques via invoicing/fixed-assets/inventory qui appellent
        // postJournalEntry(..., List.of())), on crée quand même la ApprovalRequest. La
        // notification est best-effort (voir notifyApprovers) — un avertissement est loggé
        // si la liste est vide, mais la demande est créée et l'écriture passe à PENDING_APPROVAL.
        // Le listener @EventListener(ApprovalDecidedEvent) dans accounting-engine (audit B2)
        // finalisera le postage quand un approbateur décidera via POST /approval-requests/{id}/approve.
        // Avant cette correction, le workflow était silencieusement désactivé pour toutes les
        // écritures automatiques (contournement du 4-yeux).
        ApprovalRequest request = new ApprovalRequest();
        request.setCompanyId(companyId);
        request.setActionType(actionType);
        request.setResourceType(resourceType);
        request.setResourceId(resourceId);
        request.setAmount(amount);
        request.setRequestedBy(TenantContext.getUserId());
        request.setRequestedAt(Instant.now());
        request.setStatus(ApprovalStatus.PENDING);
        ApprovalRequest saved = requestRepository.save(request);

        events.publishEvent(new ApprovalRequestedEvent(saved, TenantContext.getUserId()));

        // Notifier les approbateurs éligibles (emails résolus par l'appelant — peut être vide
        // pour les écritures automatiques ; un admin devra alors consulter la liste des demandes
        // en attente via GET /approval-requests?status=PENDING).
        notifyApprovers(approverEmails, saved);

        LOG.info("Demande d'approbation créée : requestId={} actionType={} amount={} resourceId={} approverEmails={}",
            saved.getId(), actionType, amount, resourceId,
            (approverEmails == null ? 0 : approverEmails.size()));
        return EvaluateResult.pending(saved.getId(), actionType);
    }

    /**
     * Approuve une demande. Règle des quatre yeux : 403 si
     * {@code requestedBy == decidedBy}.
     *
     * <p>S1-FIN (fix) : si la règle a {@code minApprovals > 1}, on incrémente le compteur
     * d'approbations et on ne passe à APPROVED que quand le compteur atteint minApprovals.
     * Un même utilisateur ne peut pas approuver deux fois.
     */
    @Transactional
    public ApprovalRequest approve(UUID companyId, UUID requestId, UUID deciderId, String comment) {
        ApprovalRequest request = loadAndCheckTenant(companyId, requestId);
        ensurePending(request);
        ensureFourEyes(request, deciderId);

        // S1-FIN (fix) : vérifier que cet approbateur n'a pas déjà approuvé
        List<UUID> approverIds = deserializeApproverIds(request.getApproverUserIds());
        if (approverIds.contains(deciderId)) {
            throw new ForbiddenException("ALREADY_APPROVED_BY_USER",
                "Cet utilisateur a déjà approuvé cette demande");
        }
        approverIds.add(deciderId);
        request.setApproverUserIds(serializeApproverIds(approverIds));
        request.setApprovalCount(request.getApprovalCount() + 1);

        // Trouver la règle pour vérifier minApprovals
        ApprovalRule rule = ruleRepository
            .findByCompanyIdAndActionTypeAndActiveTrue(companyId, request.getActionType())
            .orElse(null);

        int minApprovals = (rule != null) ? rule.getMinApprovals() : 1;

        if (request.getApprovalCount() >= minApprovals) {
            // Assez d'approbations → APPROVED
            request.setStatus(ApprovalStatus.APPROVED);
            request.setDecidedBy(deciderId);
            request.setDecidedAt(Instant.now());
            request.setComment(comment);
        } else {
            // Pas encore assez d'approbations → reste PENDING
            // On met à jour le comment avec le dernier approbateur
            String existingComment = request.getComment() != null ? request.getComment() + " | " : "";
            request.setComment(existingComment + "Approbation " + request.getApprovalCount()
                + "/" + minApprovals + " par " + deciderId);
        }

        ApprovalRequest saved = requestRepository.save(request);
        events.publishEvent(new ApprovalDecidedEvent(saved, deciderId));
        return saved;
    }

    /**
     * Rejette une demande. Règle des quatre yeux : 403 si
     * {@code requestedBy == decidedBy}. Le commentaire est obligatoire.
     */
    @Transactional
    public ApprovalRequest reject(UUID companyId, UUID requestId, UUID deciderId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new ValidationException("REJECT_COMMENT_REQUIRED",
                "Un motif est obligatoire pour un rejet");
        }
        ApprovalRequest request = loadAndCheckTenant(companyId, requestId);
        ensurePending(request);
        ensureFourEyes(request, deciderId);

        request.setStatus(ApprovalStatus.REJECTED);
        request.setDecidedBy(deciderId);
        request.setDecidedAt(Instant.now());
        request.setComment(comment.trim());
        ApprovalRequest saved = requestRepository.save(request);

        events.publishEvent(new ApprovalDecidedEvent(saved, deciderId));
        return saved;
    }

    /**
     * Annule une demande. Contrairement à approve/reject, le demandeur lui-même peut annuler
     * sa propre demande (typiquement avant décision).
     */
    @Transactional
    public ApprovalRequest cancel(UUID companyId, UUID requestId, UUID deciderId, String comment) {
        ApprovalRequest request = loadAndCheckTenant(companyId, requestId);
        ensurePending(request);

        request.setStatus(ApprovalStatus.CANCELLED);
        request.setDecidedBy(deciderId);
        request.setDecidedAt(Instant.now());
        request.setComment(comment == null ? null : comment.trim());
        ApprovalRequest saved = requestRepository.save(request);

        events.publishEvent(new ApprovalDecidedEvent(saved, deciderId));
        return saved;
    }

    // --- Liste des demandes ---

    @Transactional(readOnly = true)
    public List<ApprovalRequest> listRequests(UUID companyId, ApprovalStatus status) {
        if (status == null) {
            return requestRepository.findByCompanyIdOrderByRequestedAtDesc(companyId);
        }
        return requestRepository.findByCompanyIdAndStatusOrderByRequestedAtDesc(companyId, status);
    }

    // --- Helpers ---

    private void validateRuleInputs(ApprovalActionType actionType, BigDecimal thresholdAmount,
                                    List<String> requiredApproverRoles, int minApprovals) {
        if (actionType == null) {
            throw new ValidationException("ACTION_TYPE_REQUIRED", "actionType est requis");
        }
        if (thresholdAmount == null || thresholdAmount.signum() < 0) {
            throw new ValidationException("THRESHOLD_INVALID", "thresholdAmount doit être ≥ 0");
        }
        if (requiredApproverRoles == null || requiredApproverRoles.isEmpty()) {
            throw new ValidationException("REQUIRED_APPROVER_ROLES_REQUIRED",
                "Au moins un rôle approbateur est requis");
        }
        for (String role : requiredApproverRoles) {
            if (!AUTHORIZED_ROLES.contains(role)) {
                throw new ValidationException("UNKNOWN_ROLE",
                    "Rôle inconnu : " + role + ". Rôles autorisés : " + AUTHORIZED_ROLES);
            }
        }
        if (minApprovals < 1) {
            throw new ValidationException("MIN_APPROVALS_INVALID",
                "minApprovals doit être ≥ 1");
        }
        // Vague 3, item 3.1 : minApprovals > 1 est maintenant supporté.
        // Le workflow multi-étapes fonctionne : approve() passe à APPROVED uniquement
        // quand le nombre d'approbations atteint minApprovals. Pour minApprovals=1,
        // comportement inchangé (approbation unique).
    }

    private ApprovalRequest loadAndCheckTenant(UUID companyId, UUID requestId) {
        ApprovalRequest request = requestRepository.findById(requestId)
            .orElseThrow(() -> new NotFoundException("ApprovalRequest", requestId));
        if (!request.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ApprovalRequest", requestId); // §3.9 — 404 pas 403
        }
        return request;
    }

    private void ensurePending(ApprovalRequest request) {
        if (request.getStatus() != ApprovalStatus.PENDING) {
            throw new ConflictException("APPROVAL_REQUEST_ALREADY_DECIDED",
                "La demande " + request.getId() + " a déjà été décidée (statut="
                + request.getStatus() + "). Aucune re-décision possible.");
        }
    }

    /**
     * Règle des quatre yeux (§7) : l'auteur d'une demande ne peut pas l'approuver ni la
     * rejeter lui-même. 403 si violation.
     *
     * <p>L'annulation est explicitement hors périmètre de cette règle : le demandeur peut
     * annuler sa propre demande (typiquement avant décision).
     */
    private void ensureFourEyes(ApprovalRequest request, UUID deciderId) {
        if (request.getRequestedBy().equals(deciderId)) {
            throw new ForbiddenException("SELF_APPROVAL_FORBIDDEN",
                "Vous ne pouvez pas approuver/rejeter une demande que vous avez vous-même créée " +
                "(règle des quatre yeux, §7).");
        }
    }

    private void notifyApprovers(List<String> approverEmails, ApprovalRequest request) {
        if (approverEmails == null || approverEmails.isEmpty()) {
            LOG.warn("Aucun email d'approbateur à notifier pour la demande {} — la résolution " +
                "userId→email a échoué côté appelant ?", request.getId());
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("actionType", request.getActionType().name());
        variables.put("resourceType", request.getResourceType());
        variables.put("resourceId", request.getResourceId().toString());
        variables.put("amount", request.getAmount());
        variables.put("requestId", request.getId().toString());
        variables.put("requestedBy", request.getRequestedBy().toString());

        for (String email : approverEmails) {
            try {
                notificationChannel.sendEmail(email, "approval-requested", variables);
            } catch (Exception ex) {
                // La notification est best-effort — ne pas faire échouer la création de la demande
                LOG.warn("Échec de notification de {} pour la demande {} : {}",
                    email, request.getId(), ex.getMessage());
            }
        }
    }

    private String serializeRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize roles", e);
        }
    }

    /** Désérialise la liste des rôles approbateurs d'une règle. Utilisé par le contrôleur. */
    public List<String> deserializeRoles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to deserialize roles: {}", json, e);
            return List.of();
        }
    }

    /** S1-FIN (fix) : sérialise la liste des UUIDs d'approbateurs. */
    private String serializeApproverIds(List<UUID> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize approver ids", e);
        }
    }

    /** S1-FIN (fix) : désérialise la liste des UUIDs d'approbateurs. */
    private List<UUID> deserializeApproverIds(String json) {
        if (json == null || json.isBlank()) return new java.util.ArrayList<>();
        try {
            return new java.util.ArrayList<>(objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class)));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to deserialize approver ids: {}", json, e);
            return new java.util.ArrayList<>();
        }
    }
}

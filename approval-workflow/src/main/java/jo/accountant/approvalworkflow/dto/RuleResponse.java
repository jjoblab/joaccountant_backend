package jo.accountant.approvalworkflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;

/**
 * Réponse pour {@code GET .../rules} et {@code POST .../rules}.
 */
public record RuleResponse(
    UUID id,
    UUID companyId,
    ApprovalActionType actionType,
    BigDecimal thresholdAmount,
    List<String> requiredApproverRoles,
    int minApprovals,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {}

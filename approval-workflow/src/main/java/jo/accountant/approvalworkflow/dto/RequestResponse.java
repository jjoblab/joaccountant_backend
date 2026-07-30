package jo.accountant.approvalworkflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;

/**
 * Réponse pour {@code GET .../requests}.
 */
public record RequestResponse(
    UUID id,
    UUID companyId,
    ApprovalActionType actionType,
    String resourceType,
    UUID resourceId,
    BigDecimal amount,
    UUID requestedBy,
    Instant requestedAt,
    ApprovalStatus status,
    UUID decidedBy,
    Instant decidedAt,
    String comment
) {}

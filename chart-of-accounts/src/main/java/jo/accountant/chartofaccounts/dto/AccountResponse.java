package jo.accountant.chartofaccounts.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.core.framework.ReportingClass;

/**
 * Réponse d'un compte, avec ses enfants optionnels (pour le format {@code tree}).
 */
public record AccountResponse(
    UUID id,
    UUID parentId,
    String code,
    String label,
    int level,
    ReportingClass reportingClass,
    ReportingSubcategory reportingSubcategory,
    NormalBalance normalBalance,
    boolean locked,
    boolean active,
    boolean isCollective,
    String path,
    String taxMappingCode,
    List<UUID> requiresAnalyticalTagPlanIds,
    Instant createdAt,
    Instant updatedAt,
    List<AccountResponse> children
) {}

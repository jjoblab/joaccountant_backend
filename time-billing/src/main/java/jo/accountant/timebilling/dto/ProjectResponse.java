package jo.accountant.timebilling.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.timebilling.entity.BillingType;
import jo.accountant.timebilling.entity.ProjectStatus;

/**
 * ProjectResponse.
 *
 * @author jo@Dev


 */

public record ProjectResponse(
    UUID id,
    UUID companyId,
    UUID clientThirdPartyId,
    String code,
    String label,
    ProjectStatus status,
    BillingType billingType,
    Instant createdAt,
    Instant updatedAt
) {}

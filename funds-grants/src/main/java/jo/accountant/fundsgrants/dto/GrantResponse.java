package jo.accountant.fundsgrants.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.RestrictionType;

/**
 * GrantResponse.
 *
 * @author jo@Dev


 */

public record GrantResponse(
    UUID id, UUID companyId, UUID donorThirdPartyId, String code, String label,
    BigDecimal totalAmount, String currency, LocalDate startDate, LocalDate endDate,
    RestrictionType restrictionType, UUID analyticalValueId,
    Instant createdAt, Instant updatedAt
) {}

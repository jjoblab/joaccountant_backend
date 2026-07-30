package jo.accountant.thirdparties.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.thirdparties.entity.LettrageStatus;

/**
 * Réponse d'un lettrage.
 */
public record LettrageResponse(
    UUID id,
    UUID thirdPartyId,
    String matchCode,
    LettrageStatus status,
    BigDecimal matchedAmount,
    Instant matchedAt,
    UUID matchedBy,
    List<UUID> journalLineIds
) {}

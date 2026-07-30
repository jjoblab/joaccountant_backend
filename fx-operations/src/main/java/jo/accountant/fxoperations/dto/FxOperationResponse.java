package jo.accountant.fxoperations.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.fxoperations.entity.FxOperationStatus;
import jo.accountant.fxoperations.entity.FxOperationType;

public record FxOperationResponse(
    UUID id,
    UUID companyId,
    FxOperationType type,
    String fromCurrency,
    String toCurrency,
    BigDecimal fromAmount,
    BigDecimal toAmount,
    BigDecimal rate,
    BigDecimal fromAmountFunctional,
    BigDecimal toAmountFunctional,
    BigDecimal fxGainLoss,
    LocalDate operationDate,
    String description,
    UUID journalEntryId,
    UUID reversalOfId,
    FxOperationStatus status,
    Instant createdAt,
    Instant updatedAt
) {}

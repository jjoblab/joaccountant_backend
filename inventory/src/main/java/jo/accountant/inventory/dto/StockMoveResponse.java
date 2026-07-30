package jo.accountant.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.inventory.entity.StockMoveDirection;

public record StockMoveResponse(
    UUID id,
    UUID itemId,
    UUID warehouseId,
    LocalDate moveDate,
    StockMoveDirection direction,
    BigDecimal quantity,
    BigDecimal unitCost,
    BigDecimal totalCost,
    String sourceDocument,
    UUID journalEntryId,
    Instant createdAt
) {}

package jo.accountant.accountingengine.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ligne du grand livre (renvoyée par {@code GET .../ledger}).
 
 *
 * @author jo@Dev


*/
public record LedgerLine(
    LocalDate entryDate,
    String reference,
    String description,
    String accountCode,
    BigDecimal debit,
    BigDecimal credit,
    BigDecimal runningBalance,
    UUID journalEntryId
) {}

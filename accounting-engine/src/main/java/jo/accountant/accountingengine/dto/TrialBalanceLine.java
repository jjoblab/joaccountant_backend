package jo.accountant.accountingengine.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ligne de la balance générale (renvoyée par {@code GET .../trial-balance}).
 
 *
 * @author jo@Dev


*/
public record TrialBalanceLine(
    UUID accountId,
    String accountCode,
    String accountLabel,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal balance
) {}

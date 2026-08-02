package jo.accountant.thirdparties.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Relevé de compte d'un tiers — {@code GET .../third-parties/{id}/statement}.
 *
 * <p>Liste toutes les écritures POSTED du tiers, avec le solde lettré et le solde non lettré.
 
 *
 * @author jo@Dev


*/
public record ThirdPartyStatement(
    UUID thirdPartyId,
    String thirdPartyName,
    LocalDate from,
    LocalDate to,
    List<StatementLine> lines,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal balance,
    BigDecimal unletteredBalance
) {

    /** Ligne du relevé. */
    public record StatementLine(
        UUID journalLineId,
        LocalDate entryDate,
        String reference,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        String matchCode, // null si non lettrée
        BigDecimal runningBalance
    ) {}
}

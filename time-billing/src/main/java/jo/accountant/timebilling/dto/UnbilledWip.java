package jo.accountant.timebilling.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * WIP (travail en cours) d'un projet — {@code GET .../projects/{id}/unbilled}.
 *
 * <p>Liste les entrées approuvées, billables, non facturées, avec le taux applicable et
 * le montant total du WIP.
 
 *
 * @author jo@Dev


*/
public record UnbilledWip(
    UUID projectId,
    String projectCode,
    String projectLabel,
    BigDecimal totalHours,
    BigDecimal totalAmount,
    List<UnbilledLine> lines
) {
    public record UnbilledLine(
        UUID entryId,
        LocalDate entryDate,
        UUID resourceUserId,
        BigDecimal hours,
        BigDecimal hourlyRate,
        BigDecimal amount
    ) {}
}

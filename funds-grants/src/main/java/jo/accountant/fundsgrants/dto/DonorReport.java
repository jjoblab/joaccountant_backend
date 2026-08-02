package jo.accountant.fundsgrants.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Rapport bailleur par subvention (§13.
 *
 * <p>Utile pour la reddition de comptes aux bailleurs institutionnels.
 
 *
 * @author jo@Dev


*/
public record DonorReport(
    UUID grantId, String grantCode, String grantLabel,
    UUID donorId, String donorName,
    BigDecimal totalReceived, BigDecimal totalSpent, BigDecimal balanceRemaining,
    LocalDate from, LocalDate to
) {}

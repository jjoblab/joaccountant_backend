package jo.accountant.fundsgrants.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Résultat de la clôture d'exercice pour une subvention (§13fonds dédiés).
 
 *
 * @author jo@Dev


*/
public record CloseFiscalYearResult(
    UUID grantId, String grantCode,
    BigDecimal products, BigDecimal charges, BigDecimal balance,
    boolean fundsDedicatedProposed, UUID approvalRequestId,
    String message
) {}

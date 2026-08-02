package jo.accountant.reporting.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Balance âgée des factures clients (audit M5).
 *
 * <p>Ventile le solde dû ({@code totalAmount - paidAmount}) des factures ISSUED/PARTIALLY_PAID
 * par tranche d'âge depuis la date d'échéance :
 * <ul>
 * <li>{@code current} — pas encore échue (dueDate ≥ today)</li>
 * <li>{@code d0_30} — échue depuis 0 à 30 jours</li>
 * <li>{@code d31_60} — échue depuis 31 à 60 jours</li>
 * <li>{@code d61_90} — échue depuis 61 à 90 jours</li>
 * <li>{@code d90_plus} — échue depuis plus de 90 jours</li>
 * </ul>
 *
 * <p>Utilisé pour le suivi du risque client (indicateur clé de trésorerie).
 *
 * @param totalBalanceDue somme des soldes dus toutes tranches confondues
 
 *
 * @author jo@Dev


*/
public record AgedBalance(
    UUID companyId,
    BigDecimal current,
    BigDecimal d0_30,
    BigDecimal d31_60,
    BigDecimal d61_90,
    BigDecimal d90_plus,
    BigDecimal totalBalanceDue,
    int invoiceCount
) {
    public static AgedBalance empty(UUID companyId) {
        return new AgedBalance(companyId,
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0);
    }
}

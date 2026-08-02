package jo.accountant.thirdparties.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Balance âgée d'un tiers — {@code GET .../third-parties/{id}/aged-balance}.
 *
 * <p>Répartition du solde non lettré par tranche d'âge :
 * <ul>
 * <li>{@link #bucket0to30} — 0 à 30 jours (récent)</li>
 * <li>{@link #bucket31to60} — 31 à 60 jours</li>
 * <li>{@link #bucket61to90} — 61 à 90 jours</li>
 * <li>{@link #bucket90plus} — plus de 90 jours (ancien — à relancer)</li>
 * </ul>
 *
 * <p>L'âge est calculé à partir de la date d'écriture par rapport à la date « as of »
 * (typiquement aujourd'hui).
 
 *
 * @author jo@Dev


*/
public record AgedBalance(
    UUID thirdPartyId,
    java.time.LocalDate asOf,
    BigDecimal bucket0to30,
    BigDecimal bucket31to60,
    BigDecimal bucket61to90,
    BigDecimal bucket90plus,
    BigDecimal totalUnlettered
) {}

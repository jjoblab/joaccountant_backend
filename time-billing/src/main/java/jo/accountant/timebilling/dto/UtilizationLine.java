package jo.accountant.timebilling.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ligne d'agrégation de taux d'utilisation des consultants —
 * {@code GET .../time-billing/utilization?from=&to=} (Part E3).
 *
 * <p>Une ligne par couple (projet, consultant) sur la période demandée. Les heures sont
 * ventilées en :
 * <ul>
 *   <li>{@code hoursLogged} — toutes les heures saisies sur la période (billables ou non,
 *       approuvées ou non, facturées ou non).</li>
 *   <li>{@code hoursBilled} — heures facturables (billable=true) approuvées (approved=true)
 *       et déjà facturées (invoiced=true). Comptabilisées au client.</li>
 *   <li>{@code hoursUnbilled} — heures facturables approuvées non encore facturées
 *       (invoiced=false) = WIP en cours.</li>
 * </ul>
 *
 * <p>Le {@code utilizationRate} (%) = (hoursBilled + hoursUnbilled) / hoursLogged × 100
 * (i.e. part des heures facturables parmi toutes les heures saisies). 0 si hoursLogged = 0.
 *
 * <p>Note sur la résolution du nom du consultant : le {@code consultantId} est un
 * {@code resourceUserId} référençant un {@code User} du module :auth. La résolution en nom
 * affichable se fait côté client (via l'endpoint de liste des utilisateurs de l'entreprise)
 * afin de ne pas introduire de dépendance directe :time-billing → :auth. Le champ
 * {@code consultant} est donc le {@code resourceUserId} sous forme de chaîne — à utiliser
 * comme clé de jointure côté frontend, pas comme libellé affichable.
 *
 * @param projectId       identifiant du projet
 * @param projectCode     code du projet (ex. "PRJ-001")
 * @param projectLabel    libellé du projet
 * @param consultantId    identifiant de l'utilisateur/consultant (resourceUserId)
 * @param consultant      identifiant du consultant sous forme de chaîne (UUID.toString())
 *                        — résolution du nom à faire côté client (voir note ci-dessus)
 * @param hoursLogged     toutes les heures saisies sur la période
 * @param hoursBilled     heures facturables + approuvées + facturées
 * @param hoursUnbilled   heures facturables + approuvées + non facturées (WIP)
 * @param utilizationRate taux d'utilisation en % (= billable / logged × 100)
 */
public record UtilizationLine(
    UUID projectId,
    String projectCode,
    String projectLabel,
    UUID consultantId,
    String consultant,
    BigDecimal hoursLogged,
    BigDecimal hoursBilled,
    BigDecimal hoursUnbilled,
    BigDecimal utilizationRate
) {}

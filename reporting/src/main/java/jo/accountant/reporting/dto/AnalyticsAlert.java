package jo.accountant.reporting.dto;

/**
 * Alerte métier (Analytics Dashboard).
 *
 * <p>Une alerte est un signal/actionable destiné à attirer l'attention de
 * l'utilisateur sur un point nécessitant un traitement. Le MVP couvre deux
 * types :
 * <ul>
 * <li><b>OVERDUE_INVOICE</b> — factures de ventes échues depuis plus de
 * 90 jours et non réglées (severity = HIGH si montant total &gt; 0) ;</li>
 * <li><b>LOW_STOCK</b> — articles en stock dont la quantité est tombée
 * sous le seuil de réapprovisionnement (severity = MEDIUM).</li>
 * </ul>
 *
 * @param type type d'alerte (OVERDUE_INVOICE, LOW_STOCK, …)
 * @param message message localisé décrivant l'alerte
 * @param severity sévérité : HIGH (rouge) / MEDIUM (orange) / LOW (bleu)
 * @param actionLabel libellé du bouton d'action (ex. "Voir les factures") —
 * peut être null si aucune action n'est disponible
 */
public record AnalyticsAlert(
 String type,
 String message,
 String severity,
 String actionLabel
) {}

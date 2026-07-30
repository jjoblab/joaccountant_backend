package jo.accountant.financialstatements.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Requête de conversion des états financiers vers une devise de présentation
 * (Task v6-4-presentation-currency).
 *
 * <p>Permet de générer le bilan / le compte de résultat / le tableau de flux de trésorerie dans
 * une devise différente de la devise fonctionnelle de l'entreprise — typiquement pour produire
 * la DCR annuelle DGI Haïti en HTG à partir d'une comptabilité tenue en USD (ONG haïtienne ou
 * société en zone franche).
 *
 * <p>Logique de résolution du taux :
 * <ul>
 *   <li>Bilan : si {@code closingRate} est fourni, l'utiliser directement. Sinon, lookup dans
 *       {@code exchange_rate_snapshot} (snapshot_type = CLOSING) à la date {@code asOfDate}.</li>
 *   <li>Compte de résultat / tableau de flux : si {@code averageRate} est fourni, l'utiliser.
 *       Sinon, lookup du taux moyen mensuel (snapshot_type = PERIOD_AVERAGE) le plus récent
 *       dans la période demandée.</li>
 * </ul>
 *
 * <p>Si {@code presentationCurrency} est {@code null} ou égal à la devise fonctionnelle, aucun
 * traitement de conversion n'est appliqué (backward-compat avec le comportement v5.5).
 *
 * @param presentationCurrency code ISO 4217 de la devise cible (ex. "HTG")
 * @param asOfDate             date du bilan (pour lookup du taux de clôture si non fourni)
 * @param closingRate          taux à la clôture (bilan) — nullable, lookup si null
 * @param averageRate          taux moyen de période (CR/CF) — nullable, lookup si null
 */
public record PresentationCurrencyRequest(
    String presentationCurrency,
    LocalDate asOfDate,
    BigDecimal closingRate,
    BigDecimal averageRate
) {

    /**
     * Compact constructor — normalisation : taux éventuellement nuls si non fournis,
     * devise en majuscules (ISO 4217 standard).
     */
    public PresentationCurrencyRequest {
        if (presentationCurrency != null) {
            presentationCurrency = presentationCurrency.trim().toUpperCase();
        }
    }
}

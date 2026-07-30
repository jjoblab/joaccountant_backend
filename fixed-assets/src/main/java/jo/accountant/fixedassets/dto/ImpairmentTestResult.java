package jo.accountant.fixedassets.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Résultat d'un test de dépréciation IAS 36 (Finding #11).
 *
 * <p>Le test de dépréciation compare la valeur nette comptable (VNC) d'une immobilisation
 * avec son montant recouvrable (le plus élevé entre la valeur d'utilité et la juste valeur
 * nette des coûts de cession, selon IAS 36 §6).
 *
 * <p>Si VNC &gt; montant recouvrable, une dépréciation est enregistrée :
 * <ul>
 *   <li>{@code impairmentAmount} = VNC − montant recouvrable</li>
 *   <li>Écriture comptable : D 6816 (Charges pour dépréciation) /
 *       C 291 (Dépréciation des immobilisations)</li>
 * </ul>
 *
 * @param assetId identifiant de l'immobilisation testée
 * @param netBookValue valeur nette comptable = coût d'acquisition − amortissement cumulé −
 *                     dépréciation antérieure
 * @param recoverableAmount montant recouvrable (valeur d'utilité ou juste valeur nette)
 * @param impairmentAmount montant de la dépréciation à enregistrer (0 si pas de dépréciation)
 * @param impaired {@code true} si une dépréciation a été enregistrée (VNC &gt; recouvrable),
 *                 {@code false} sinon
 * @param journalEntryId ID de l'écriture comptable générée (null si aucune dépréciation)
 * @param testedAt horodatage du test
 */
public record ImpairmentTestResult(
    UUID assetId,
    BigDecimal netBookValue,
    BigDecimal recoverableAmount,
    BigDecimal impairmentAmount,
    boolean impaired,
    UUID journalEntryId,
    Instant testedAt
) {}

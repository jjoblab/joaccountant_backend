package jo.accountant.thirdparties.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.thirdparties.entity.LettrageStatus;

/**
 * Ligne d'un rapport de lettrage (step7-backend — Reports Hub v2.5.0).
 *
 * <p>DTO paginé retourné par {@code GET /api/v1/companies/{companyId}/third-parties/lettrage}.
 * Plus riche que {@link LettrageResponse} (qui est centré sur POST) car la vue liste doit
 * permettre à l'utilisateur de décider s'il veut entrer dans le détail sans appel /statement
 * supplémentaire.
 *
 * <p>Champs :
 * <ul>
 *   <li>{@code id} — ID du {@code LettrageMatch}.</li>
 *   <li>{@code thirdPartyId} — ID du tiers lettré.</li>
 *   <li>{@code thirdPartyName} — nom du tiers (résolu via lookup batch).</li>
 *   <li>{@code accountCode} — code du compte dédié du tiers (snapshot au moment du lettrage).</li>
 *   <li>{@code matchCode} — code de lettrage séquentiel (A, B, C, ...).</li>
 *   <li>{@code matchedAt} — timestamp du lettrage.</li>
 *   <li>{@code matchedBy} — ID utilisateur qui a lettré.</li>
 *   <li>{@code matchedAmount} — somme des montants des lignes lettrées (débit + crédit).</li>
 *   <li>{@code status} — statut ({@link LettrageStatus#FULL} ou {@link LettrageStatus#PARTIAL}).</li>
 *   <li>{@code entryCount} — nombre de lignes d'écriture lettrées ensemble (taille de journalLineIds).</li>
 * </ul>
 */
public record LettrageListResponse(
    UUID id,
    UUID thirdPartyId,
    String thirdPartyName,
    String accountCode,
    String matchCode,
    Instant matchedAt,
    UUID matchedBy,
    BigDecimal matchedAmount,
    LettrageStatus status,
    int entryCount
) {}

package jo.accountant.thirdparties.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Corps de requête pour {@code POST .../third-parties/lettrage}.
 *
 * <p>Lettre manuellement un ensemble de lignes d'écriture pour un tiers donné. Le service
 * calcule si le lettrage est FULL (somme débit = somme crédit) ou PARTIAL.
 *
 * @param thirdPartyId ID du tiers concerné
 * @param journalLineIds IDs des lignes à lettrer ensemble (au moins 2)
 */
public record LettrageRequest(
    @NotNull UUID thirdPartyId,
    @NotEmpty List<UUID> journalLineIds
) {}

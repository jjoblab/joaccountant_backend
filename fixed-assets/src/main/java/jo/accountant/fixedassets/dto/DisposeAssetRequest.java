package jo.accountant.fixedassets.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Corps de requête pour {@code POST .../fixed-assets/{id}/dispose}.
 *
 * @param disposalDate date de cession
 * @param disposalAmount prix de cession HT (en devise fonctionnelle)
 * @param cashAccountId compte de trésorerie à débiter (ex. 521 Banque). Si null, utilise le
 * compte d'actif par défaut (comportementà éviter en production).
 
 *
 * @author jo@Dev


*/
public record DisposeAssetRequest(
    @NotNull LocalDate disposalDate,
    @NotNull @PositiveOrZero BigDecimal disposalAmount,
    UUID cashAccountId
) {}

package jo.accountant.fixedassets.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.fixedassets.entity.DepreciationMethod;

/**
 * Réponse d'un composant d'immobilisation (IAS 16).
 
 *
 * @author jo@Dev


*/
public record AssetComponentResponse(
 UUID id,
 UUID assetId,
 String code,
 String label,
 BigDecimal acquisitionCost,
 int usefulLifeYears,
 BigDecimal residualValue,
 DepreciationMethod depreciationMethod,
 Instant createdAt,
 Instant updatedAt
) {}

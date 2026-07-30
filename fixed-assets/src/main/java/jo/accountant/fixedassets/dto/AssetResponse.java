package jo.accountant.fixedassets.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.fixedassets.entity.AssetStatus;
import jo.accountant.fixedassets.entity.DepreciationMethod;

/**
 * Réponse d'une immobilisation.
 *
 * @param disposalGainAccountId compte de PRODUITS pour les plus-values de cession (audit M11).
 *        Peut être null (fallback sur depreciationExpenseAccountId à la cession).
 * @param disposalLossAccountId compte de CHARGES pour les moins-values de cession (audit M11).
 *        Peut être null (fallback sur depreciationExpenseAccountId à la cession).
 * @param acquisitionJournalEntryId ID de l'écriture d'acquisition (audit M10).
 *        Null si l'écriture n'a pas été générée (anciennes immobilisations ou si ni
 *        supplierAccountId ni cashAccountId n'ont été fournis à la création).
 * @param impairmentAmount dépréciation IAS 36 cumulée (Finding #11). 0 si aucun test
 *        n'a constaté de perte de valeur.
 * @param impairmentExpenseAccountId compte de CHARGES pour la dépréciation IAS 36
 *        (ex. 6816). Null = fallback sur depreciationExpenseAccountId.
 * @param accumulatedImpairmentAccountId compte d'ACTIF pour la dépréciation IAS 36 cumulée
 *        (ex. 291). Null = fallback sur accumulatedDepreciationAccountId.
 * @param components liste des composants IAS 16 de l'immobilisation (Finding #11).
 *        Vide si l'amortissement est calculé globalement sur l'asset.
 */
public record AssetResponse(
    UUID id,
    UUID companyId,
    String label,
    LocalDate acquisitionDate,
    BigDecimal acquisitionCost,
    int usefulLifeMonths,
    BigDecimal residualValue,
    DepreciationMethod depreciationMethod,
    UUID assetAccountId,
    UUID depreciationExpenseAccountId,
    UUID accumulatedDepreciationAccountId,
    UUID disposalGainAccountId,
    UUID disposalLossAccountId,
    UUID acquisitionJournalEntryId,
    AssetStatus status,
    LocalDate disposalDate,
    BigDecimal disposalAmount,
    BigDecimal gainOrLoss,
    BigDecimal cumulativeDepreciation,
    BigDecimal impairmentAmount,
    UUID impairmentExpenseAccountId,
    UUID accumulatedImpairmentAccountId,
    List<AssetComponentResponse> components,
    Instant createdAt,
    Instant updatedAt
) {}

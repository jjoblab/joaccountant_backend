package jo.accountant.fixedassets.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.fixedassets.entity.DepreciationMethod;

/**
 * Corps de requête pour {@code POST .../fixed-assets}.
 *
 * @param label libellé de l'immobilisation (ex. "Véhicule Toyota Corolla 2026")
 * @param acquisitionDate date d'acquisition
 * @param acquisitionCost coût d'acquisition HT (en devise fonctionnelle)
 * @param usefulLifeMonths durée de vie utile en mois (ex. 60 = 5 ans)
 * @param residualValue valeur résiduelle estimée (défaut 0)
 * @param depreciationMethod méthode d'amortissement (défaut STRAIGHT_LINE)
 * @param assetAccountId compte d'actif immobilisé (ex. 244)
 * @param depreciationExpenseAccountId compte de charge d'amortissement (ex. 631)
 * @param accumulatedDepreciationAccountId compte d'amortissement cumulé (ex. 2844)
 * @param disposalGainAccountId compte de PRODUITS pour les plus-values de cession (audit M11).
 *        Si null, fallback sur {@code depreciationExpenseAccountId}. Ex. SYSCOHADA : 775.
 * @param disposalLossAccountId compte de CHARGES pour les moins-values de cession (audit M11).
 *        Si null, fallback sur {@code depreciationExpenseAccountId}. Ex. SYSCOHADA : 675.
 * @param supplierAccountId compte de tiers (fournisseur) à créditer à l'acquisition (audit M10).
 *        Si null, fallback sur {@code cashAccountId}. Si les deux sont null, l'écriture
 *        d'acquisition n'est PAS générée (rétro-compatibilité avec les tests existants).
 * @param cashAccountId compte de trésorerie à créditer à l'acquisition (audit M10).
 *        Mutuellement exclusif avec {@code supplierAccountId} (l'un ou l'autre).
 * @param impairmentExpenseAccountId compte de CHARGES pour la dépréciation IAS 36 (Finding #11).
 *        Ex. SYSCOHADA : 6816. Si null, fallback sur {@code depreciationExpenseAccountId}
 *        lors du {@code testImpairment}.
 * @param accumulatedImpairmentAccountId compte d'ACTIF pour la dépréciation IAS 36 cumulée
 *        (Finding #11). Ex. SYSCOHADA : 291. Si null, fallback sur
 *        {@code accumulatedDepreciationAccountId} lors du {@code testImpairment}.
 * @param components liste optionnelle de composants IAS 16 (Finding #11). Si non vide,
 *        l'amortissement est calculé par composant (chaque composant a sa propre durée de
 *        vie). Si vide/null, l'amortissement est calculé globalement sur l'asset.
 */
public record CreateAssetRequest(
    @NotBlank String label,
    @NotNull LocalDate acquisitionDate,
    @NotNull @Positive BigDecimal acquisitionCost,
    @NotNull @Positive int usefulLifeMonths,
    @PositiveOrZero BigDecimal residualValue,
    DepreciationMethod depreciationMethod,
    @NotNull UUID assetAccountId,
    @NotNull UUID depreciationExpenseAccountId,
    @NotNull UUID accumulatedDepreciationAccountId,
    UUID disposalGainAccountId,
    UUID disposalLossAccountId,
    UUID supplierAccountId,
    UUID cashAccountId,
    UUID impairmentExpenseAccountId,
    UUID accumulatedImpairmentAccountId,
    @Valid List<CreateAssetComponentRequest> components
) {
    public CreateAssetRequest {
        if (residualValue == null) residualValue = BigDecimal.ZERO;
        if (depreciationMethod == null) depreciationMethod = DepreciationMethod.STRAIGHT_LINE;
        if (components == null) components = List.of();
    }

    /** Rétro-compatibilité — pour les anciens appelants qui ne passent pas les nouveaux champs. */
    public CreateAssetRequest(
        @NotBlank String label,
        @NotNull LocalDate acquisitionDate,
        @NotNull @Positive BigDecimal acquisitionCost,
        @NotNull @Positive int usefulLifeMonths,
        @PositiveOrZero BigDecimal residualValue,
        DepreciationMethod depreciationMethod,
        @NotNull UUID assetAccountId,
        @NotNull UUID depreciationExpenseAccountId,
        @NotNull UUID accumulatedDepreciationAccountId
    ) {
        this(label, acquisitionDate, acquisitionCost, usefulLifeMonths, residualValue,
             depreciationMethod, assetAccountId, depreciationExpenseAccountId,
             accumulatedDepreciationAccountId, null, null, null, null, null, null, null);
    }

    /** Variante 13-args (audit M10/M11) — rétro-compatibilité. */
    public CreateAssetRequest(
        @NotBlank String label,
        @NotNull LocalDate acquisitionDate,
        @NotNull @Positive BigDecimal acquisitionCost,
        @NotNull @Positive int usefulLifeMonths,
        @PositiveOrZero BigDecimal residualValue,
        DepreciationMethod depreciationMethod,
        @NotNull UUID assetAccountId,
        @NotNull UUID depreciationExpenseAccountId,
        @NotNull UUID accumulatedDepreciationAccountId,
        UUID disposalGainAccountId,
        UUID disposalLossAccountId,
        UUID supplierAccountId,
        UUID cashAccountId
    ) {
        this(label, acquisitionDate, acquisitionCost, usefulLifeMonths, residualValue,
             depreciationMethod, assetAccountId, depreciationExpenseAccountId,
             accumulatedDepreciationAccountId, disposalGainAccountId, disposalLossAccountId,
             supplierAccountId, cashAccountId, null, null, null);
    }

    /**
     * Sous-DTO pour déclarer un composant IAS 16 à la création de l'asset (Finding #11).
     */
    public record CreateAssetComponentRequest(
        @NotBlank String code,
        @NotBlank String label,
        @NotNull @Positive BigDecimal acquisitionCost,
        @NotNull @Positive int usefulLifeYears,
        @PositiveOrZero BigDecimal residualValue,
        DepreciationMethod depreciationMethod
    ) {
        public CreateAssetComponentRequest {
            if (residualValue == null) residualValue = BigDecimal.ZERO;
            if (depreciationMethod == null) depreciationMethod = DepreciationMethod.STRAIGHT_LINE;
        }
    }
}

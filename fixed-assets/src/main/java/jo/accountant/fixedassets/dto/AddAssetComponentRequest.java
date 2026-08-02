package jo.accountant.fixedassets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import jo.accountant.fixedassets.entity.DepreciationMethod;

/**
 * Corps de requête pour ajouter un composant à une immobilisation (IAS 16).
 *
 * <p>Un composant représente une partie identifiable d'une immobilisation ayant sa propre
 * durée de vie utile (ex. structure / toiture / installations pour un bâtiment). L'ajout
 * d'un composant déclenche la regénération de l'échéancier d'amortissement — opération
 * refusée si des lignes ont déjà été postées (409 SCHEDULE_ALREADY_POSTED).
 *
 * @param code code court du composant (ex. "STRUCT"), unique par asset
 * @param label libellé du composant (ex. "Structure béton")
 * @param acquisitionCost coût d'acquisition du composant (> 0)
 * @param usefulLifeYears durée de vie utile en années (≥ 1)
 * @param residualValue valeur résiduelle (défaut 0)
 * @param depreciationMethod méthode d'amortissement (défaut STRAIGHT_LINE)
 
 *
 * @author jo@Dev


*/
public record AddAssetComponentRequest(
 @NotBlank String code,
 @NotBlank String label,
 @NotNull @Positive BigDecimal acquisitionCost,
 @NotNull @Positive int usefulLifeYears,
 @PositiveOrZero BigDecimal residualValue,
 DepreciationMethod depreciationMethod
) {
 public AddAssetComponentRequest {
 if (residualValue == null) residualValue = BigDecimal.ZERO;
 if (depreciationMethod == null) depreciationMethod = DepreciationMethod.STRAIGHT_LINE;
 }
}

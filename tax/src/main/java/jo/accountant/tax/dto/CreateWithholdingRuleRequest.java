package jo.accountant.tax.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import jo.accountant.core.tax.WithholdingBracketType;

/**
 * Création d'une règle de retenue à la source.
 *
 * <p><b>Finding #14 — barème progressif</b> : par défaut, la règle utilise un taux unique
 * ({@code bracketType = FLAT}) et le champ {@code rate} est obligatoire. Pour un barème
 * progressif (PAS FR), passer {@code bracketType = PROGRESSIVE} et {@code brackets}
 * (une liste de {@code {threshold, rate}}) ; le {@code rate} reste obligatoire pour la
 * rétro-compatibilité (valeur ignorée en PROGRESSIVE côté calcul).
 */
public record CreateWithholdingRuleRequest(
    @NotBlank String code,
    @NotBlank String label,
    @NotNull @PositiveOrZero BigDecimal rate,
    List<String> applicableThirdPartyTypes,
    WithholdingBracketType bracketType,
    List<Bracket> brackets
) {
    /**
     * Constructeur de commodité pour la rétro-compatibilité — utilise {@link WithholdingBracketType#FLAT}
     * (comportement historique avant V46). Les appelants qui veulent un barème progressif doivent
     * utiliser le constructeur canonique à 6 arguments.
     */
    public CreateWithholdingRuleRequest(String code, String label, BigDecimal rate,
                                        List<String> applicableThirdPartyTypes) {
        this(code, label, rate, applicableThirdPartyTypes, WithholdingBracketType.FLAT, null);
    }

    /**
     * Définition d'une tranche du barème progressif — Finding #14.
     *
     * @param threshold plafond inférieur de la tranche (inclus), en montant de base
     * @param rate      taux applicable à la part de la base comprise entre ce threshold
     *                  et le suivant (en %, ex: 10 pour 10%)
     */
    public record Bracket(BigDecimal threshold, BigDecimal rate) {}
}

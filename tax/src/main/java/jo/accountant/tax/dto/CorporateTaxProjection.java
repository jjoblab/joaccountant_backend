package jo.accountant.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Projection d'Impôt sur les Sociétés (IS) — audit v4.7 §4.1 Finding #4.
 *
 * <p>Calcul en 4 étapes :
 * <ol>
 *   <li>{@link #accountingResult} — résultat comptable (depuis le CR)</li>
 *   <li>{@link #taxableResult} — résultat fiscal = résultat comptable
 *       + {@link ExtraComptableAdjustment#additions}
 *       − {@link ExtraComptableAdjustment#deductions}</li>
 *   <li>{@link #corporateTaxBrut} — IS brut = résultat fiscal × taux appliqué (15% ou 25%)</li>
 *   <li>{@link #corporateTaxNet} — IS net = IS brut − {@link #taxCredits}</li>
 * </ol>
 *
 * <p>Les acomptes (4 par an en France : mars, juin, septembre, décembre) sont calculés
 * sur l'IS N-1. Le solde est versé au plus tard le 15 mai N+1.
 */
public record CorporateTaxProjection(
    UUID companyId,
    LocalDate from,
    LocalDate to,
    BigDecimal accountingResult,
    ExtraComptableAdjustments adjustments,
    BigDecimal taxableResult,
    BigDecimal appliedRate,
    BigDecimal corporateTaxBrut,
    BigDecimal taxCredits,
    BigDecimal corporateTaxNet,
    List<Installment> installments,  // acomptes à verser sur l'exercice
    BigDecimal balanceDue,           // solde à verser au 15 mai N+1
    CorporateTaxRuleSummary rule
) {

    /**
     * Réintégrations et déductions extra-comptables pour passer du résultat comptable
     * au résultat fiscal.
     *
     * <p>Audit v4.7 §4.1 — amendement Charasse (réintégration 5/105 des quotes-parts de frais
     * financiers sur dividendes/CEC), amendements divers, plus-values LTPE exonérées à 80%, etc.
     */
    public record ExtraComptableAdjustments(
        BigDecimal charasseAddition,         // réintégration 5/105 quotes-parts frais financiers
        BigDecimal otherAdditions,           // autres réintégrations (amendes, etc.)
        BigDecimal longTermCapitalGainDeduction,  // plus-values LTPE déduites (80%)
        BigDecimal otherDeductions,
        BigDecimal totalAdditions,           // = charasseAddition + otherAdditions
        BigDecimal totalDeductions           // = longTermCapitalGainDeduction + otherDeductions
    ) {}

    /** Acompte IS à verser (4 par an en France). */
    public record Installment(
        LocalDate dueDate,
        BigDecimal amount,
        String label  // ex: "Acompte 1er trimestre 2026"
    ) {}

    /** Résumé de la règle d'IS appliquée. */
    public record CorporateTaxRuleSummary(
        BigDecimal standardRate,
        BigDecimal reducedRate,
        BigDecimal reducedRateThreshold,
        String eligibility  // SME / LARGE / UNKNOWN
    ) {}
}

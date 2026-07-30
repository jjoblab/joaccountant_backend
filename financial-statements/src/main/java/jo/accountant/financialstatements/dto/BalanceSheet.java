package jo.accountant.financialstatements.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bilan — {@code GET .../financial-statements/balance-sheet?asOf=}.
 *
 * <p>Invariant : {@code totalAssets == totalLiabilities + totalEquity}.
 *
 * <p>Le résultat net de la période (Produits − Charges) est intégré aux capitaux propres
 * si l'utilisateur a posté les écritures de clôture (report à nouveau / résultat de
 * l'exercice) avant de générer le bilan. Sinon, le bilan peut être déséquilibré — c'est
 * attendu tant que l'exercice n'est pas clôturé.
 *
 * <p><b>Task v6-4-presentation-currency</b> : champs optionnels de conversion de devise de
 * présentation. Si {@code presentationCurrency} est non null, le bilan a été converti depuis
 * la devise fonctionnelle ({@code functionalCurrency}) au {@code conversionRate} (taux de
 * clôture IAS 21 à la {@code conversionRateDate}). Si null, le bilan est en devise fonctionnelle
 * (comportement v5.5 inchangé).
 *
 * <p><b>V74 — v7-3</b> : {@code ctaAmount} (Cumulative Translation Adjustment) est calculé
 * lorsque le bilan est converti dans une devise de présentation. Il isole l'écart de
 * conversion en capitaux propres (IAS 21) : CTA = totalAssetsPresentation −
 * totalLiabilitiesPresentation − totalEquityFunctionalConverted. Si pas de conversion,
 * {@code ctaAmount} est null.
 */
public record BalanceSheet(
    UUID companyId,
    LocalDate asOf,
    List<Section> assets,
    List<Section> liabilities,
    List<Section> equity,
    BigDecimal totalAssets,
    BigDecimal totalLiabilities,
    BigDecimal totalEquity,
    boolean balanced,
    String presentationCurrency,
    String functionalCurrency,
    BigDecimal conversionRate,
    LocalDate conversionRateDate,
    String conversionType,
    BigDecimal ctaAmount
) {

    /**
     * Constructeur backward-compat (v5.5) — équivalent à un bilan en devise fonctionnelle
     * sans conversion. Les champs de conversion sont null, et ctaAmount est null
     * (pas de conversion → pas de CTA).
     */
    public BalanceSheet(UUID companyId,
                        LocalDate asOf,
                        List<Section> assets,
                        List<Section> liabilities,
                        List<Section> equity,
                        BigDecimal totalAssets,
                        BigDecimal totalLiabilities,
                        BigDecimal totalEquity,
                        boolean balanced) {
        this(companyId, asOf, assets, liabilities, equity,
             totalAssets, totalLiabilities, totalEquity, balanced,
             null, null, null, null, null, null);
    }

    /**
     * Constructeur backward-compat (v6-4) — sans CTA. Délègue au constructeur canonique
     * avec ctaAmount = null.
     */
    public BalanceSheet(UUID companyId,
                        LocalDate asOf,
                        List<Section> assets,
                        List<Section> liabilities,
                        List<Section> equity,
                        BigDecimal totalAssets,
                        BigDecimal totalLiabilities,
                        BigDecimal totalEquity,
                        boolean balanced,
                        String presentationCurrency,
                        String functionalCurrency,
                        BigDecimal conversionRate,
                        LocalDate conversionRateDate,
                        String conversionType) {
        this(companyId, asOf, assets, liabilities, equity,
             totalAssets, totalLiabilities, totalEquity, balanced,
             presentationCurrency, functionalCurrency, conversionRate,
             conversionRateDate, conversionType, null);
    }

    /** Section du bilan (ex. "ACTIF_COURANT", "PASSIF_NON_COURANT", "CAPITAUX_PROPRES"). */
    public record Section(
        String reportingClass,
        String reportingSubcategory,
        List<Line> lines,
        BigDecimal subtotal
    ) {}

    /** Ligne du bilan (un compte). */
    public record Line(
        UUID accountId,
        String accountCode,
        String accountLabel,
        BigDecimal amount
    ) {}
}

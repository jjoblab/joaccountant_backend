package jo.accountant.financialstatements.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * V84 — v7-2 : Statement of Changes in Equity (IAS 1.106).
 *
 * <p>Tableau de variation des capitaux propres entre deux dates, conforme à IAS 1.
 * Le tableau présente la réconciliation suivante :
 * <pre>
 *   Capitaux propres d'ouverture (à from - 1)
 *   + Résultat net de l'exercice (from → to)
 *   + Other Comprehensive Income (OCI)
 *   + Émissions de capital
 *   − Rachats d'actions (treasury shares)
 *   − Dividendes distribués
 *   ± Autres mouvements
 *   = Capitaux propres de clôture (à to)
 * </pre>
 *
 * <p>Détail des mouvements (liste {@link #movements}) fourni pour transparence — permet à
 * l'audit de retracer chaque variation. Chaque mouvement est catégorisé (CAPITAL_ISSUED,
 * TREASURY_PURCHASE, DIVIDEND, OCI, OTHER).
 *
 * <p><b>Conversion de devise</b> : optionnelle. Si {@code presentationCurrency} est non null,
 * les soldes sont convertis depuis la devise fonctionnelle ({@code functionalCurrency}) au
 * taux de clôture (IAS 21 — soldes au taux de clôture). Le {@code conversionRate} est exposé
 * pour information.
 */
public record StatementOfChangesInEquity(
    UUID companyId,
    LocalDate from,
    LocalDate to,
    BigDecimal openingEquity,
    BigDecimal netIncome,
    BigDecimal otherComprehensiveIncome,
    BigDecimal capitalIssued,
    BigDecimal treasurySharesPurchased,
    BigDecimal dividendsDistributed,
    BigDecimal otherMovements,
    BigDecimal closingEquity,
    List<EquityMovement> movements,
    String functionalCurrency,
    String presentationCurrency,
    BigDecimal conversionRate
) {

    /**
     * Vérification de cohérence : openingEquity + netIncome + OCI + capitalIssued
     * − treasuryShares − dividends + otherMovements ≈ closingEquity (à 1 unité près).
     *
     * <p>En pratique, l'écart peut être non nul si des écritures sont postées avec des
     * dates frontières (ex: écriture postée exactement à {@code from} mais avant l'inventaire
     * d'ouverture). L'appelant peut utiliser cette méthode pour détecter un bug de calcul.
     */
    public BigDecimal reconciliationGap() {
        return closingEquity
            .subtract(openingEquity)
            .subtract(netIncome)
            .subtract(otherComprehensiveIncome)
            .subtract(capitalIssued)
            .add(treasurySharesPurchased)
            .add(dividendsDistributed)
            .subtract(otherMovements);
    }

    /**
     * Un mouvement individuel de capitaux propres (pour le détail/audit).
     *
     * @param date        date comptable de l'écriture
     * @param description description (reprise du libellé de l'écriture)
     * @param accountCode code du compte mouvement (101, 109, 455, 108, etc.)
     * @param debit       montant débit (0 si mouvement crédit)
     * @param credit      montant crédit (0 si mouvement débit)
     * @param category    catégorie : CAPITAL_ISSUED, TREASURY_PURCHASE, DIVIDEND, OCI, OTHER
     */
    public record EquityMovement(
        LocalDate date,
        String description,
        String accountCode,
        BigDecimal debit,
        BigDecimal credit,
        String category
    ) {}
}

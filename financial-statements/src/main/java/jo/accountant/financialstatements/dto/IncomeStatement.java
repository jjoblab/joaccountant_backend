package jo.accountant.financialstatements.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Compte de résultat — {@code GET .../financial-statements/income-statement?from=&to=}.
 *
 * <p>Invariant : {@code netResult == totalProducts - totalCharges}.
 *
 * <p><b></b> : champs optionnels de conversion de devise de
 * présentation. Si {@code presentationCurrency} est non null, le CR a été converti depuis la
 * devise fonctionnelle au taux moyen de période (IAS 21 — flux au taux moyen). Si null, le CR
 * est en devise fonctionnelle (comportement inchangé).
 
 *
 * @author jo@Dev


*/
public record IncomeStatement(
    UUID companyId,
    LocalDate from,
    LocalDate to,
    List<Section> products,
    List<Section> charges,
    BigDecimal totalProducts,
    BigDecimal totalCharges,
    BigDecimal netResult,
    String presentationCurrency,
    String functionalCurrency,
    BigDecimal conversionRate,
    LocalDate conversionRateDate,
    String conversionType
) {

    /**
     * Constructeur backward-compat — équivalent à un CR en devise fonctionnelle sans
     * conversion. Les champs de conversion sont null.
     */
    public IncomeStatement(UUID companyId,
                           LocalDate from,
                           LocalDate to,
                           List<Section> products,
                           List<Section> charges,
                           BigDecimal totalProducts,
                           BigDecimal totalCharges,
                           BigDecimal netResult) {
        this(companyId, from, to, products, charges,
             totalProducts, totalCharges, netResult,
             null, null, null, null, null);
    }

    /** Section du compte de résultat (ex. "PRODUITS_COURANTS", "CHARGES_NON_COURANTES"). */
    public record Section(
        String reportingClass,
        String reportingSubcategory,
        List<Line> lines,
        BigDecimal subtotal
    ) {}

    /** Ligne du compte de résultat (un compte). */
    public record Line(
        UUID accountId,
        String accountCode,
        String accountLabel,
        BigDecimal amount
    ) {}
}

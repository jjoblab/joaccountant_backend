package jo.accountant.financialstatements.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tableau de flux de trésorerie (IAS 7 / SYSCOHADA TAFIRE) —.
 *
 * <p>Méthode indirecte : on part du résultat net, on ajuste des éléments non monétaires
 * (amortissements, plus/moins-values de cession), on corrige des variations du BFR, puis on
 * isole les flux d'investissement et de financement.
 *
 * <p>Structure :
 * <pre>
 * Flux de trésorerie des activités d'exploitation
 * Résultat net
 * + Amortissements et dépréciations
 * ± Variation des créances clients
 * ± Variation des stocks
 * ± Variation des fournisseurs et autres dettes d'exploitation
 * = Flux net d'exploitation
 *
 * Flux de trésorerie des activités d'investissement
 * − Acquisitions d'immobilisations
 * + Cessions d'immobilisations (prix de cession)
 * = Flux net d'investissement
 *
 * Flux de trésorerie des activités de financement
 * + Augmentations de capital
 * ± Variation des emprunts
 * − Dividendes versés
 * = Flux net de financement
 *
 * Variation de trésorerie = Flux exploitation + Flux investissement + Flux financement
 * Trésorerie ouverture + Variation = Trésorerie clôture
 * </pre>
 *
 * <p><b>Limitation connue</b> : la distinction précise entre flux d'investissement et flux de
 * financement dépend de conventions comptables (ex: un emprunt pour acheter un véhicule est
 * classé en investissement pour la partie actif et en financement pour la partie passif).
 * L'implémentation actuelle utilise une heuristique basée sur les ReportingClass et les
 * codes de compte (1xxx = financement, 2xxx = investissement, 4xx/5xx = exploitation).
 * À affiner avec un mapping explicite par compte.
 
 *
 * @author jo@Dev


*/
public record CashFlowStatement(
 UUID companyId,
 LocalDate from,
 LocalDate to,
 BigDecimal netIncome,
 OperatingFlows operating,
 InvestingFlows investing,
 FinancingFlows financing,
 BigDecimal netCashFlow, // = operating.total + investing.total + financing.total
 BigDecimal openingCash, // trésorerie à la date `from - 1`
 BigDecimal closingCash, // trésorerie à la date `to`
 boolean balanced, // closingCash == openingCash + netCashFlow
 // — champs de conversion de devise de présentation
 String presentationCurrency,
 String functionalCurrency,
 BigDecimal conversionRate,
 LocalDate conversionRateDate,
 String conversionType
) {

 /**
 * Constructeur backward-compat — sans conversion de devise.
 */
 public CashFlowStatement(UUID companyId,
 LocalDate from,
 LocalDate to,
 BigDecimal netIncome,
 OperatingFlows operating,
 InvestingFlows investing,
 FinancingFlows financing,
 BigDecimal netCashFlow,
 BigDecimal openingCash,
 BigDecimal closingCash,
 boolean balanced) {
 this(companyId, from, to, netIncome, operating, investing, financing,
 netCashFlow, openingCash, closingCash, balanced,
 null, null, null, null, null);
 }

 public record OperatingFlows(
 BigDecimal netIncome,
 BigDecimal depreciationAmortization, // amortissements et dépréciations (non-monetaries)
 BigDecimal accountsReceivableVariation,
 BigDecimal inventoryVariation,
 BigDecimal accountsPayableVariation,
 BigDecimal otherWorkingCapitalVariation,
 BigDecimal total
 ) {}

 public record InvestingFlows(
 BigDecimal fixedAssetsAcquisitions,
 BigDecimal fixedAssetsDisposals,
 BigDecimal otherInvestingFlows,
 BigDecimal total // négatif en général (acquisitions nettes)
 ) {}

 public record FinancingFlows(
 BigDecimal capitalVariation,
 BigDecimal loansVariation,
 BigDecimal dividendsPaid,
 BigDecimal otherFinancingFlows,
 BigDecimal total
 ) {}
}

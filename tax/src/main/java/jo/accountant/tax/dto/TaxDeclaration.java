package jo.accountant.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Déclaration fiscale par période (§13.
 *
 * <p>Agrégation par taux de TVA des factures émises (TVA collectée) ET reçues (TVA déductible)
 * sur la période. La TVA due = TVA collectée − TVA déductible (art. 286 CGI).
 *
 * <p><b>la version originale n'agrégeait que
 * la TVA collectée (côté ventes). La TVA déductible (côté achats) et la TVA due n'étaient pas
 * calculées, rendant la déclaration inutilisable (l'entreprise ne savait pas combien elle devait
 * réellement). Désormais, le DTO contient les 3 volets + crédit de TVA reporté.
 *
 * <p>Export déclaratif simple — pas d'intégration de télédéclaration en v1.
 
 *
 * @author jo@Dev


*/
public record TaxDeclaration(
 UUID companyId,
 LocalDate from,
 LocalDate to,
 List<TaxLine> collectedLines,
 List<TaxLine> deductibleLines,
 BigDecimal totalTaxCollected,
 BigDecimal totalTaxDeductible,
 BigDecimal taxCreditCarriedForward,
 BigDecimal taxDue,
 BigDecimal taxCreditToCarryForward
) {
 /**
 * @deprecated utiliser le constructeur complet avec TVA déductible. Conservé pour
 * backward-compat pendant la migration des callers — sera supprimé .
 */
 @Deprecated
 public TaxDeclaration(UUID companyId, LocalDate from, LocalDate to,
 List<TaxLine> lines, BigDecimal totalTaxCollected) {
 this(companyId, from, to, lines, List.of(), totalTaxCollected, BigDecimal.ZERO,
 BigDecimal.ZERO, totalTaxCollected, BigDecimal.ZERO);
 }

 public record TaxLine(
 String taxCode, String taxLabel, BigDecimal rate,
 BigDecimal taxableBase, BigDecimal taxAmount
 ) {}
}

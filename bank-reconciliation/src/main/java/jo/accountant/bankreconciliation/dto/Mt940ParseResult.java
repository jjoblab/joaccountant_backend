package jo.accountant.bankreconciliation.dto;

import java.math.BigDecimal;
import java.util.List;
import jo.accountant.bankreconciliation.entity.BankStatementLine;

/**
 * Résultat du parsing d'un fichier MT940 SWIFT (Parser MT940).
 *
 * <p>Contient les informations niveau relevé + les lignes de transaction parsées. Les lignes
 * retournées ne sont pas encore persistées — l'appelant (typiquement
 * {@code BankReconciliationService.importStatement}) les hydrate avec {@code importId},
 * {@code bankAccountId}, {@code companyId} avant persistance.
 *
 * @param account numéro de compte bancaire (tag :25:), null si absent du fichier
 * @param openingBalance solde d'ouverture (tag :60F:), null si absent. Signe : positif pour un
 * solde créditeur, négatif pour un solde débiteur.
 * @param closingBalance solde de clôture (tag :62F:), null si absent. Même convention de signe.
 * @param lines lignes de transaction (une par tag :61:), potentiellement enrichies
 * avec les détails du tag :86: suivant dans la description
 
 *
 * @author jo@Dev


*/
public record Mt940ParseResult(
 String account,
 BigDecimal openingBalance,
 BigDecimal closingBalance,
 List<BankStatementLine> lines
) {}

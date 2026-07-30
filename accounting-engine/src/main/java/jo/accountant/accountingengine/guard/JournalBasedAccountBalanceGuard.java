package jo.accountant.accountingengine.guard;

import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.chartofaccounts.guard.AccountBalanceGuard;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Implémentation réelle de {@link AccountBalanceGuard} (Phase 5).
 *
 * <p>Remplace {@code DefaultAccountBalanceGuard} de Phase 3 qui retournait toujours false.
 * Cette implémentation interroge {@link JournalLineRepository} pour calculer le solde
 * d'un compte : somme des débits − somme des crédits.
 *
 * <p>Annotée {@link Primary} pour gagner l'injection quand deux beans
 * {@link AccountBalanceGuard} coexistent temporairement (le défaut de Phase 3 + celui-ci).
 * En pratique, on supprimera le défaut dans une migration de code ultérieure — pour
 * l'instant on garde les deux pour minimiser le risque de casser Phase 3.
 */
@Component
@Primary
public class JournalBasedAccountBalanceGuard implements AccountBalanceGuard {

    private final JournalLineRepository journalLineRepository;

    public JournalBasedAccountBalanceGuard(JournalLineRepository journalLineRepository) {
        this.journalLineRepository = journalLineRepository;
    }

    @Override
    public boolean hasNonZeroBalance(UUID companyId, UUID accountId) {
        BigDecimal debit = journalLineRepository.sumDebitByCompanyIdAndAccountId(companyId, accountId);
        BigDecimal credit = journalLineRepository.sumCreditByCompanyIdAndAccountId(companyId, accountId);
        BigDecimal balance = debit.subtract(credit);
        return balance.compareTo(BigDecimal.ZERO) != 0;
    }

    /**
     * Audit v4.7 §3.2 Finding MOYENNE — FIX : vérifie si le solde est négatif (anormal).
     *
     * <p>Un solde négatif signifie : pour un compte ACTIF (normalBalance=DEBIT), le crédit
     * dépasse le débit (ex: client avec un avoir > factures) ; pour un compte PASSIF
     * (normalBalance=CREDIT), le débit dépasse le crédit (ex: fournisseur avec un règlement
     * > factures reçues). Ces situations peuvent être légitimes (avance client/fournisseur)
     * mais méritent une alerte pour éviter les bugs silencieux.
     */
    @Override
    public boolean hasNegativeBalance(UUID companyId, UUID accountId) {
        BigDecimal debit = journalLineRepository.sumDebitByCompanyIdAndAccountId(companyId, accountId);
        BigDecimal credit = journalLineRepository.sumCreditByCompanyIdAndAccountId(companyId, accountId);
        BigDecimal balance = debit.subtract(credit);
        // Solde négatif = crédit > débit (balance < 0). Pour un ACTIF (normalBalance=DEBIT),
        // un solde créditeur est anormal. Pour un PASSIF (normalBalance=CREDIT), un solde
        // débiteur (balance > 0) est anormal — mais cette méthode vérifie uniquement < 0.
        // L'appelant doit interpréter selon le normalBalance du compte.
        return balance.compareTo(BigDecimal.ZERO) < 0;
    }
}

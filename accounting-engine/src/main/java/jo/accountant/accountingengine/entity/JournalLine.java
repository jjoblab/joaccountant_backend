package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne d'écriture comptable (§13.
 *
 * <p>Une ligne porte soit un débit, soit un crédit — jamais les deux. La convention est
 * que {@link #debit} et {@link #credit} sont mutuellement exclusifs (l'un est 0 quand
 * l'autre est non-nul). Vérifié par CHECK DB.
 *
 * <p>Montants en devise fonctionnelle uniquement pour cette itération. Les champs
 * {@code amountTransactionCurrency}, {@code transactionCurrency}, {@code exchangeRateUsed}
 * sont posés (§3.5) mais la conversion n'est pas implémentée enles lignes sont
 * saisies directement en devise fonctionnelle.(probablementou
 * 13) activera le multi-devises.
 *
 * <p>Référence un {@link jo.accountant.chartofaccounts.entity.Account compte} du plan
 * comptable via {@link #accountId} — pas de FK dure pour permettre la désactivation d'un
 * compte sans casser les écritures historiques.
 *
 * <p>{@link #thirdPartyId} est nullable — sera renseigné par({@code third-parties}).
 * Pour l'instant c'est juste un champ libre (UUID opaque).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "journal_line")
public class JournalLine extends TenantAwareEntity {

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Code du compte (snapshot au moment de l'écriture — pour audit même si le compte est renommé). */
    @Column(name = "account_code", nullable = false, length = 30)
    private String accountCode;

    @Column(name = "third_party_id")
    private UUID thirdPartyId;

    @Column(name = "debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(name = "credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal credit = BigDecimal.ZERO;

    /** Numéro de ligne dans l'écriture — pour l'ordre d'affichage. */
    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * Montant en devise de transaction (§3.5). En, égal à {@link #debit} ou
     * {@link #credit} (pas de multi-devises).
     */
    @Column(name = "amount_transaction_currency", precision = 19, scale = 4)
    private BigDecimal amountTransactionCurrency;

    /** Code ISO 4217 de la devise de transaction. En, égal à la devise fonctionnelle. */
    @Column(name = "transaction_currency", length = 3)
    private String transactionCurrency;

    /** Taux de change utilisé pour la conversion. En, toujours 1. */
    @Column(name = "exchange_rate_used", precision = 19, scale = 6)
    private BigDecimal exchangeRateUsed = BigDecimal.ONE;

    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public String getAccountCode() { return accountCode; }
    public void setAccountCode(String accountCode) { this.accountCode = accountCode; }

    public UUID getThirdPartyId() { return thirdPartyId; }
    public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }

    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getAmountTransactionCurrency() { return amountTransactionCurrency; }
    public void setAmountTransactionCurrency(BigDecimal amountTransactionCurrency) {
        this.amountTransactionCurrency = amountTransactionCurrency;
    }

    public String getTransactionCurrency() { return transactionCurrency; }
    public void setTransactionCurrency(String transactionCurrency) {
        this.transactionCurrency = transactionCurrency;
    }

    public BigDecimal getExchangeRateUsed() { return exchangeRateUsed; }
    public void setExchangeRateUsed(BigDecimal exchangeRateUsed) {
        this.exchangeRateUsed = exchangeRateUsed;
    }
}

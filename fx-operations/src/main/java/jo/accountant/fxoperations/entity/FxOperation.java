package jo.accountant.fxoperations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Opération en devise étrangère (3 — module :fx-operations).
 *
 * <p>Une opération peut être :
 * <ul>
 * <li><b>BUY</b> — achat de devise étrangère contre devise fonctionnelle.
 * L'utilisateur vend {@code fromAmount} de {@code fromCurrency} (ex. HTG) pour acheter
 * {@code toAmount} de {@code toCurrency} (ex. USD) au taux {@code rate}.</li>
 * <li><b>SELL</b> — vente de devise étrangère contre devise fonctionnelle.
 * L'utilisateur vend {@code fromAmount} de {@code fromCurrency} (ex. USD) pour acheter
 * {@code toAmount} de {@code toCurrency} (ex. HTG) au taux {@code rate}.</li>
 * <li><b>REVALUATION</b> — réévaluation de fin de période des soldes en devises
 * étrangères au taux de clôture. Génère un gain ou une perte de change latent.</li>
 * </ul>
 *
 * <p>Les montants en devise fonctionnelle ({@code fromAmountFunctional} et
 * {@code toAmountFunctional}) sont calculés via {@code ExchangeRateService.convert()}.
 * Si {@code fromAmountFunctional != toAmountFunctional}, la différence est un gain ou une
 * perte de change ({@code fxGainLoss}).
 *
 * <p>L'écriture comptable générée :
 * <ul>
 * <li>BUY : D 521-{toCurrency} (toAmountFunctional) / C 521-{fromCurrency} (fromAmountFunctional)
 * + si gain : C 776 Produits de change ; si perte : D 676 Charges de change.</li>
 * <li>SELL : symétrique.</li>
 * <li>REVALUATION : D/C 521-{currency} / C/D 776 ou 676 selon le sens du gain/perte latent.</li>
 * </ul>
 *
 * <p>Le compte de trésorerie par devise est résolu par convention : on cherche un compte
 * ACTIF marqué {@code taxMappingCode = "CASH"} (le 521 Banque standard). En SYSCOHADA, les
 * comptes en devises étrangères sont généralement créés en sous-comptes (521-USD, 521-EUR),
 * mais la résolution ici utilise le 521 unique pour rester simple au MVP. À affiner en
 * production avec une convention de mapping compte ↔ devise.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "fx_operation")
public class FxOperation extends TenantAwareEntity {

 @Enumerated(EnumType.STRING)
 @Column(name = "type", nullable = false, length = 15)
 private FxOperationType type;

 @Column(name = "from_currency", nullable = false, length = 3)
 private String fromCurrency;

 @Column(name = "to_currency", nullable = false, length = 3)
 private String toCurrency;

 @Column(name = "from_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal fromAmount;

 @Column(name = "to_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal toAmount;

 /** Taux appliqué : 1 unité fromCurrency = rate unités toCurrency. */
 @Column(name = "rate", nullable = false, precision = 19, scale = 6)
 private BigDecimal rate;

 /** fromAmount converti en devise fonctionnelle. */
 @Column(name = "from_amount_functional", nullable = false, precision = 19, scale = 4)
 private BigDecimal fromAmountFunctional;

 /** toAmount converti en devise fonctionnelle. */
 @Column(name = "to_amount_functional", nullable = false, precision = 19, scale = 4)
 private BigDecimal toAmountFunctional;

 /** Gain (>0) ou perte (<0) de change, en devise fonctionnelle. */
 @Column(name = "fx_gain_loss", nullable = false, precision = 19, scale = 4)
 private BigDecimal fxGainLoss = BigDecimal.ZERO;

 @Column(name = "operation_date", nullable = false)
 private LocalDate operationDate;

 @Column(name = "description", length = 500)
 private String description;

 /** ID de l'écriture comptable générée. */
 @Column(name = "journal_entry_id")
 private UUID journalEntryId;

 /** Si cette opération est contre-passée, pointe vers l'opération inverse générée. */
 @Column(name = "reversal_of_id")
 private UUID reversalOfId;

 @Enumerated(EnumType.STRING)
 @Column(name = "status", nullable = false, length = 15)
 private FxOperationStatus status = FxOperationStatus.POSTED;

 public FxOperationType getType() { return type; }
 public void setType(FxOperationType type) { this.type = type; }

 public String getFromCurrency() { return fromCurrency; }
 public void setFromCurrency(String fromCurrency) { this.fromCurrency = fromCurrency; }

 public String getToCurrency() { return toCurrency; }
 public void setToCurrency(String toCurrency) { this.toCurrency = toCurrency; }

 public BigDecimal getFromAmount() { return fromAmount; }
 public void setFromAmount(BigDecimal fromAmount) { this.fromAmount = fromAmount; }

 public BigDecimal getToAmount() { return toAmount; }
 public void setToAmount(BigDecimal toAmount) { this.toAmount = toAmount; }

 public BigDecimal getRate() { return rate; }
 public void setRate(BigDecimal rate) { this.rate = rate; }

 public BigDecimal getFromAmountFunctional() { return fromAmountFunctional; }
 public void setFromAmountFunctional(BigDecimal fromAmountFunctional) { this.fromAmountFunctional = fromAmountFunctional; }

 public BigDecimal getToAmountFunctional() { return toAmountFunctional; }
 public void setToAmountFunctional(BigDecimal toAmountFunctional) { this.toAmountFunctional = toAmountFunctional; }

 public BigDecimal getFxGainLoss() { return fxGainLoss; }
 public void setFxGainLoss(BigDecimal fxGainLoss) { this.fxGainLoss = fxGainLoss; }

 public LocalDate getOperationDate() { return operationDate; }
 public void setOperationDate(LocalDate operationDate) { this.operationDate = operationDate; }

 public String getDescription() { return description; }
 public void setDescription(String description) { this.description = description; }

 public UUID getJournalEntryId() { return journalEntryId; }
 public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

 public UUID getReversalOfId() { return reversalOfId; }
 public void setReversalOfId(UUID reversalOfId) { this.reversalOfId = reversalOfId; }

 public FxOperationStatus getStatus() { return status; }
 public void setStatus(FxOperationStatus status) { this.status = status; }
}

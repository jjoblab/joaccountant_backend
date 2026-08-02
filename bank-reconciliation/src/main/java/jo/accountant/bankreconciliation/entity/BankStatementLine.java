package jo.accountant.bankreconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne de relevé bancaire (§13.
 *
 * <p>Chaque ligne est issue du parsing d'un fichier de relevé (CSV ou OFX). Elle peut être
 * dans 2 états :
 * <ul>
 * <li>Non rapprochée : {@code matched = false}, {@code matchedJournalLineId = null}</li>
 * <li>Rapprochée : {@code matched = true}, {@code matchedJournalLineId} référence la
 * {@link jo.accountant.accountingengine.entity.JournalLine} correspondante</li>
 * </ul>
 *
 * <p>Le rapprochement peut être automatique (montant + date exacte, puis correspondance floue
 * sur libellé) ou manuel (l'utilisateur valide la correspondance).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "bank_statement_line")
public class BankStatementLine extends TenantAwareEntity {

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Column(name = "line_date", nullable = false)
    private LocalDate lineDate;

    /** Montant — positif pour un crédit (entrée), négatif pour un débit (sortie). */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "matched", nullable = false)
    private boolean matched = false;

    /** ID de la JournalLine rapprochée. Null si non rapprochée. */
    @Column(name = "matched_journal_line_id")
    private UUID matchedJournalLineId;

    @Column(name = "matched_at")
    private java.time.Instant matchedAt;

    public UUID getImportId() { return importId; }
    public void setImportId(UUID importId) { this.importId = importId; }

    public UUID getBankAccountId() { return bankAccountId; }
    public void setBankAccountId(UUID bankAccountId) { this.bankAccountId = bankAccountId; }

    public LocalDate getLineDate() { return lineDate; }
    public void setLineDate(LocalDate lineDate) { this.lineDate = lineDate; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isMatched() { return matched; }
    public void setMatched(boolean matched) { this.matched = matched; }

    public UUID getMatchedJournalLineId() { return matchedJournalLineId; }
    public void setMatchedJournalLineId(UUID matchedJournalLineId) { this.matchedJournalLineId = matchedJournalLineId; }

    public java.time.Instant getMatchedAt() { return matchedAt; }
    public void setMatchedAt(java.time.Instant matchedAt) { this.matchedAt = matchedAt; }
}

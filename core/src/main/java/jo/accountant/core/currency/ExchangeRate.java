package jo.accountant.core.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Taux de change entre devises (§3.5, Vague 2 item 2.5).
 *
 * <p>Un taux par (companyId, fromCurrency, toCurrency, asOfDate). Le taux est direct
 * (fromCurrency → toCurrency), pas inversé. Si on a EUR→USD mais qu'on a besoin de USD→EUR,
 * on calcule l'inverse : 1/rate.
 */
@Entity
@Table(name = "exchange_rate",
    uniqueConstraints = @UniqueConstraint(name = "uc_er_company_from_to_date",
        columnNames = {"company_id", "from_currency", "to_currency", "as_of_date"}))
/**
 * ExchangeRate.
 *
 * @author jo@Dev


 */

public class ExchangeRate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    /** Taux de change : 1 unité fromCurrency = rate unités toCurrency. */
    @Column(name = "rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** Source du taux (ex. "Banque Nationale", "BCEAO", "manuel"). */
    @Column(name = "source", length = 100)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }
    public String getFromCurrency() { return fromCurrency; }
    public void setFromCurrency(String fromCurrency) { this.fromCurrency = fromCurrency; }
    public String getToCurrency() { return toCurrency; }
    public void setToCurrency(String toCurrency) { this.toCurrency = toCurrency; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

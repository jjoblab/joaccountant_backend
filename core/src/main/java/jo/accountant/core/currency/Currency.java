package jo.accountant.core.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Données de référence des devises ISO 4217 (§3.5).
 *
 * <p>{@code decimals} pilote l'échelle de tout montant {@link java.math.BigDecimal} libellé dans
 * cette devise. HTG = 2, XOF = 0 (franc CFA), JPY = 0.
 *
 * <p>Seed-only — non modifiable par les utilisateurs.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "currency")
public class Currency {

    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 3)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "decimals", nullable = false)
    private int decimals;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Currency() {}

    public Currency(String code, String label, int decimals) {
        this.code = code;
        this.label = label;
        this.decimals = decimals;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public int getDecimals() { return decimals; }
    public void setDecimals(int decimals) { this.decimals = decimals; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

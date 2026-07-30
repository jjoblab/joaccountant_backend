package jo.accountant.fxoperations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Snapshot de taux de change pour la présentation des états financiers en devise de présentation
 * (Task v6-4-presentation-currency).
 *
 * <p>Stocke le taux officiel (typiquement BRH — Banque de la République d'Haïti) à utiliser pour
 * convertir les états financiers de la devise fonctionnelle (ex. USD pour une ONG haïtienne ou
 * une société en zone franche) vers la devise de présentation (ex. HTG pour la DCR DGI annuelle).
 *
 * <p>Deux types de snapshot (colonne {@code snapshot_type}) :
 * <ul>
 *   <li><b>CLOSING</b> — taux à la date de clôture. Utilisé pour le bilan (IAS 21 — solde à la clôture).
 *       {@code periodYear} et {@code periodMonth} sont NULL.</li>
 *   <li><b>PERIOD_AVERAGE</b> — taux moyen sur une période. Utilisé pour le compte de résultat
 *       et le tableau de flux de trésorerie (IAS 21 — flux à la moyenne). {@code periodYear} et
 *       {@code periodMonth} sont renseignés (mois 1-12).</li>
 * </ul>
 *
 * <p><b>Conformité IAS 21</b> : la présente table permet d'appliquer les deux conventions de
 * conversion de la norme IAS 21 (effets des variations des cours des monnaies étrangères) :
 * taux de clôture pour le bilan (postes monétaires et non-monétaires au coût historique en devise),
 * taux moyen pour le compte de résultat.
 *
 * <p><b>Limitation v6-4</b> : pas encore de cumul de translation adjustment (CTA) en capitaux
 * propres — planifié v7. La conversion v6 multiplie chaque solde par le taux sans isoler l'écart
 * de conversion.
 */
@Entity
@Table(name = "exchange_rate_snapshot")
public class ExchangeRateSnapshot extends TenantAwareEntity {

    /** Source typique : "BRH", "COMMERCIAL", "MANUAL". */
    public static final String SOURCE_BRH = "BRH";
    public static final String SOURCE_COMMERCIAL = "COMMERCIAL";
    public static final String SOURCE_MANUAL = "MANUAL";

    /** Type de snapshot : taux de clôture (bilan) ou taux moyen de période (CR / CF). */
    public static final String TYPE_CLOSING = "CLOSING";
    public static final String TYPE_PERIOD_AVERAGE = "PERIOD_AVERAGE";

    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    /** Taux : 1 unité fromCurrency = rate unités toCurrency. */
    @Column(name = "rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "source", nullable = false, length = 50)
    private String source = SOURCE_BRH;

    @Column(name = "snapshot_type", nullable = false, length = 20)
    private String snapshotType = TYPE_CLOSING;

    /** Année fiscale (pour PERIOD_AVERAGE uniquement). */
    @Column(name = "period_year")
    private Integer periodYear;

    /** Mois 1-12 (pour PERIOD_AVERAGE uniquement). */
    @Column(name = "period_month")
    private Integer periodMonth;

    public String getFromCurrency() { return fromCurrency; }
    public void setFromCurrency(String fromCurrency) { this.fromCurrency = fromCurrency; }

    public String getToCurrency() { return toCurrency; }
    public void setToCurrency(String toCurrency) { this.toCurrency = toCurrency; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public LocalDate getRateDate() { return rateDate; }
    public void setRateDate(LocalDate rateDate) { this.rateDate = rateDate; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public Integer getPeriodYear() { return periodYear; }
    public void setPeriodYear(Integer periodYear) { this.periodYear = periodYear; }

    public Integer getPeriodMonth() { return periodMonth; }
    public void setPeriodMonth(Integer periodMonth) { this.periodMonth = periodMonth; }
}

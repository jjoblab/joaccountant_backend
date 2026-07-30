package jo.accountant.payroll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * V78 — v7-6 : Taux OFATMA Accidents par secteur d'activité (Loi OFATMA Haïti).
 *
 * <p>Le taux OFATMA Accidents varie de 0,5% à 6% selon le secteur d'activité de l'entreprise
 * (les secteurs à risque élevé comme la construction paient plus que les secteurs à faible
 * risque comme la banque).
 *
 * <p>Le {@code sectorCode} est stocké sur l'employé ({@code Employee.ofatmaSectorCode}) et
 * résolu par {@code PayrollCalculator.resolveOfatmaAccidentRate} lors du calcul de paie.
 *
 * <p>Si le sector_code est null/blank/inconnu, le taux par défaut 2,00% est utilisé (règle
 * OFATMA_HT_ACCIDENT existante en V57).
 */
@Entity
@Table(name = "ofatma_sector_rate")
public class OfatmaSectorRate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sector_code", nullable = false, unique = true, length = 10)
    private String sectorCode;

    @Column(name = "sector_label", nullable = false, length = 200)
    private String sectorLabel;

    @Column(name = "accident_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal accidentRate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSectorCode() { return sectorCode; }
    public void setSectorCode(String sectorCode) { this.sectorCode = sectorCode; }

    public String getSectorLabel() { return sectorLabel; }
    public void setSectorLabel(String sectorLabel) { this.sectorLabel = sectorLabel; }

    public BigDecimal getAccidentRate() { return accidentRate; }
    public void setAccidentRate(BigDecimal accidentRate) { this.accidentRate = accidentRate; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

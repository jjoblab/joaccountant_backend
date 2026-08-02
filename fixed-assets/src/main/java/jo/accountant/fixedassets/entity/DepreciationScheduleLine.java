package jo.accountant.fixedassets.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Ligne d'échéancier d'amortissement (§13.
 *
 * <p>Une ligne par période (mensuelle) de la durée de vie utile de l'actif. Générée
 * automatiquement à la création de l'actif — voir
 * {@link jo.accountant.fixedassets.service.FixedAssetsService#createAsset}.
 *
 * <p>Une ligne peut être dans 2 états :
 * <ul>
 * <li>Non postée : {@link #journalEntryId} = null, {@link #postedAt} = null. La ligne est
 * en attente de postage via
 * {@link jo.accountant.fixedassets.service.FixedAssetsService#postPeriodDepreciation}.</li>
 * <li>Postée : {@link #journalEntryId} référence l'écriture comptable générée,
 * {@link #postedAt} est non null. La ligne est immuable.</li>
 * </ul>
 *
 * <p>Unicité : un (assetId, periodId) ne peut avoir qu'une seule ligne. Vérifié par contrainte
 * DB unique.
 */
@Entity
@Table(name = "depreciation_schedule_line",
 uniqueConstraints = @UniqueConstraint(name = "uc_dsl_asset_period_component",
 columnNames = {"asset_id", "period_id", "component_id"}))
/**
 * DepreciationScheduleLine.
 *
 * @author jo@Dev


 */

public class DepreciationScheduleLine extends TenantAwareEntity {

 @Column(name = "asset_id", nullable = false)
 private UUID assetId;

 /**
 * Composant IAS 16 auquel se rattache cette ligne.
 * Null si l'amortissement est calculé globalement sur l'asset (pas de composants).
 */
 @Column(name = "component_id")
 private UUID componentId;

 @Column(name = "period_id", nullable = false)
 private UUID periodId;

 /** Date de la période (typiquement le 1er du mois). */
 @Column(name = "period_date", nullable = false)
 private java.time.LocalDate periodDate;

 /** Montant de l'amortissement pour cette période. */
 @Column(name = "amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal amount;

 /** Amortissement cumulé à la fin de cette période. */
 @Column(name = "cumulative_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal cumulativeAmount;

 /** ID de l'écriture comptable générée au postage. Null si non postée. */
 @Column(name = "journal_entry_id")
 private UUID journalEntryId;

 @Column(name = "posted_at")
 private Instant postedAt;

 @Column(name = "posted_by")
 private UUID postedBy;

 public UUID getAssetId() { return assetId; }
 public void setAssetId(UUID assetId) { this.assetId = assetId; }

 public UUID getComponentId() { return componentId; }
 public void setComponentId(UUID componentId) { this.componentId = componentId; }

 public UUID getPeriodId() { return periodId; }
 public void setPeriodId(UUID periodId) { this.periodId = periodId; }

 public java.time.LocalDate getPeriodDate() { return periodDate; }
 public void setPeriodDate(java.time.LocalDate periodDate) { this.periodDate = periodDate; }

 public BigDecimal getAmount() { return amount; }
 public void setAmount(BigDecimal amount) { this.amount = amount; }

 public BigDecimal getCumulativeAmount() { return cumulativeAmount; }
 public void setCumulativeAmount(BigDecimal cumulativeAmount) { this.cumulativeAmount = cumulativeAmount; }

 public UUID getJournalEntryId() { return journalEntryId; }
 public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

 public Instant getPostedAt() { return postedAt; }
 public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }

 public UUID getPostedBy() { return postedBy; }
 public void setPostedBy(UUID postedBy) { this.postedBy = postedBy; }

 public boolean isPosted() { return journalEntryId != null; }
}

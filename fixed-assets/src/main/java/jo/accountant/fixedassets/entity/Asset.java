package jo.accountant.fixedassets.entity;

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
 * Immobilisation (§13 Phase 8).
 *
 * <p>Une immobilisation est un actif destiné à être utilisé durablement par l'entreprise
 * (typiquement > 12 mois) — ex. véhicule, matériel informatique, mobilier, bâtiment.
 *
 * <p>L'amortissement constate la dépréciation de l'actif sur sa durée de vie utile.
 * L'échéancier d'amortissement est généré automatiquement à la création — voir
 * {@link jo.accountant.fixedassets.service.FixedAssetsService#createAsset}.
 *
 * <p>Comptabilisation période par période (jamais en une seule fois à l'achat) — voir
 * {@link jo.accountant.fixedassets.service.FixedAssetsService#postPeriodDepreciation}.
 * Chaque postage génère une écriture avec {@code sourceModule = FIXED_ASSETS} :
 * <ul>
 * <li>Débit : compte de charge d'amortissement ({@link #depreciationExpenseAccountId})</li>
 * <li>Crédit : compte d'amortissement cumulé ({@link #accumulatedDepreciationAccountId})</li>
 * </ul>
 *
 * <p>Cession ({@link jo.accountant.fixedassets.service.FixedAssetsService#dispose}) :
 * <ul>
 * <li>Calcul plus/moins-value = prix de cession − (coût − amortissement cumulé)</li>
 * <li>Génération d'une écriture de cession (sortie de l'actif, sortie de l'amortissement
 * cumulé, constatation du prix de cession et de la plus/moins-value)</li>
 * <li>Asset → {@link AssetStatus#DISPOSED} (immuable)</li>
 * </ul>
 *
 * <p>Référence 3 comptes du plan comptable :
 * <ul>
 * <li>{@link #assetAccountId} — compte d'actif immobilisé (ex. 244 "Matériel de transport")</li>
 * <li>{@link #depreciationExpenseAccountId} — compte de charge d'amortissement (ex. 631)</li>
 * <li>{@link #accumulatedDepreciationAccountId} — compte d'amortissement cumulé (ex. 2844)</li>
 * </ul>
 */
@Entity
@Table(name = "asset")
public class Asset extends TenantAwareEntity {

 @Column(name = "label", nullable = false, length = 200)
 private String label;

 @Column(name = "acquisition_date", nullable = false)
 private LocalDate acquisitionDate;

 @Column(name = "acquisition_cost", nullable = false, precision = 19, scale = 4)
 private BigDecimal acquisitionCost;

 /** Durée de vie utile en mois. Ex. 60 mois = 5 ans. */
 @Column(name = "useful_life_months", nullable = false)
 private int usefulLifeMonths;

 /**
 * Valeur résiduelle — valeur estimée de l'actif à la fin de sa durée de vie.
 * L'amortissement total = coût d'acquisition − valeur résiduelle.
 */
 @Column(name = "residual_value", nullable = false, precision = 19, scale = 4)
 private BigDecimal residualValue = BigDecimal.ZERO;

 @Enumerated(EnumType.STRING)
 @Column(name = "depreciation_method", nullable = false, length = 25)
 private DepreciationMethod depreciationMethod = DepreciationMethod.STRAIGHT_LINE;

 @Column(name = "asset_account_id", nullable = false)
 private UUID assetAccountId;

 @Column(name = "depreciation_expense_account_id", nullable = false)
 private UUID depreciationExpenseAccountId;

 @Column(name = "accumulated_depreciation_account_id", nullable = false)
 private UUID accumulatedDepreciationAccountId;

 /**
 * Compte de PRODUITS pour les plus-values de cession (audit M11).
 * Si NULL à la cession, fallback sur {@link #depreciationExpenseAccountId} (rétro-compatibilité).
 * Ex. SYSCOHADA : 775 "Produits de cession d'immobilisations".
 */
 @Column(name = "disposal_gain_account_id")
 private UUID disposalGainAccountId;

 /**
 * Compte de CHARGES pour les moins-values de cession (audit M11).
 * Si NULL à la cession, fallback sur {@link #depreciationExpenseAccountId} (rétro-compatibilité).
 * Ex. SYSCOHADA : 675 "Valeurs comptables des immobilisations cédées".
 */
 @Column(name = "disposal_loss_account_id")
 private UUID disposalLossAccountId;

 /**
 * ID de l'écriture de JournalEntry générée à l'acquisition (audit M10).
 * Null si l'écriture n'a pas été générée (anciennes immobilisations ou si ni
 * supplierAccountId ni cashAccountId n'ont été fournis à la création).
 */
 @Column(name = "acquisition_journal_entry_id")
 private UUID acquisitionJournalEntryId;

 @Enumerated(EnumType.STRING)
 @Column(name = "status", nullable = false, length = 12)
 private AssetStatus status = AssetStatus.ACTIVE;

 /** Date de cession — null tant que le statut est ACTIVE. */
 @Column(name = "disposal_date")
 private LocalDate disposalDate;

 /** Prix de cession — null tant que non cédé. */
 @Column(name = "disposal_amount", precision = 19, scale = 4)
 private BigDecimal disposalAmount;

 /** Plus/moins-value réalisée à la cession — null tant que non cédé. */
 @Column(name = "gain_or_loss", precision = 19, scale = 4)
 private BigDecimal gainOrLoss;

 /**
 * Dépréciation IAS 36 cumulée. 0 tant qu'aucun test de dépréciation
 * n'a constaté de perte de valeur. Chaque test qui révèle VNC &gt; montant recouvrable
 * incrémente cette valeur (et génère une écriture D 6816 / C 291).
 *
 * <p>Utilisée dans le calcul de la VNC : VNC = coût d'acquisition − amortissement cumulé −
 * dépréciation IAS 36.
 */
 @Column(name = "impairment_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal impairmentAmount = BigDecimal.ZERO;

 /**
 * Compte de CHARGES pour la dépréciation IAS 36.
 * Ex. SYSCOHADA : 6816 "Dotations pour dépréciation des immobilisations".
 * Si null à l'enregistrement de la dépréciation, fallback sur
 * {@link #depreciationExpenseAccountId} (rétro-compatibilité).
 */
 @Column(name = "impairment_expense_account_id")
 private UUID impairmentExpenseAccountId;

 /**
 * Compte d'ACTIF pour la dépréciation IAS 36 cumulée.
 * Ex. SYSCOHADA : 291 "Dépréciation des immobilisations".
 * Si null à l'enregistrement de la dépréciation, fallback sur
 * {@link #accumulatedDepreciationAccountId} (rétro-compatibilité).
 */
 @Column(name = "accumulated_impairment_account_id")
 private UUID accumulatedImpairmentAccountId;

 public String getLabel() { return label; }
 public void setLabel(String label) { this.label = label; }

 public LocalDate getAcquisitionDate() { return acquisitionDate; }
 public void setAcquisitionDate(LocalDate acquisitionDate) { this.acquisitionDate = acquisitionDate; }

 public BigDecimal getAcquisitionCost() { return acquisitionCost; }
 public void setAcquisitionCost(BigDecimal acquisitionCost) { this.acquisitionCost = acquisitionCost; }

 public int getUsefulLifeMonths() { return usefulLifeMonths; }
 public void setUsefulLifeMonths(int usefulLifeMonths) { this.usefulLifeMonths = usefulLifeMonths; }

 public BigDecimal getResidualValue() { return residualValue; }
 public void setResidualValue(BigDecimal residualValue) { this.residualValue = residualValue; }

 public DepreciationMethod getDepreciationMethod() { return depreciationMethod; }
 public void setDepreciationMethod(DepreciationMethod depreciationMethod) { this.depreciationMethod = depreciationMethod; }

 public UUID getAssetAccountId() { return assetAccountId; }
 public void setAssetAccountId(UUID assetAccountId) { this.assetAccountId = assetAccountId; }

 public UUID getDepreciationExpenseAccountId() { return depreciationExpenseAccountId; }
 public void setDepreciationExpenseAccountId(UUID depreciationExpenseAccountId) {
 this.depreciationExpenseAccountId = depreciationExpenseAccountId;
 }

 public UUID getAccumulatedDepreciationAccountId() { return accumulatedDepreciationAccountId; }
 public void setAccumulatedDepreciationAccountId(UUID accumulatedDepreciationAccountId) {
 this.accumulatedDepreciationAccountId = accumulatedDepreciationAccountId;
 }

 public UUID getDisposalGainAccountId() { return disposalGainAccountId; }
 public void setDisposalGainAccountId(UUID disposalGainAccountId) {
 this.disposalGainAccountId = disposalGainAccountId;
 }

 public UUID getDisposalLossAccountId() { return disposalLossAccountId; }
 public void setDisposalLossAccountId(UUID disposalLossAccountId) {
 this.disposalLossAccountId = disposalLossAccountId;
 }

 public UUID getAcquisitionJournalEntryId() { return acquisitionJournalEntryId; }
 public void setAcquisitionJournalEntryId(UUID acquisitionJournalEntryId) {
 this.acquisitionJournalEntryId = acquisitionJournalEntryId;
 }

 public AssetStatus getStatus() { return status; }
 public void setStatus(AssetStatus status) { this.status = status; }

 public LocalDate getDisposalDate() { return disposalDate; }
 public void setDisposalDate(LocalDate disposalDate) { this.disposalDate = disposalDate; }

 public BigDecimal getDisposalAmount() { return disposalAmount; }
 public void setDisposalAmount(BigDecimal disposalAmount) { this.disposalAmount = disposalAmount; }

 public BigDecimal getGainOrLoss() { return gainOrLoss; }
 public void setGainOrLoss(BigDecimal gainOrLoss) { this.gainOrLoss = gainOrLoss; }

 public BigDecimal getImpairmentAmount() { return impairmentAmount; }
 public void setImpairmentAmount(BigDecimal impairmentAmount) {
 if (impairmentAmount == null) impairmentAmount = BigDecimal.ZERO;
 this.impairmentAmount = impairmentAmount;
 }

 public UUID getImpairmentExpenseAccountId() { return impairmentExpenseAccountId; }
 public void setImpairmentExpenseAccountId(UUID impairmentExpenseAccountId) {
 this.impairmentExpenseAccountId = impairmentExpenseAccountId;
 }

 public UUID getAccumulatedImpairmentAccountId() { return accumulatedImpairmentAccountId; }
 public void setAccumulatedImpairmentAccountId(UUID accumulatedImpairmentAccountId) {
 this.accumulatedImpairmentAccountId = accumulatedImpairmentAccountId;
 }
}

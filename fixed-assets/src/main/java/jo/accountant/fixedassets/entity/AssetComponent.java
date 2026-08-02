package jo.accountant.fixedassets.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Composant d'une immobilisation (IAS 16 amortissement par composant).
 *
 * <p>IAS 16 §43 impose que chaque partie d'une immobilisation ayant une durée de vie utile
 * différente soit comptabilisée et amortie séparément. Exemple typique : un bâtiment se
 * décompose en :
 * <ul>
 * <li><b>Structure</b> — durée de vie 50 ans, amortissement linéaire</li>
 * <li><b>Toiture</b> — durée de vie 20 ans, amortissement linéaire</li>
 * <li><b>Installations techniques</b> — durée de vie 10 ans, amortissement linéaire</li>
 * </ul>
 *
 * <p>Chaque composant a son propre coût d'acquisition, sa propre durée de vie et sa propre
 * valeur résiduelle. L'amortissement est calculé indépendamment pour chaque composant — voir
 * {@link jo.accountant.fixedassets.service.FixedAssetsService#generateSchedule}.
 *
 * <p>La somme des coûts d'acquisition des composants devrait idéalement égaler le coût
 * d'acquisition de l'immobilisation parente (contrôle recommandé mais non bloquant au MVP —
 * l'utilisateur peut saisir un coût d'acquisition global sur l'asset et ne détailler que
 * certains composants).
 *
 * <p>La durée de vie est exprimée en <strong>années</strong> (et non en mois comme sur
 * {@link Asset#getUsefulLifeMonths()}) car c'est l'usage IAS 16 (ex. "bâtiment 50 ans").
 * Convertie en mois (×12) au moment de la génération de l'échéancier.
 *
 * <p>Unicité : le {@code code} composant est unique par immobilisation
 * (contrainte {@code uc_asset_component_asset_code}).
 */
@Entity
@Table(name = "asset_component",
 uniqueConstraints = @UniqueConstraint(name = "uc_asset_component_asset_code",
 columnNames = {"asset_id", "code"}))
public class AssetComponent extends TenantAwareEntity {

 /** Immobilisation parente (FK logique vers asset.id). */
 @Column(name = "asset_id", nullable = false)
 private UUID assetId;

 /** Code court du composant (ex. "STRUCT", "TOIT", "INSTAL"). Unique par asset. */
 @Column(name = "code", nullable = false, length = 50)
 private String code;

 @Column(name = "label", nullable = false, length = 200)
 private String label;

 /** Coût d'acquisition du composant (en devise fonctionnelle). */
 @Column(name = "acquisition_cost", nullable = false, precision = 19, scale = 4)
 private BigDecimal acquisitionCost;

 /** Durée de vie utile en années. Convertie en mois (×12) pour l'échéancier. */
 @Column(name = "useful_life_years", nullable = false)
 private int usefulLifeYears;

 /** Valeur résiduelle du composant (défaut 0). */
 @Column(name = "residual_value", nullable = false, precision = 19, scale = 4)
 private BigDecimal residualValue = BigDecimal.ZERO;

 @Enumerated(EnumType.STRING)
 @Column(name = "depreciation_method", nullable = false, length = 25)
 private DepreciationMethod depreciationMethod = DepreciationMethod.STRAIGHT_LINE;

 public UUID getAssetId() { return assetId; }
 public void setAssetId(UUID assetId) { this.assetId = assetId; }

 public String getCode() { return code; }
 public void setCode(String code) { this.code = code; }

 public String getLabel() { return label; }
 public void setLabel(String label) { this.label = label; }

 public BigDecimal getAcquisitionCost() { return acquisitionCost; }
 public void setAcquisitionCost(BigDecimal acquisitionCost) { this.acquisitionCost = acquisitionCost; }

 public int getUsefulLifeYears() { return usefulLifeYears; }
 public void setUsefulLifeYears(int usefulLifeYears) { this.usefulLifeYears = usefulLifeYears; }

 public BigDecimal getResidualValue() { return residualValue; }
 public void setResidualValue(BigDecimal residualValue) { this.residualValue = residualValue; }

 public DepreciationMethod getDepreciationMethod() { return depreciationMethod; }
 public void setDepreciationMethod(DepreciationMethod depreciationMethod) {
 this.depreciationMethod = depreciationMethod;
 }
}

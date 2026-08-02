package jo.accountant.tax.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Règle de cotisation sociale pour le moteur de paie (audit v4.7 §4.1 ).
 *
 * <p>Représente une cotisation (URSSAF, retraite, prévoyance, mutuelle, CSG/CRDS) avec :
 * <ul>
 * <li><b>ContributionBase</b> : assiette de calcul (brut, abattue, plafonnée).</li>
 * <li><b>ContributionRate</b> : taux appliqué sur l'assiette (séparé employé / employeur).</li>
 * <li><b>Plafond</b> : PMSS France (3 864 € mensuels 2024), PMT Haïti, etc.</li>
 * <li><b>Tranches</b> : Tranche A (&lt; PMSS), Tranche B (PMSS à 4×PMSS), Tranche C (4× à 8×PMSS).</li>
 * </ul>
 *
 * <p>Supporte les régimes :
 * <ul>
 * <li><b>FR_GENERAL</b> : régime général français (URSSAF + retraite + chômage + AGIRC-ARRCO).</li>
 * <li><b>FR_CADRE</b> : régime cadre français (ajoute APEC + GMP + prévoyance cadre).</li>
 * <li><b>FR_NON_CADRE</b> : régime non-cadre français (sans APEC/GMP).</li>
 * <li><b>HT_GENERAL</b> : régime haïtien (OFATMA + impôt sur le revenu).</li>
 * <li><b>CUSTOM</b> : règle personnalisée (entreprise configure ses propres taux).</li>
 * </ul>
 *
 * <p>Stockage : une ligne par (companyId, code, regime). {@code active=true} pour activer.
 */
@Entity
@Table(name = "contribution_rule")
public class ContributionRule {

 @Id
 @Column(name = "id", nullable = false, updatable = false)
 private UUID id;

 @Column(name = "company_id", nullable = false)
 private UUID companyId;

 /** Code court unique par entreprise (ex: "URSSAF", "RETRAITE", "CSG", "MUTUELLE"). */
 @Column(name = "code", nullable = false, length = 30)
 private String code;

 @Column(name = "label", nullable = false, length = 200)
 private String label;

 /** Régime applicable — détermine l'ordre d'application et les plafonds par défaut. */
 @Enumerated(EnumType.STRING)
 @Column(name = "regime", nullable = false, length = 30)
 private ContributionRegime regime;

 /** Type de cotisation — détermine si c'est une cotisation salariale, patronale, ou les deux. */
 @Enumerated(EnumType.STRING)
 @Column(name = "contribution_type", nullable = false, length = 20)
 private ContributionType contributionType;

 /**
 * Taux appliqué sur l'assiette (en %). Pour CSG déductible : 6.865%.
 * Séparé employé/employeur via {@link #contributionType}.
 */
 @Column(name = "rate", nullable = false, precision = 6, scale = 4)
 private BigDecimal rate;

 /**
 * Assiette de calcul — détermine sur quel montant le taux s'applique.
 * <ul>
 * <li>{@code GROSS} : salaire brut intégral (ex: URSSAF maladie).</li>
 * <li>{@code GROSS_ABATED} : brut × abattementRate (ex: CSG/CRDS = brut × 98.25%).</li>
 * <li>{@code CAPPED_GROSS} : brut plafonné au PMSS (ex: retraite Tranche A).</li>
 * <li>{@code CAPPED_GROSS_ABATED} : brut plafonné ET abattu (rare).</li>
 * <li>{@code TRANCHE_B} : part du brut entre PMSS et 4×PMSS (ex: retraite Tranche B).</li>
 * </ul>
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "base_type", nullable = false, length = 30)
 private ContributionBase baseType;

 /**
 * Taux d'abattement appliqué à l'assiette (en %, ex: 98.25 pour CSG).
 * Utilisé uniquement si {@code baseType = GROSS_ABATED} ou {@code CAPPED_GROSS_ABATED}.
 * 100 si pas d'abattement.
 */
 @Column(name = "abatement_rate", precision = 6, scale = 4)
 private BigDecimal abatementRate = new BigDecimal("100.0000");

 /**
 * Plafond mensuel (ex: PMSS France 2024 = 3864 €). Si null, pas de plafond.
 * Pour Tranche B, le plafond supérieur est 4× cette valeur.
 */
 @Column(name = "monthly_ceiling", precision = 19, scale = 4)
 private BigDecimal monthlyCeiling;

 /**
 * Multiplicateur du plafond pour la tranche supérieure (ex: 4 pour Tranche B = 4×PMSS).
 * Utilisé uniquement si {@code baseType = TRANCHE_B}.
 */
 @Column(name = "ceiling_multiplier", precision = 5, scale = 2)
 private BigDecimal ceilingMultiplier;

 /**
 * Lot B type de barème pour les cotisations à barème progressif (ex: AST Haïti).
 * {@code FLAT} par défaut pour préserver le comportement historique (calcul
 * {@code assiette × rate / 100}). Si {@code PROGRESSIVE}, le calcul utilise
 * {@link #bracketsJson} pour sommer les tranches (chaque palier taxé à son propre taux).
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "bracket_type", nullable = false, length = 15)
 private jo.accountant.core.tax.WithholdingBracketType bracketType =
 jo.accountant.core.tax.WithholdingBracketType.FLAT;

 /**
 * Lot B barème progressif par tranches au format JSON
 * {@code [{"threshold":0,"rate":0},{"threshold":50000,"rate":1},...]}.
 * Utilisé uniquement si {@code bracketType = PROGRESSIVE}.
 */
 @Column(name = "brackets_json", columnDefinition = "jsonb")
 private String bracketsJson;

 @Column(name = "active", nullable = false)
 private boolean active = true;

 /**
 * Compte de débit/crédit pour la comptabilisation (taxMappingCode, ex: "URSSAF_DEBIT",
 * "URSSAF_CREDIT"). Résolu au moment de l'écriture comptable via ChartOfAccountsService.
 */
 @Column(name = "tax_mapping_code", length = 50)
 private String taxMappingCode;

 @Version
 @Column(name = "version", nullable = false)
 private long version;

 // --- Getters/Setters ---

 public UUID getId() { return id; }
 public void setId(UUID id) { this.id = id; }
 public UUID getCompanyId() { return companyId; }
 public void setCompanyId(UUID companyId) { this.companyId = companyId; }
 public String getCode() { return code; }
 public void setCode(String code) { this.code = code; }
 public String getLabel() { return label; }
 public void setLabel(String label) { this.label = label; }
 public ContributionRegime getRegime() { return regime; }
 public void setRegime(ContributionRegime regime) { this.regime = regime; }
 public ContributionType getContributionType() { return contributionType; }
 public void setContributionType(ContributionType contributionType) { this.contributionType = contributionType; }
 public BigDecimal getRate() { return rate; }
 public void setRate(BigDecimal rate) { this.rate = rate; }
 public ContributionBase getBaseType() { return baseType; }
 public void setBaseType(ContributionBase baseType) { this.baseType = baseType; }
 public BigDecimal getAbatementRate() { return abatementRate; }
 public void setAbatementRate(BigDecimal abatementRate) { this.abatementRate = abatementRate; }
 public BigDecimal getMonthlyCeiling() { return monthlyCeiling; }
 public void setMonthlyCeiling(BigDecimal monthlyCeiling) { this.monthlyCeiling = monthlyCeiling; }
 public BigDecimal getCeilingMultiplier() { return ceilingMultiplier; }
 public void setCeilingMultiplier(BigDecimal ceilingMultiplier) { this.ceilingMultiplier = ceilingMultiplier; }
 public jo.accountant.core.tax.WithholdingBracketType getBracketType() { return bracketType; }
 public void setBracketType(jo.accountant.core.tax.WithholdingBracketType bracketType) {
 this.bracketType = bracketType != null ? bracketType
 : jo.accountant.core.tax.WithholdingBracketType.FLAT;
 }
 public String getBracketsJson() { return bracketsJson; }
 public void setBracketsJson(String bracketsJson) { this.bracketsJson = bracketsJson; }
 public boolean isActive() { return active; }
 public void setActive(boolean active) { this.active = active; }
 public String getTaxMappingCode() { return taxMappingCode; }
 public void setTaxMappingCode(String taxMappingCode) { this.taxMappingCode = taxMappingCode; }
 public long getVersion() { return version; }
 public void setVersion(long version) { this.version = version; }

 /** Régime de cotisation applicable. */
 public enum ContributionRegime {
 FR_GENERAL, // Régime général français (URSSAF + retraite + chômage)
 FR_CADRE, // Cadre français (+ APEC + GMP)
 FR_NON_CADRE, // Non-cadre français
 HT_GENERAL, // Haïti (OFATMA + IR)
 CUSTOM // Personnalisé
 }

 /** Type de cotisation — détermine le débit/crédit comptable. */
 public enum ContributionType {
 EMPLOYEE, // Cotisation salariale (débit 431-433, crédit 64)
 EMPLOYER, // Cotisation patronale (débit 645, crédit 431-433)
 EMPLOYEE_AND_EMPLOYER // Les deux (crée 2 lignes séparées)
 }

 /** Assiette de calcul de la cotisation. */
 public enum ContributionBase {
 GROSS, // Brut intégral
 GROSS_ABATED, // Brut × abatementRate
 CAPPED_GROSS, // Brut plafonné au monthlyCeiling
 CAPPED_GROSS_ABATED, // Brut plafonné puis abattu
 TRANCHE_B // Part du brut entre PMSS et ceilingMultiplier×PMSS
 }
}

package jo.accountant.tax.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Règle d'Impôt sur les Sociétés (IS) —.
 *
 * <p>Permet de calculer la projection d'IS pour un exercice fiscal. France : taux normal 25%,
 * taux réduit PME 15% jusqu'à 42 500 € de profit (CGI art. 219).
 *
 * <p>Le calcul de l'IS se fait en 3 étapes :
 * <ol>
 * <li>Résultat comptable = Produits − Charges (depuis le CR)</li>
 * <li>Résultat fiscal = Résultat comptable + réintégrations extra-comptables
 * (amendement Charasse, amendements divers) − déductions (plus-values LTPE, etc.)</li>
 * <li>IS brut = Résultat fiscal × taux (15% jusqu'à 42 500 €, 25% au-delà pour PME ;
 * 25% sur la totalité pour les grandes entreprises)</li>
 * <li>IS net = IS brut − avoirs fiscaux − crédits d'impôt</li>
 * </ol>
 *
 * <p>Sur la période, l'entreprise doit verser 4 acomptes (en mars, juin, septembre, décembre)
 * calculés sur l'IS N-1. Le solde est versé au plus tard le 15 mai N+1.
 */
@Entity
@Table(name = "corporate_tax_rule",
 uniqueConstraints = @UniqueConstraint(name = "uc_ctr_company_active",
 columnNames = {"company_id", "active"}))
/**
 * CorporateTaxRule.
 *
 * @author jo@Dev


 */

public class CorporateTaxRule extends TenantAwareEntity {

 @Id
 @Column(name = "id", nullable = false)
 private UUID id;

 /** Taux normal (25% en France 2026). */
 @Column(name = "standard_rate", nullable = false, precision = 5, scale = 4)
 private BigDecimal standardRate;

 /** Taux réduit PME (15% en France, applicable jusqu'à {@link #reducedRateThreshold}). */
 @Column(name = "reduced_rate", precision = 5, scale = 4)
 private BigDecimal reducedRate;

 /** Seuil d'application du taux réduit (42 500 € en France). */
 @Column(name = "reduced_rate_threshold", precision = 19, scale = 4)
 private BigDecimal reducedRateThreshold;

 /** Taille d'entreprise déterminant l'éligibilité au taux réduit PME. */
 @Enumerated(EnumType.STRING)
 @Column(name = "eligibility")
 private CorporateTaxEligibility eligibility;

 /**
 * v8-1 — Code pays ISO 3166-1 alpha-2 de la règle (ex: "HT", "FR", "CA").
 *
 * <p>Permet de résoudre la règle par pays sans dépendre uniquement de {@code companyId}
 * (les règles globales ont {@code companyId = NULL} et sont distinguées par leur pays).
 * Colonne déjà créée en V76 — exposée côté Java uniquement en v8-1.
 */
 @Column(name = "country_code", length = 2)
 private String countryCode;

 /**
 * v8-1 — TRUE si cette règle est l'IS zone franche (15% Haïti, Code Fiscal art. 195).
 *
 * <p>Permet à {@code CorporateTaxRuleRepository.findByCountryCodeAndIsFreeZoneRateTrueAndActiveTrue}
 * de résoudre directement la règle ZF pour un pays donné. Colonne déjà créée en V76 —
 * exposée côté Java en v8-1.
 */
 @Column(name = "is_free_zone_rate", nullable = false)
 private boolean freeZoneRate = false;

 /**
 * v8-1 — TRUE si cette règle est l'IS ONG exonérée (0% Haïti, Code Fiscal art. 195).
 *
 * <p>Permet à {@code CorporateTaxRuleRepository.findByCountryCodeAndIsNgoExemptRateTrueAndActiveTrue}
 * de résoudre directement la règle ONG pour un pays donné. Colonne créée en V90.
 */
 @Column(name = "is_ngo_exempt_rate", nullable = false)
 private boolean ngoExemptRate = false;

 /** Date d'effet (début). */
 @Column(name = "applicable_from")
 private LocalDate applicableFrom;

 /** Date d'effet (fin, nullable = illimité). */
 @Column(name = "applicable_to")
 private LocalDate applicableTo;

 @Column(name = "active", nullable = false)
 private boolean active;

 // --- Getters / Setters ---

 public UUID getId() { return id; }
 public void setId(UUID id) { this.id = id; }

 public BigDecimal getStandardRate() { return standardRate; }
 public void setStandardRate(BigDecimal standardRate) { this.standardRate = standardRate; }

 public BigDecimal getReducedRate() { return reducedRate; }
 public void setReducedRate(BigDecimal reducedRate) { this.reducedRate = reducedRate; }

 public BigDecimal getReducedRateThreshold() { return reducedRateThreshold; }
 public void setReducedRateThreshold(BigDecimal reducedRateThreshold) { this.reducedRateThreshold = reducedRateThreshold; }

 public CorporateTaxEligibility getEligibility() { return eligibility; }
 public void setEligibility(CorporateTaxEligibility eligibility) { this.eligibility = eligibility; }

 /** v8-1 — Code pays ISO 3166-1 alpha-2 de la règle (ex: "HT", "FR", "CA"). */
 public String getCountryCode() { return countryCode; }
 public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

 /** v8-1 — TRUE si règle IS zone franche (15% Haïti, art. 195). */
 public boolean isFreeZoneRate() { return freeZoneRate; }
 public void setFreeZoneRate(boolean freeZoneRate) { this.freeZoneRate = freeZoneRate; }

 /** v8-1 — TRUE si règle IS ONG exonérée (0% Haïti, art. 195). */
 public boolean isNgoExemptRate() { return ngoExemptRate; }
 public void setNgoExemptRate(boolean ngoExemptRate) { this.ngoExemptRate = ngoExemptRate; }

 public LocalDate getApplicableFrom() { return applicableFrom; }
 public void setApplicableFrom(LocalDate applicableFrom) { this.applicableFrom = applicableFrom; }

 public LocalDate getApplicableTo() { return applicableTo; }
 public void setApplicableTo(LocalDate applicableTo) { this.applicableTo = applicableTo; }

 public boolean isActive() { return active; }
 public void setActive(boolean active) { this.active = active; }
}

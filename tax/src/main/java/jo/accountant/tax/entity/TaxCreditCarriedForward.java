package jo.accountant.tax.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Crédit de TVA reporté d'une période à la suivante (Lot B ).
 *
 * <p>Avant la , le crédit de TVA (quand TVA déductible &gt; TVA collectée) était calculé
 * mais non persisté : l'utilisateur devait le resaisir manuellement à la période suivante.
 * Cela générait deux problèmes :
 * <ul>
 * <li><b>Risque d'oubli</b> : l'entreprise oubliait de reporter le crédit → perte fiscale
 * (le crédit de TVA est un droit récupérable, art. 271 CGI / Code Fiscal Haïti).</li>
 * <li><b>Donnée non auditée</b> : aucun historique des crédits reportés — impossible de
 * justifier un crédit élevé lors d'un contrôle DGI/DGFiP.</li>
 * </ul>
 *
 * <p>Cette entité persiste le crédit à la fin de chaque déclaration (quand
 * {@code taxCreditToCarryForward > 0}) et le lit au début de la déclaration suivante pour
 * pré-remplir {@code taxCreditCarriedForward}. La contrainte unique
 * {@code (company_id, tax_type, period_year, period_month)} garantit l'unicité d'un crédit
 * par période et par type de taxe (TVA, TCA — chacune peut avoir son propre crédit).
 *
 * <p><b>Stratégie de période</b> : pour une déclaration mensuelle (la plus courante), on
 * stocke {@code periodYear/periodMonth} de la période concernée. À la période suivante, on
 * lit le crédit de la période précédente (M-1). Pour une déclaration annuelle (IS), on lit
 * l'année précédente.
 *
 * <p><b>Pas de TenantAwareEntity</b> : bien que cette entité soit liée à un tenant
 * ({@code companyId}), elle n'étend pas {@code TenantAwareEntity} pour éviter la double
 * colonne company_id (la PK est un UUID standard). La RLS PostgreSQL (V62) protège
 * néanmoins la table via la policy sur company_id.
 */
@Entity
@Table(name = "tax_credit_carried_forward",
 uniqueConstraints = @UniqueConstraint(name = "uc_tax_credit_period",
 columnNames = {"company_id", "tax_type", "period_year", "period_month"}))
public class TaxCreditCarriedForward {

 @Id
 @Column(name = "id", nullable = false, updatable = false)
 private UUID id;

 @Column(name = "company_id", nullable = false, updatable = false)
 private UUID companyId;

 /**
 * Type de taxe concerné — permet de gérer séparément les crédits de TVA, TCA, etc.
 * Les valeurs sont alignées sur {@link TaxType} (VAT, TCA, TURNOVER_TAX, EXCISE).
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "tax_type", nullable = false, length = 20, updatable = false)
 private TaxType taxType;

 @Column(name = "period_year", nullable = false, updatable = false)
 private int periodYear;

 @Column(name = "period_month", nullable = false, updatable = false)
 private int periodMonth;

 /** Montant du crédit reporté (positif). */
 @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal creditAmount;

 /**
 * Indique si ce crédit a été reporté vers la période suivante (true par défaut).
 * Mis à false si l'entreprise demande un remboursement (TVA : art. 271 CGI ; Haïti :
 * remboursement sur autorisation DGI).
 */
 @Column(name = "carried_to_next", nullable = false)
 private boolean carriedToNext = true;

 @Column(name = "created_at", nullable = false, updatable = false)
 private Instant createdAt;

 @Version
 @Column(name = "version", nullable = false)
 private long version;

 // --- Getters/Setters ---

 public UUID getId() { return id; }
 public void setId(UUID id) { this.id = id; }

 public UUID getCompanyId() { return companyId; }
 public void setCompanyId(UUID companyId) { this.companyId = companyId; }

 public TaxType getTaxType() { return taxType; }
 public void setTaxType(TaxType taxType) { this.taxType = taxType; }

 public int getPeriodYear() { return periodYear; }
 public void setPeriodYear(int periodYear) { this.periodYear = periodYear; }

 public int getPeriodMonth() { return periodMonth; }
 public void setPeriodMonth(int periodMonth) { this.periodMonth = periodMonth; }

 public BigDecimal getCreditAmount() { return creditAmount; }
 public void setCreditAmount(BigDecimal creditAmount) { this.creditAmount = creditAmount; }

 public boolean isCarriedToNext() { return carriedToNext; }
 public void setCarriedToNext(boolean carriedToNext) { this.carriedToNext = carriedToNext; }

 public Instant getCreatedAt() { return createdAt; }
 public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

 public long getVersion() { return version; }
 public void setVersion(long version) { this.version = version; }
}

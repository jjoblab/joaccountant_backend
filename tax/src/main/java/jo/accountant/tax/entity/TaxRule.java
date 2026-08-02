package jo.accountant.tax.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tax.VatMode;

/**
 * Règle fiscale — TVA ou autre taxe (§13.
 *
 * <p>Peut être globale par pays/référentiel (companyId null) ou spécifique à une entreprise.
 *
 * <p><b>TVA sur encaissement</b> : le champ {@link #vatMode} indique si la TVA est
 * exigible à l'émission de la facture ({@link VatMode#DEBIT} — régime des débits, défaut) ou au
 * paiement effectif par le client ({@link VatMode#ENCAISSEMENT} — régime des encaissements,
 * art. 289 II CGI). En mode {@code ENCAISSEMENT}, la TVA est stockée dans un compte d'attente
 * 4438 « TVA sur factures émises non encaissées » à l'émission, puis basculée vers le 443
 * (TVA collectée) au règlement — voir {@code InvoicingService.generateInvoiceEntry} et
 * {@code InvoicingService.recordPayment}.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "tax_rule")
public class TaxRule {

 @Id @Column(name = "id", nullable = false, updatable = false)
 private UUID id;

 /** Null = règle globale par pays. Non-null = spécifique à l'entreprise. */
 @Column(name = "company_id")
 private UUID companyId;

 @Column(name = "code", nullable = false, length = 30)
 private String code;

 @Column(name = "label", nullable = false, length = 200)
 private String label;

 /** Taux en pourcentage (ex. 15.00 pour 15% TVA Haïti). */
 @Column(name = "rate", nullable = false, precision = 5, scale = 2)
 private BigDecimal rate;

 /** Compte de TVA collectée (créditée à la vente). */
 @Column(name = "payable_account_id")
 private UUID payableAccountId;

 /** Compte de TVA déductible (débitée à l'achat). */
 @Column(name = "receivable_account_id")
 private UUID receivableAccountId;

 @Column(name = "applicable_from", nullable = false)
 private LocalDate applicableFrom;

 @Column(name = "applicable_to")
 private LocalDate applicableTo;

 @Column(name = "active", nullable = false)
 private boolean active = true;

 /**
 * Mode d'exigibilité de la TVA. {@link VatMode#DEBIT} par défaut (régime des
 * débits — exigible à l'émission). {@link VatMode#ENCAISSEMENT} = régime des encaissements
 * (exigible au paiement).
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "vat_mode", nullable = false, length = 15)
 private VatMode vatMode = VatMode.DEBIT;

 /**
 * Type de taxe (Lot B — fiscalité Haïti). {@link TaxType#VAT} par défaut pour
 * préserver le comportement historique. Pour les TCA haïtiennes (art. 196/197), utiliser
 * {@link TaxType#TCA}. La distinction est nécessaire car TVA et TCA sont cumulables sur
 * une même opération en Haïti.
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "tax_type", nullable = false, length = 20)
 private TaxType taxType = TaxType.VAT;

 @Version @Column(name = "version", nullable = false)
 private long version;

 public UUID getId() { return id; }
 public void setId(UUID id) { this.id = id; }
 public UUID getCompanyId() { return companyId; }
 public void setCompanyId(UUID companyId) { this.companyId = companyId; }
 public String getCode() { return code; }
 public void setCode(String code) { this.code = code; }
 public String getLabel() { return label; }
 public void setLabel(String label) { this.label = label; }
 public BigDecimal getRate() { return rate; }
 public void setRate(BigDecimal rate) { this.rate = rate; }
 public UUID getPayableAccountId() { return payableAccountId; }
 public void setPayableAccountId(UUID payableAccountId) { this.payableAccountId = payableAccountId; }
 public UUID getReceivableAccountId() { return receivableAccountId; }
 public void setReceivableAccountId(UUID receivableAccountId) { this.receivableAccountId = receivableAccountId; }
 public LocalDate getApplicableFrom() { return applicableFrom; }
 public void setApplicableFrom(LocalDate applicableFrom) { this.applicableFrom = applicableFrom; }
 public LocalDate getApplicableTo() { return applicableTo; }
 public void setApplicableTo(LocalDate applicableTo) { this.applicableTo = applicableTo; }
 public boolean isActive() { return active; }
 public void setActive(boolean active) { this.active = active; }

 public VatMode getVatMode() { return vatMode; }
 public void setVatMode(VatMode vatMode) {
 this.vatMode = vatMode != null ? vatMode : VatMode.DEBIT;
 }

 public TaxType getTaxType() { return taxType; }
 public void setTaxType(TaxType taxType) {
 this.taxType = taxType != null ? taxType : TaxType.VAT;
 }

 public long getVersion() { return version; }
 public void setVersion(long version) { this.version = version; }
}

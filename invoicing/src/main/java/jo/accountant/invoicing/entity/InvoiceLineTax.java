package jo.accountant.invoicing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ligne de taxe associée à une {@link InvoiceLine} (v6-1-multi-tax-invoice-line).
 *
 * <p><b>Contexte fiscal Haïti</b> (lot-G validation-pme-expert — P0 BLOQUANT) : sur une facture
 * de prestation de services en Haïti, la <b>TVA 10%</b> (Code Fiscal art. 191) et la <b>TCA 10%</b>
 * (Code Fiscal art. 196) sont <b>cumulatives</b> sur la même ligne. Avant V67, {@code InvoiceLine}
 * ne portait qu'un seul champ {@code taxRate} → une seule taxe par ligne → les cabinets de services
 * (PME2 Moïse &amp; Associés) et commerces mixtes (PME1 Boutik Lakay livraison) ne pouvaient pas
 * éditer de factures conformes.
 *
 * <p>V67 introduit la table {@code invoice_line_tax} : chaque {@link InvoiceLine} peut porter
 * <b>N taxes</b> (TVA + TCA + autres taxes sur chiffre d'affaires + accises). Le champ
 * {@code InvoiceLine.taxRate} est <b>conservé</b> pour la rétro-compatibilité :
 * <ul>
 *   <li>Si {@code InvoiceLine.taxes} (table invoice_line_tax) est <b>vide</b> pour la ligne,
 *       le service fallback sur {@code InvoiceLine.taxRate} comme TVA seule (comportement
 *       historique v5.x).</li>
 *   <li>Si {@code InvoiceLine.taxes} contient au moins une entrée, la TVA unique {@code taxRate}
 *       est ignorée (mais reste persistée pour audit et backward-compat lecture).</li>
 * </ul>
 *
 * <p><b>Tenant-awareness</b> : cette entité n'est PAS directement tenant-aware (pas de
 * {@code company_id}). Elle hérite du tenant via la {@link InvoiceLine} parente. Cela évite la
 * redondance et les bugs de désynchronisation. Les requêtes de tenant-isolation se font en JOIN
 * sur {@code invoice_line.company_id}.
 *
 * <p><b>Rétro-compatibilité</b> : l'entité ne casse aucune lecture/existence de {@link InvoiceLine}.
 * Le repo {@code InvoiceLineRepository} est inchangé. Cette entité est lue/écrite uniquement par
 * {@code InvoicingService} (création + génération écriture) et {@code TaxService} (agrégation
 * déclarative par {@code taxType}).
 */
@Entity
@Table(name = "invoice_line_tax")
public class InvoiceLineTax {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** FK logique vers {@link InvoiceLine#getId()} — pas de FK physique (pattern projet). */
    @Column(name = "invoice_line_id", nullable = false, updatable = false)
    private UUID invoiceLineId;

    /** Type de taxe (VAT, TCA, TURNOVER_TAX, EXCISE) — enum local miroir de TaxType. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", nullable = false, length = 20)
    private InvoiceLineTaxType taxType;

    /** Code optionnel de la TaxRule appliquée (ex: TVA_HT_10, TCA_HT_10_SERVICES). */
    @Column(name = "tax_code", length = 30)
    private String taxCode;

    /** Libellé optionnel affiché sur la facture (ex: "TVA 10% (art. 191)"). */
    @Column(name = "tax_label", length = 200)
    private String taxLabel;

    /** Taux en pourcentage (0 à 100, NUMERIC 5,2 — cohérent avec invoice_line.tax_rate). */
    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    /** Base HT soumise à la taxe (NUMERIC 19,4 — cohérent avec line_total_ht). */
    @Column(name = "taxable_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableBase;

    /** Montant de la taxe = taxableBase × rate / 100 (NUMERIC 19,4). */
    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    /** Ordre d'affichage sur la facture (TVA avant TCA avant EXCISE, etc.). Défaut 0. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // --- Getters / Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getInvoiceLineId() { return invoiceLineId; }
    public void setInvoiceLineId(UUID invoiceLineId) { this.invoiceLineId = invoiceLineId; }

    public InvoiceLineTaxType getTaxType() { return taxType; }
    public void setTaxType(InvoiceLineTaxType taxType) { this.taxType = taxType; }

    public String getTaxCode() { return taxCode; }
    public void setTaxCode(String taxCode) { this.taxCode = taxCode; }

    public String getTaxLabel() { return taxLabel; }
    public void setTaxLabel(String taxLabel) { this.taxLabel = taxLabel; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public BigDecimal getTaxableBase() { return taxableBase; }
    public void setTaxableBase(BigDecimal taxableBase) { this.taxableBase = taxableBase; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

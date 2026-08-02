package jo.accountant.invoicing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Facture de vente (§13 Phase 12).
 *
 * <p>Une facture peut être de type {@link InvoiceType#STANDARD} (facture classique) ou
 * {@link InvoiceType#CREDIT_NOTE} (avoir). L'avoir a sa propre séquence dans
 * {@code document-numbering} (DocumentType.CREDIT_NOTE).
 *
 * <p>Cycle de vie : DRAFT → ISSUED → PARTIALLY_PAID → PAID (ou VOID à tout moment
 * après ISSUED).
 *
 * <p>{@link #invoiceNumber} est {@code null} en DRAFT, assigné via document-numbering
 * au passage DRAFT → ISSUED. Une fois ISSUED, la facture est immuable — correction par
 * avoir uniquement.
 *
 * <p><b> — Rich aggregate methods</b> : les méthodes {@link #issue()},
 * {@link #markPaid()} et {@link #markPartiallyPaid()} encapsulent les transitions d'état
 * (DRAFT → ISSUED, ISSUED → PAID/PARTIALLY_PAID). Les setters existants sont conservés
 * (backward compat — InvoicingService les utilise) ; les méthodes métier sont ajoutées
 * pour usage progressif.
 */
@Entity
@Table(name = "sales_invoice")
public class SalesInvoice extends TenantAwareEntity {

 @Column(name = "third_party_id", nullable = false)
 private UUID thirdPartyId;

 @Enumerated(EnumType.STRING)
 @Column(name = "type", nullable = false, length = 15)
 private InvoiceType type = InvoiceType.STANDARD;

 @Enumerated(EnumType.STRING)
 @Column(name = "status", nullable = false, length = 20)
 private InvoiceStatus status = InvoiceStatus.DRAFT;

 /** Null en DRAFT, assigné via document-numbering au passage DRAFT → ISSUED. */
 @Column(name = "invoice_number", length = 50)
 private String invoiceNumber;

 @Column(name = "issue_date")
 private LocalDate issueDate;

 @Column(name = "due_date")
 private LocalDate dueDate;

 /** Code ISO 4217 de la devise. En Phase 12, devrait être la devise fonctionnelle. */
 @Column(name = "currency", nullable = false, length = 3)
 private String currency;

 /** Total HT — somme des lignes (quantity × unitPrice × (1 - discountPercent/100)). */
 @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
 private BigDecimal subtotal = BigDecimal.ZERO;

 /** Total TVA — somme des lignes (subtotal_line × taxRate). */
 @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal taxAmount = BigDecimal.ZERO;

 /** Total TTC = subtotal + taxAmount. */
 @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal totalAmount = BigDecimal.ZERO;

 /** Montant déjà réglé (cumulé via recordPayment). */
 @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
 private BigDecimal paidAmount = BigDecimal.ZERO;

 /** Si CREDIT_NOTE : référence vers la facture originale corrigée. */
 @Column(name = "credit_note_for_invoice_id")
 private UUID creditNoteForInvoiceId;

 /** ID de l'écriture comptable générée au passage DRAFT → ISSUED. */
 @Column(name = "journal_entry_id")
 private UUID journalEntryId;

 /**
 * ID de l'écriture comptable de bascule TVA générée au règlement (TVA sur
 * encaissement). Null en mode {@code DEBIT} ou tant qu'aucun règlement n'a été enregistré.
 */
 @Column(name = "vat_settlement_entry_id")
 private UUID vatSettlementEntryId;

 /**
 * Montant de TVA encore « différée » (en compte 4438 « TVA sur factures émises non
 * encaissées ») — . En mode {@code DEBIT}, toujours 0. En mode
 * {@code ENCAISSEMENT}, initialisé au {@link #taxAmount} à l'émission puis décrémenté à
 * chaque règlement jusqu'à 0 (facture entièrement payée).
 */
 @Column(name = "vat_deferred_amount", precision = 19, scale = 4)
 private BigDecimal vatDeferredAmount = BigDecimal.ZERO;

 /**
 * Indique si la facture est en autoliquidation (reverse-charge intra-UE B2B) — .
 *
 * <p>Positionné à {@code true} à l'émission quand le tiers client ET l'entreprise émettrice
 * disposent tous deux d'un numéro de TVA intracommunautaire (opération intra-UE B2B). Dans
 * ce cas, la TVA n'est pas collectée par l'émetteur : elle est auto-liquidée par le client
 * (Article 283, 2 nonies du CGI). L'écriture comptable crédite le compte 447
 * « TDA autoliquidation » ({@code taxMappingCode = "VAT_REVERSE_CHARGE"}, fallback
 * SYSCOHADA/PCG {@code 444700/4447}) au lieu du 443 (TVA collectée).
 *
 * <p>{@code false} par défaut — rétro-compatible pour toutes les factures existantes.
 */
 @Column(name = "is_reverse_charge", nullable = false)
 private boolean reverseCharge = false;

 /**
 * R-F-validation v6-2 — Taux de retenue à la source (RS) appliqué sur la facture de vente.
 *
 * <p>En Haïti (Code Fiscal art. 156-1), lorsqu'une entreprise facture des prestations de
 * services à un client entreprise, ce client <b>retient à la source</b> un pourcentage
 * (généralement 2% pour les prestations locales) qu'il reverse à la DGI pour le compte du
 * fournisseur. L'entreprise émettrice n'encaisse donc que {@code totalAmount − withholdingAmount}.
 *
 * <p>Exemples de taux (seeds V75 — WithholdingRule globales Haïti) :
 * <ul>
 * <li>{@code 2.00} — RS 2% sur prestations locales (art. 156-1) — code
 * {@code RS_HT_PRESTATIONS_LOCAL}</li>
 * <li>{@code 10.00} — RS 10% sur royalties/redevances (art. 156-2) — code
 * {@code RS_HT_ROYALTIES}</li>
 * <li>{@code 30.00} — RS 30% sur services de non-résidents (art. 156-3) — code
 * {@code RS_HT_NON_RESIDENT_SERVICES}</li>
 * <li>{@code 10.00} — RS 10% sur loyers (art. 156-4) — code {@code RS_HT_RENT}</li>
 * </ul>
 *
 * <p><b>NULL</b> par défaut — la facture n'est pas soumise à RS. Backward-compatible : les
 * factures existantes (avant V79) ont NULL dans tous les champs RS.
 *
 * @see #withholdingAmount
 * @see #netReceivable
 * @see #withholdingRuleId
 */
 @Column(name = "withholding_rate", precision = 5, scale = 2)
 private BigDecimal withholdingRate;

 /**
 * R-F-validation v6-2 — Montant de la retenue à la source retenue par le client.
 *
 * <p>Formule : {@code withholdingAmount = subtotal × withholdingRate / 100} (RS calculée
 * sur le HT, conforme à la pratique OHADA/Haïti — pas sur le TTC).
 *
 * <p>Le client paie à l'entreprise : {@code totalAmount − withholdingAmount = netReceivable}.
 * L'entreprise reverse ensuite ce montant à la DGI (déclaration mensuelle RS, échéance 15 M+1).
 *
 * <p><b>Écriture comptable</b> (générée par {@code InvoicingService.generateInvoiceEntry}) :
 * <pre>
 * D 411 Clients ............ netReceivable (au lieu de totalAmount)
 * D 442 État-RS à reverser . withholdingAmount (à reverser à la DGI pour le compte du client)
 * C 70x Ventes ............. subtotal
 * C 443 TVA collectée ...... taxAmount
 * </pre>
 *
 * <p><b>NULL</b> par défaut (pas de RS sur la facture).
 */
 @Column(name = "withholding_amount", precision = 19, scale = 4)
 private BigDecimal withholdingAmount;

 /**
 * R-F-validation v6-2 — Montant net à recevoir du client = {@code totalAmount − withholdingAmount}.
 *
 * <p>Le client paie ce montant (et non le {@code totalAmount}) car il a retenu la RS à la
 * source qu'il reversera lui-même à la DGI pour le compte du fournisseur.
 *
 * <p><b>NULL</b> par défaut (pas de RS sur la facture). Si NULL, le client paie le
 * {@code totalAmount} complet.
 */
 @Column(name = "net_receivable", precision = 19, scale = 4)
 private BigDecimal netReceivable;

 /**
 * R-F-validation v6-2 — Référence vers la {@code WithholdingRule} appliquée (FK vers
 * {@code withholding_rule.id}).
 *
 * <p>Peut être NULL si la RS a été appliquée avec un taux forcé ({@code withholdingRate}
 * sans règle associée). Sinon, est renseigné quand {@code withholdingRuleCode} est fourni
 * dans la requête.
 */
 @Column(name = "withholding_rule_id")
 private UUID withholdingRuleId;

 /**
 * — Collection transiente (non persistée) des lignes de la facture.
 *
 * <p>Les lignes sont persistées séparément via {@link InvoiceLine} (table {@code invoice_line}
 * avec FK {@code invoice_id}). Cette collection transiente permet aux méthodes métier
 * ({@link #issue()}) de valider les invariants (présence de lignes, recalcul des totaux)
 * sans avoir à recharger les lignes depuis la DB.
 *
 * <p><b>Usage</b> : un service qui veut utiliser {@link #issue()} doit d'abord peupler cette
 * collection (typiquement via {@code invoiceLineRepository.findByInvoiceId(id)} puis
 * {@code invoice.setLines(lines)}). Si la collection reste vide, {@link #issue()} lèvera une
 * {@link IllegalStateException}.
 *
 * <p><b>Backward compat</b> : les services existants qui utilisent les setters directs ne sont
 * pas affectés — cette collection n'est lue que par {@link #issue()} (ajoutée en ).
 */
 @Transient
 private List<InvoiceLine> lines = new ArrayList<>();

 public UUID getThirdPartyId() { return thirdPartyId; }
 public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

 public InvoiceType getType() { return type; }
 public void setType(InvoiceType type) { this.type = type; }

 public InvoiceStatus getStatus() { return status; }
 public void setStatus(InvoiceStatus status) { this.status = status; }

 public String getInvoiceNumber() { return invoiceNumber; }
 public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

 public LocalDate getIssueDate() { return issueDate; }
 public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

 public LocalDate getDueDate() { return dueDate; }
 public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

 public String getCurrency() { return currency; }
 public void setCurrency(String currency) { this.currency = currency; }

 public BigDecimal getSubtotal() { return subtotal; }
 public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

 public BigDecimal getTaxAmount() { return taxAmount; }
 public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

 public BigDecimal getTotalAmount() { return totalAmount; }
 public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

 public BigDecimal getPaidAmount() { return paidAmount; }
 public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

 public UUID getCreditNoteForInvoiceId() { return creditNoteForInvoiceId; }
 public void setCreditNoteForInvoiceId(UUID creditNoteForInvoiceId) {
 this.creditNoteForInvoiceId = creditNoteForInvoiceId;
 }

 public UUID getJournalEntryId() { return journalEntryId; }
 public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

 public UUID getVatSettlementEntryId() { return vatSettlementEntryId; }
 public void setVatSettlementEntryId(UUID vatSettlementEntryId) {
 this.vatSettlementEntryId = vatSettlementEntryId;
 }

 public BigDecimal getVatDeferredAmount() { return vatDeferredAmount; }
 public void setVatDeferredAmount(BigDecimal vatDeferredAmount) {
 this.vatDeferredAmount = vatDeferredAmount != null ? vatDeferredAmount : BigDecimal.ZERO;
 }

 public boolean isReverseCharge() { return reverseCharge; }
 public void setReverseCharge(boolean reverseCharge) { this.reverseCharge = reverseCharge; }

 // ── R-F-validation v6-2 — RS sur ventes (Code Fiscal art. 156-1 Haïti) ──

 public BigDecimal getWithholdingRate() { return withholdingRate; }
 public void setWithholdingRate(BigDecimal withholdingRate) { this.withholdingRate = withholdingRate; }

 public BigDecimal getWithholdingAmount() { return withholdingAmount; }
 public void setWithholdingAmount(BigDecimal withholdingAmount) { this.withholdingAmount = withholdingAmount; }

 public BigDecimal getNetReceivable() { return netReceivable; }
 public void setNetReceivable(BigDecimal netReceivable) { this.netReceivable = netReceivable; }

 public UUID getWithholdingRuleId() { return withholdingRuleId; }
 public void setWithholdingRuleId(UUID withholdingRuleId) { this.withholdingRuleId = withholdingRuleId; }

 /** Solde restant à payer = totalAmount - paidAmount. */
 public BigDecimal getBalanceDue() {
 return totalAmount.subtract(paidAmount);
 }

 /**
 * Collection transiente des lignes (cf. {@link #lines}). Jamais null.
 */
 public List<InvoiceLine> getLines() { return lines; }
 public void setLines(List<InvoiceLine> lines) {
 this.lines = lines != null ? lines : new ArrayList<>();
 }

 // ════════════════════════════════════════════════════════════════════════
 // — Méthodes métier (rich aggregate)
 // ════════════════════════════════════════════════════════════════════════

 /**
 * Recalcule les totaux {@link #subtotal}, {@link #taxAmount} et {@link #totalAmount} à partir
 * des {@link #lines}. Utilisé par {@link #issue()} pour garantir la cohérence des totaux
 * persistés avec les lignes effectivement rattachées à la facture.
 *
 * <p>Formules (cf. {@link InvoiceLine}) :
 * <ul>
 * <li>{@code lineTotalHt = quantity × unitPrice × (1 - discountPercent/100)}</li>
 * <li>{@code lineTotalTax = lineTotalHt × taxRate / 100}</li>
 * <li>{@code subtotal = Σ lineTotalHt}</li>
 * <li>{@code taxAmount = Σ lineTotalTax}</li>
 * <li>{@code totalAmount = subtotal + taxAmount}</li>
 * </ul>
 *
 * <p><b>Note</b> : cette méthode suppose que les {@code lineTotalHt} et {@code lineTotalTax}
 * des lignes sont déjà calculés (typiquement par le service avant l'appel). Elle ne fait que
 * sommer — elle ne re-calcule PAS les totaux par ligne.
 */
 public void recalculateTotals() {
 if (this.lines == null || this.lines.isEmpty()) {
 this.subtotal = BigDecimal.ZERO;
 this.taxAmount = BigDecimal.ZERO;
 this.totalAmount = BigDecimal.ZERO;
 return;
 }
 this.subtotal = this.lines.stream()
 .map(InvoiceLine::getLineTotalHt)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 this.taxAmount = this.lines.stream()
 .map(InvoiceLine::getLineTotalTax)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 this.totalAmount = this.subtotal.add(this.taxAmount);
 }

 /**
 * Transition {@link InvoiceStatus#DRAFT} → {@link InvoiceStatus#ISSUED}. Garantit les
 * invariants d'aggregate :
 * <ul>
 * <li>La facture doit être en statut DRAFT (impossible d'émettre une facture déjà ISSUED
 * ou VOID).</li>
 * <li>La facture doit avoir au moins une ligne (cf. {@link #lines} — la collection
 * transiente doit être peuplée par le service appelant).</li>
 * <li>Les totaux sont recalculés depuis les lignes pour garantir la cohérence
 * (anti-tampering : si un service a modifié les lignes sans recalculer les totaux,
 * cette méthode restaure les valeurs correctes).</li>
 * </ul>
 *
 * <p><b>Effets de bord</b> : cette méthode ne peuple PAS {@code invoiceNumber} (qui dépend de
 * document-numbering) — c'est la responsabilité du service appelant. Elle positionne
 * {@code issueDate = LocalDate.now()}.
 *
 * @throws IllegalStateException si la facture n'est pas en DRAFT ou n'a aucune ligne
 */
 public void issue() {
 if (this.status != InvoiceStatus.DRAFT) {
 throw new IllegalStateException(
 "Cannot issue invoice in status: " + this.status + " (invoiceId=" + this.getId() + ")");
 }
 if (this.lines == null || this.lines.isEmpty()) {
 throw new IllegalStateException(
 "Cannot issue invoice without lines (invoiceId=" + this.getId() + ")");
 }
 // Recalculer les totaux depuis les lignes pour garantir cohérence
 recalculateTotals();
 this.status = InvoiceStatus.ISSUED;
 this.issueDate = LocalDate.now();
 }

 /**
 * Transition {@link InvoiceStatus#ISSUED} ou {@link InvoiceStatus#PARTIALLY_PAID}
 * → {@link InvoiceStatus#PAID}. À appeler après un règlement qui solde la facture.
 *
 * <p><b>Invariant</b> : la facture doit être en statut ISSUED ou PARTIALLY_PAID. Impossible
 * de marquer PAID une facture DRAFT (pas encore émise) ou VOID (annulée).
 *
 * <p><b>Note</b> : cette méthode ne valide PAS que {@code paidAmount >= totalAmount}
 * (le service appelant est responsable de la cohérence du règlement). Elle ne fait que
 * transitionner le statut.
 *
 * @throws IllegalStateException si la facture n'est pas en ISSUED ou PARTIALLY_PAID
 */
 public void markPaid() {
 if (this.status != InvoiceStatus.ISSUED && this.status != InvoiceStatus.PARTIALLY_PAID) {
 throw new IllegalStateException(
 "Cannot mark as paid invoice in status: " + this.status
 + " (invoiceId=" + this.getId() + ")");
 }
 this.status = InvoiceStatus.PAID;
 }

 /**
 * Transition {@link InvoiceStatus#ISSUED} → {@link InvoiceStatus#PARTIALLY_PAID}. À appeler
 * après un règlement partiel (paidAmount &gt; 0 mais &lt; totalAmount).
 *
 * <p><b>Invariant</b> : la facture doit être en statut ISSUED. Impossible de marquer
 * PARTIALLY_PAID une facture DRAFT, PAID (déjà entièrement payée) ou VOID.
 *
 * @throws IllegalStateException si la facture n'est pas en ISSUED
 */
 public void markPartiallyPaid() {
 if (this.status != InvoiceStatus.ISSUED) {
 throw new IllegalStateException(
 "Cannot mark as partially paid invoice in status: " + this.status
 + " (invoiceId=" + this.getId() + ")");
 }
 this.status = InvoiceStatus.PARTIALLY_PAID;
 }
}

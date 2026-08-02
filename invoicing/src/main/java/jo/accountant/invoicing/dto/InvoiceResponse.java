package jo.accountant.invoicing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.InvoiceType;

/**
 * InvoiceResponse.
 *
 * @author jo@Dev


 */

public record InvoiceResponse(
    UUID id,
    UUID companyId,
    UUID thirdPartyId,
    String thirdPartyName,
    InvoiceType type,
    InvoiceStatus status,
    String invoiceNumber,
    LocalDate issueDate,
    LocalDate dueDate,
    String currency,
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal balanceDue,
    UUID creditNoteForInvoiceId,
    UUID journalEntryId,
    List<LineResponse> lines,
    Instant createdAt,
    Instant updatedAt,
    boolean reverseCharge,

    // ── R-F-validation v6-2 — RS sur ventes (Code Fiscal art. 156-1 Haïti) ──
    /** Taux RS appliqué (ex : 2.00 pour 2%). Null si pas de RS. */
    BigDecimal withholdingRate,
    /** Montant RS retenu par le client = subtotal × withholdingRate / 100. Null si pas de RS. */
    BigDecimal withholdingAmount,
    /** Montant net à recevoir = totalAmount − withholdingAmount. Null si pas de RS. */
    BigDecimal netReceivable,
    /** Code de la WithholdingRule appliquée (ex : "RS_HT_PRESTATIONS_LOCAL"). Null si pas de RS
     * ou si RS appliquée via taux forcé sans règle. */
    String withholdingRuleCode
) {

    /**
     * R-F-validation v6-2 — Constructeur rétro-compatible (21 args, sans RS).
     *
     * <p>Conservé pour les callers pré-v6-2 qui ne passent pas encore les 4 champs RS — ils
     * obtiennent le comportement historique (tous les champs RS à null).
     */
    public InvoiceResponse(UUID id, UUID companyId, UUID thirdPartyId, String thirdPartyName,
                            InvoiceType type, InvoiceStatus status, String invoiceNumber,
                            LocalDate issueDate, LocalDate dueDate, String currency,
                            BigDecimal subtotal, BigDecimal taxAmount, BigDecimal totalAmount,
                            BigDecimal paidAmount, BigDecimal balanceDue,
                            UUID creditNoteForInvoiceId, UUID journalEntryId,
                            List<LineResponse> lines, Instant createdAt, Instant updatedAt,
                            boolean reverseCharge) {
        this(id, companyId, thirdPartyId, thirdPartyName, type, status, invoiceNumber,
            issueDate, dueDate, currency, subtotal, taxAmount, totalAmount, paidAmount,
            balanceDue, creditNoteForInvoiceId, journalEntryId, lines, createdAt, updatedAt,
            reverseCharge, null, null, null, null);
    }

    public record LineResponse(
        UUID id,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discountPercent,
        BigDecimal taxRate,
        UUID itemId,
        UUID timesheetEntryId,
        BigDecimal lineTotalHt,
        BigDecimal lineTotalTax,
        List<TaxApplicationResponse> taxes
    ) {
        // ── v6-1 — backward-compat constructor (sans taxes) ──
        // Les appelants pré-v6-1 qui construisent LineResponse avec 10 champs continuent à
        // compiler grâce à ce constructeur secondaire qui délègue au canonique avec taxes=null.
        public LineResponse(UUID id, String description, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal discountPercent, BigDecimal taxRate, UUID itemId,
                             UUID timesheetEntryId, BigDecimal lineTotalHt, BigDecimal lineTotalTax) {
            this(id, description, quantity, unitPrice, discountPercent, taxRate, itemId,
                timesheetEntryId, lineTotalHt, lineTotalTax, null);
        }
    }

    /**
     * Détail d'une taxe appliquée à une ligne, exposé en réponse API (v6-1-multi-tax-invoice-line).
     *
     * <p>Contrairement à {@link TaxApplication} (DTO d'entrée — rate + taxType + taxCode seulement),
     * ce DTO de réponse expose également le {@code taxLabel} résolu et les montants calculés
     * {@code taxableBase} et {@code taxAmount}, pour permettre l'affichage détaillé sur la facture
     * PDF et la relecture côté client mobile.
     */
    public record TaxApplicationResponse(
        String taxType, // VAT | TCA | TURNOVER_TAX | EXCISE
        String taxCode, // nullable — code de la TaxRule (ex: TVA_HT_10)
        String taxLabel, // nullable — libellé (ex: "TVA 10% (art. 191)")
        BigDecimal rate, // taux en %
        BigDecimal taxableBase, // base HT soumise
        BigDecimal taxAmount // montant de la taxe
    ) {}
}

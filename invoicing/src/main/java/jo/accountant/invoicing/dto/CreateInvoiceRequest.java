package jo.accountant.invoicing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.invoicing.entity.InvoiceType;

/**
 * Corps de requête pour {@code POST /invoices}.
 *
 * <p><b>(session 8) — Validation DTOs</b> : ajout des annotations
 * {@code @Positive} (via {@code @DecimalMin}) sur les montants pour rejeter les valeurs
 * négatives ou nulles côté API. Sans cette validation, un client pouvait envoyer
 * {@code quantity=-100} ou {@code unitPrice=-50} → écriture comptable négative incohérente.
 *
 * <p><b>R-F-validation v6-2 — RS sur ventes (Code Fiscal Haïti art. 156-1)</b> : ajout de
 * 2 champs optionnels au niveau facture (pas au niveau ligne) pour appliquer une retenue
 * à la source sur la facture de vente :
 * <ul>
 * <li>{@code withholdingRuleCode} — code d'une WithholdingRule existante (ex :
 * {@code "RS_HT_PRESTATIONS_LOCAL"} pour 2% Haïti). Si fourni, le taux est résolu
 * automatiquement depuis la règle.</li>
 * <li>{@code withholdingRate} — taux forcé en % (ex : {@code 2.00}). Permet d'appliquer
 * un taux sans règle associée (rare — usage test ou taux ad hoc).</li>
 * </ul>
 *
 * <p><b>Règles de résolution</b> :
 * <ul>
 * <li>Si {@code withholdingRuleCode} est fourni : lookup de la WithholdingRule +
 * calcul automatique via le taux de la règle.</li>
 * <li>Sinon si {@code withholdingRate} est fourni : application directe du taux.</li>
 * <li>Sinon (les deux null/absents) : pas de RS (backward compat — comportement par défaut).</li>
 * </ul>
 *
 * <p>Si les deux sont fournis, {@code withholdingRuleCode} prend priorité (le taux de la
 * règle écrase le {@code withholdingRate} de la requête).
 
 *
 * @author jo@Dev


*/
public record CreateInvoiceRequest(
    @NotNull UUID thirdPartyId,
    InvoiceType type,
    LocalDate issueDate,
    LocalDate dueDate,
    String currency,
    @NotEmpty List<LineDto> lines,
    UUID creditNoteForInvoiceId,
    String supplierReference,

    /** R-F-validation v6-2 — Code de la WithholdingRule à appliquer (ex : "RS_HT_PRESTATIONS_LOCAL"). */
    String withholdingRuleCode,

    /** R-F-validation v6-2 — Taux RS forcé en % (rare — si pas de règle associée). */
    @DecimalMin(value = "0", message = "Withholding rate must be >= 0")
    @DecimalMax(value = "100", message = "Withholding rate must be <= 100")
    BigDecimal withholdingRate
) {

    /**
     * Constructeur de commodité rétro-compatible (8 args, sans RS) — pour les callers pré-v6-2
     * qui ne passent pas encore {@code withholdingRuleCode} / {@code withholdingRate}.
     */
    public CreateInvoiceRequest(UUID thirdPartyId, InvoiceType type, LocalDate issueDate,
                                 LocalDate dueDate, String currency, List<LineDto> lines,
                                 UUID creditNoteForInvoiceId) {
        this(thirdPartyId, type, issueDate, dueDate, currency, lines, creditNoteForInvoiceId,
            null, null, null);
    }

    /** v9.2 — Backward-compat 9-arg (with RS, without supplierReference). */
    public CreateInvoiceRequest(UUID thirdPartyId, InvoiceType type, LocalDate issueDate,
                                 LocalDate dueDate, String currency, List<LineDto> lines,
                                 UUID creditNoteForInvoiceId,
                                 String withholdingRuleCode, BigDecimal withholdingRate) {
        this(thirdPartyId, type, issueDate, dueDate, currency, lines, creditNoteForInvoiceId,
            null, withholdingRuleCode, withholdingRate);
    }

    public record LineDto(
        @NotNull String description,
        @NotNull @DecimalMin(value = "0", message = "Quantity must be >= 0") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0", message = "Unit price must be >= 0") BigDecimal unitPrice,
        @DecimalMin(value = "0", message = "Discount must be >= 0") @DecimalMax(value = "100", message = "Discount must be <= 100") BigDecimal discountPercent,
        @DecimalMin(value = "0", message = "Tax rate must be >= 0") @DecimalMax(value = "100", message = "Tax rate must be <= 100") BigDecimal taxRate,
        UUID itemId,
        UUID timesheetEntryId,
        UUID expenseAccountId,
        @Valid List<TaxApplication> taxes
    ) {
        public LineDto {
            if (discountPercent == null) discountPercent = BigDecimal.ZERO;
            if (taxRate == null) taxRate = BigDecimal.ZERO;
        }

        // ── v6-1-multi-tax-invoice-line — backward-compat constructors ──
        // Les constructeurs historiques à 6 et 7 champs (sans `taxes`) sont conservés pour ne
        // casser aucun appelant existant (InvoicingIntegrationTest, FacturXExporterTest, etc.).
        // Ils délèguent au constructeur canonique avec taxes=null → fallback taxRate (TVA seule).

        public LineDto(@NotNull String description,
                       @NotNull @DecimalMin(value = "0", message = "Quantity must be >= 0") BigDecimal quantity,
                       @NotNull @DecimalMin(value = "0", message = "Unit price must be >= 0") BigDecimal unitPrice,
                       @DecimalMin(value = "0", message = "Discount must be >= 0") @DecimalMax(value = "100", message = "Discount must be <= 100") BigDecimal discountPercent,
                       @DecimalMin(value = "0", message = "Tax rate must be >= 0") @DecimalMax(value = "100", message = "Tax rate must be <= 100") BigDecimal taxRate,
                       UUID itemId) {
            this(description, quantity, unitPrice, discountPercent, taxRate, itemId, null, null, null);
        }

        public LineDto(@NotNull String description,
                       @NotNull @DecimalMin(value = "0", message = "Quantity must be >= 0") BigDecimal quantity,
                       @NotNull @DecimalMin(value = "0", message = "Unit price must be >= 0") BigDecimal unitPrice,
                       @DecimalMin(value = "0", message = "Discount must be >= 0") @DecimalMax(value = "100", message = "Discount must be <= 100") BigDecimal discountPercent,
                       @DecimalMin(value = "0", message = "Tax rate must be >= 0") @DecimalMax(value = "100", message = "Tax rate must be <= 100") BigDecimal taxRate,
                       UUID itemId,
                       UUID timesheetEntryId) {
            this(description, quantity, unitPrice, discountPercent, taxRate, itemId, timesheetEntryId, null, null);
        }

        /** v9.2 — Backward-compat 8-arg (with taxes, without expenseAccountId). */
        public LineDto(@NotNull String description,
                       @NotNull @DecimalMin(value = "0", message = "Quantity must be >= 0") BigDecimal quantity,
                       @NotNull @DecimalMin(value = "0", message = "Unit price must be >= 0") BigDecimal unitPrice,
                       @DecimalMin(value = "0", message = "Discount must be >= 0") @DecimalMax(value = "100", message = "Discount must be <= 100") BigDecimal discountPercent,
                       @DecimalMin(value = "0", message = "Tax rate must be >= 0") @DecimalMax(value = "100", message = "Tax rate must be <= 100") BigDecimal taxRate,
                       UUID itemId,
                       UUID timesheetEntryId,
                       @Valid List<TaxApplication> taxes) {
            this(description, quantity, unitPrice, discountPercent, taxRate, itemId, timesheetEntryId, null, taxes);
        }
    }
}

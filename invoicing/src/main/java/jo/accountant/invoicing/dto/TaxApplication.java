package jo.accountant.invoicing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Application d'une taxe sur une ligne de facture (v6-1-multi-tax-invoice-line).
 *
 * <p>DTO d'entrée utilisé par {@code CreateInvoiceRequest.LineDto.taxes} pour déclarer les
 * taxes applicables à une ligne. Une ligne peut porter plusieurs {@code TaxApplication}
 * (ex: TVA 10% + TCA 10% sur une prestation de services Haïti — Code Fiscal art. 191 + 196).
 *
 * <p>Le {@code taxType} est un String (valeurs : "VAT", "TCA", "TURNOVER_TAX", "EXCISE") pour
 * compatibilité API REST — l'enum Java {@code InvoiceLineTaxType} est interne au backend. La
 * validation par {@code @Pattern} garantit que seule une valeur autorisée est acceptée.
 *
 * <p>Le {@code taxCode} est optionnel : s'il est renseigné, il doit correspondre à un
 * {@code TaxRule.code} existant pour l'entreprise (ex: "TVA_HT_10", "TCA_HT_10_SERVICES"). La
 * résolution et la validation du code se font côté service (InvoicingService).
 *
 * <p>Le {@code displayOrder} est optionnel (défaut 0) : permet à l'API cliente de contrôler
 * l'ordre d'affichage des taxes sur la facture PDF (ex: TVA avant TCA).
 *
 * <p><b>Rétro-compatibilité</b> : si {@code LineDto.taxes} est {@code null} ou vide,
 * {@code InvoicingService} fallback sur {@code LineDto.taxRate} comme TVA seule (comportement
 * historique v5.x). Aucune modification des clients existants n'est nécessaire.
 
 *
 * @author jo@Dev
*/
public record TaxApplication(
    @NotBlank
    @Pattern(regexp = "VAT|TCA|TURNOVER_TAX|EXCISE|VAT_EXEMPT_ZF|VAT_EXEMPT_NGO",
             message = "taxType must be one of: VAT, TCA, TURNOVER_TAX, EXCISE, VAT_EXEMPT_ZF, VAT_EXEMPT_NGO")
    String taxType,

    /** Code optionnel de la TaxRule (ex: "TVA_HT_10", "TCA_HT_10_SERVICES"). Nullable. */
    String taxCode,

    @NotNull
    @DecimalMin(value = "0", message = "Tax rate must be >= 0")
    @DecimalMax(value = "100", message = "Tax rate must be <= 100")
    BigDecimal rate,

    /** Ordre d'affichage (TVA avant TCA avant EXCISE). Défaut 0. */
    Integer displayOrder
) {
    public TaxApplication {
        if (displayOrder == null) displayOrder = 0;
    }
}

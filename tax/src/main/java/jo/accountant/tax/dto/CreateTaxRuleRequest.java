package jo.accountant.tax.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tax.VatMode;

public record CreateTaxRuleRequest(
 @NotBlank String code,
 @NotBlank String label,
 @NotNull @PositiveOrZero BigDecimal rate,
 UUID payableAccountId,
 UUID receivableAccountId,
 LocalDate applicableFrom,
 LocalDate applicableTo,
 VatMode vatMode
) {
 public CreateTaxRuleRequest {
 if (applicableFrom == null) applicableFrom = LocalDate.now();
 // TVA sur encaissement : par défaut, régime des débits (exigible à l'émission).
 if (vatMode == null) vatMode = VatMode.DEBIT;
 }

 /**
 * Constructeur de rétro-compatibilité — .
 *
 * <p>Les appelants existants (tests, scripts) qui ne précisent pas le {@code vatMode}
 * obtiennent le régime par défaut {@link VatMode#DEBIT} (comportement historique).
 */
 public CreateTaxRuleRequest(String code, String label, BigDecimal rate,
 UUID payableAccountId, UUID receivableAccountId,
 LocalDate applicableFrom, LocalDate applicableTo) {
 this(code, label, rate, payableAccountId, receivableAccountId,
 applicableFrom, applicableTo, null);
 }
}

package jo.accountant.tax.adapter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.port.TaxRulePort;
import jo.accountant.core.tax.VatMode;
import jo.accountant.tax.entity.TaxRule;
import jo.accountant.tax.repository.TaxRuleRepository;
import org.springframework.stereotype.Component;

/**
 * Adaptateur du port {@link TaxRulePort} — implémentation concrète côté {@code :tax}
 * (Finding #6 — TVA sur encaissement).
 *
 * <p>Lit les {@link TaxRule} depuis la DB via {@link TaxRuleRepository} et les mappe en
 * {@link TaxRulePort.TaxRuleSnapshot} (DTO immuable du port). Permet à {@code :invoicing} de
 * consulter le {@link VatMode} d'une règle de TVA (DEBIT ou ENCAISSEMENT) sans dépendre de
 * {@code :tax} compile-time — évite la dépendance circulaire Gradle
 * ({@code :tax} → {@code :invoicing} pour la déclaration TVA).
 *
 * <p>Bean Spring détecté automatiquement via {@code @Component}. {@code :invoicing} injecte le
 * port via constructeur — Spring résout l'implémentation au runtime.
 *
 * <p>Même pattern que {@link WithholdingRulePortAdapter} (audit v4.7 §4.1 Finding #2).
 */
@Component
public class TaxRulePortAdapter implements TaxRulePort {

    private final TaxRuleRepository taxRuleRepository;

    public TaxRulePortAdapter(TaxRuleRepository taxRuleRepository) {
        this.taxRuleRepository = taxRuleRepository;
    }

    @Override
    public Optional<TaxRuleSnapshot> findActiveRuleByRate(UUID companyId, BigDecimal rate, LocalDate date) {
        if (rate == null || date == null) {
            return Optional.empty();
        }
        LocalDate effectiveDate = date;
        // Recherche : règles actives et valides à la date donnée, inclant les règles globales
        // (companyId IS NULL). On privilégie une règle spécifique à l'entreprise sur une globale.
        return taxRuleRepository.findActiveRulesValidAt(companyId, effectiveDate).stream()
            .filter(rule -> rule.getRate() != null && rule.getRate().compareTo(rate) == 0)
            // Règle spécifique à l'entreprise d'abord (companyId non null), puis globale
            .min((r1, r2) -> {
                int s1 = r1.getCompanyId() != null ? 0 : 1;
                int s2 = r2.getCompanyId() != null ? 0 : 1;
                return Integer.compare(s1, s2);
            })
            .map(rule -> new TaxRuleSnapshot(
                rule.getCode(),
                rule.getLabel(),
                rule.getRate(),
                rule.getVatMode() != null ? rule.getVatMode() : VatMode.DEBIT,
                rule.getPayableAccountId()
            ));
    }
}

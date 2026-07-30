package jo.accountant.core.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.tax.VatMode;

/**
 * Port d'accès aux règles de TVA ({@code TaxRule}) pour les modules qui ne peuvent pas dépendre
 * de {@code :tax} directement (Finding #6 — TVA sur encaissement).
 *
 * <p><b>Problème d'architecture</b> : {@code :tax} dépend de {@code :invoicing} (pour lire les
 * {@code SalesInvoice} dans {@code TaxService.getDeclaration} et {@code projectCorporateTax}).
 * Si {@code :invoicing} dépendait de {@code :tax} pour accéder à {@code TaxRuleRepository}, on
 * aurait une dépendance circulaire Gradle.
 *
 * <p><b>Solution</b> : pattern hexagonal — définir un port dans {@code :core} (qui ne dépend de
 * rien) que {@code :tax} implémente. {@code :invoicing} dépend de {@code :core} (déjà le cas) et
 * injecte ce port. L'implémentation concrète est fournie par {@code :tax} via un bean Spring
 * ({@code TaxRulePortAdapter}).
 *
 * <p><b>Utilisation côté {@code :invoicing}</b> :
 * <pre>
 * &#64;Autowired
 * private TaxRulePort taxRulePort;
 *
 * Optional&lt;TaxRuleSnapshot&gt; rule = taxRulePort
 *     .findActiveRuleByRate(companyId, new BigDecimal("15"), issueDate);
 * if (rule.isPresent() &amp;&amp; rule.get().vatMode() == VatMode.ENCAISSEMENT) {
 *     // TVA différée au paiement : créditer 4438 au lieu de 443
 * }
 * </pre>
 *
 * <p>Même pattern que {@link WithholdingRulePort} (audit v4.7 §4.1 Finding #2) pour la même
 * raison de découplage.
 *
 * @see WithholdingRulePort
 * @see jo.accountant.core.tax.VatMode
 */
public interface TaxRulePort {

    /**
     * Charge la règle de TVA active et valide à la date donnée, dont le taux correspond
     * exactement au {@code rate} fourni.
     *
     * <p>Recherche par priorité :
     * <ol>
     *   <li>Règle spécifique à l'entreprise ({@code companyId} non null) ;</li>
     *   <li>Règle globale par pays ({@code companyId} null).</li>
     * </ol>
     *
     * @param companyId identifiant de l'entreprise (les règles globales sont incluses)
     * @param rate      taux de TVA en pourcentage (ex: {@code 15.00} pour 15%)
     * @param date      date de référence (ex: date d'émission de la facture)
     * @return la règle trouvée, ou {@link Optional#empty()} si aucune règle ne correspond
     */
    Optional<TaxRuleSnapshot> findActiveRuleByRate(UUID companyId, BigDecimal rate, LocalDate date);

    /**
     * Snapshot immuable d'une règle de TVA — ne dépend pas de l'entité JPA {@code TaxRule} (qui
     * vit dans {@code :tax}). Permet à {@code :invoicing} de consommer les données sans
     * dépendance compile-time vers {@code :tax}.
     *
     * @param code                  code court (ex: "TVA-HT-15", "TVA-FR-20")
     * @param label                 libellé descriptif
     * @param rate                  taux en % (ex: 15.00 pour 15%)
     * @param vatMode               mode d'exigibilité : {@link VatMode#DEBIT} (à l'émission) ou
     *                              {@link VatMode#ENCAISSEMENT} (au paiement)
     * @param payableAccountId      identifiant du compte de TVA collectée (443) — peut être null
     *                              si non configuré (résolution par code fallback utilisée alors)
     */
    record TaxRuleSnapshot(
        String code,
        String label,
        BigDecimal rate,
        VatMode vatMode,
        UUID payableAccountId
    ) {}
}

package jo.accountant.core.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.tax.WithholdingBracketType;

/**
 * Port d'accès aux règles de retenue à la source (WithholdingRule) pour les modules qui ne
 * peuvent pas dépendre de {@code :tax} directement (audit v4.7 §4.1 Finding #2 — suite).
 *
 * <p><b>Problème d'architecture</b> : {@code :tax} dépend de {@code :purchasing} (pour
 * {@code PurchaseInvoiceRepository} utilisé dans {@code TaxService.getDeclaration} afin de
 * calculer la TVA déductible). Si {@code :purchasing} dépendait de {@code :tax} pour
 * {@code WithholdingRuleRepository}, on aurait une dépendance circulaire Gradle.
 *
 * <p><b>Solution</b> : pattern hexagonal — définir un port dans {@code :core} (qui ne dépend
 * de rien) que {@code :tax} implémente. {@code :purchasing} dépend de {@code :core} (déjà
 * le cas) et utilise ce port. L'implémentation concrète est fournie par {@code :tax} via
 * un bean Spring.
 *
 * <p><b>Utilisation côté {@code :purchasing}</b> :
 * <pre>
 * &#64;Autowired
 * private WithholdingRulePort withholdingRulePort;
 *
 * List&lt;WithholdingRuleSnapshot&gt; supplierRules = withholdingRulePort
 *     .findActiveRulesForThirdPartyType(companyId, "SUPPLIER");
 * </pre>
 *
 * <p><b>R-F-validation v6-2 — RS sur ventes</b> : nouveau point d'accès
 * {@link #findActiveRuleByCode(UUID, String)} utilisé par {@code :invoicing} pour résoudre
 * une règle par son code (ex : {@code "RS_HT_PRESTATIONS_LOCAL"}) sur les factures de ventes.
 *
 * <p><b>Implémentation côté {@code :tax}</b> : {@code WithholdingRulePortAdapter} lit les
 * {@code WithholdingRule} depuis la DB et les mappe en {@link WithholdingRuleSnapshot}.
 *
 * @see jo.accountant.tax.entity.WithholdingRule
 */
public interface WithholdingRulePort {

    /**
     * Charge les règles de retenue à la source actives pour une entreprise, filtrées par
     * type de tiers applicable.
     *
     * @param companyId        identifiant de l'entreprise
     * @param thirdPartyType   type de tiers à filtrer (ex: "SUPPLIER", "EMPLOYEE") —
     *                         filtre sur {@code applicableThirdPartyTypes} qui contient cette valeur
     * @return liste des règles actives, vide si aucune
     */
    List<WithholdingRuleSnapshot> findActiveRulesForThirdPartyType(UUID companyId, String thirdPartyType);

    /**
     * R-F-validation v6-2 — Charge une règle de retenue à la source active par son code.
     *
     * <p>Recherche par priorité :
     * <ol>
     *   <li>Règle spécifique à l'entreprise ({@code companyId} non null) ;</li>
     *   <li>Règle globale par pays ({@code companyId} null).</li>
     * </ol>
     *
     * <p>Utilisé par {@code :invoicing} sur les factures de ventes pour appliquer la RS
     * (Code Fiscal art. 156-1 Haïti) — ex : lookup du code {@code "RS_HT_PRESTATIONS_LOCAL"}.
     *
     * @param companyId identifiant de l'entreprise (les règles globales sont incluses)
     * @param code      code court de la règle (ex: "RS_HT_PRESTATIONS_LOCAL", "RS_HT_ROYALTIES")
     * @return la règle trouvée, ou {@link Optional#empty()} si aucune règle ne correspond
     */
    Optional<WithholdingRuleSnapshot> findActiveRuleByCode(UUID companyId, String code);

    /**
     * R-F-validation v6-2 — Charge une règle de retenue à la source par son identifiant.
     *
     * <p>Utilisé par {@code :invoicing} lors de la construction de {@code InvoiceResponse} pour
     * exposer le code de la règle appliquée (rétro-lookup depuis {@code SalesInvoice.withholdingRuleId}).
     *
     * @param ruleId identifiant UUID de la règle
     * @return la règle trouvée, ou {@link Optional#empty()} si l'ID ne correspond à aucune règle
     */
    Optional<WithholdingRuleSnapshot> findRuleById(UUID ruleId);

    /**
     * Snapshot immuable d'une règle de retenue à la source — ne dépend pas de l'entité JPA
     * {@code WithholdingRule} (qui vit dans {@code :tax}). Permet à {@code :purchasing} de
     * consommer les données sans dépendance compile-time vers {@code :tax}.
     *
     * @param id                       identifiant de la règle (UUID) — utilisé pour stocker la FK
     *                                 sur {@code SalesInvoice.withholdingRuleId} (R-F-validation v6-2).
     *                                 Peut être null pour les snapshots construits rétro-compat.
     * @param code                     code court (ex: "IR", "TCS", "RS_HT_PRESTATIONS_LOCAL")
     * @param label                    libellé descriptif
     * @param rate                     taux en % (ex: 10.00 pour 10%) — utilisé quand bracketType = FLAT
     * @param applicableThirdPartyTypes types de tiers applicables (JSON string, ex: {@code ["SUPPLIER"]})
     * @param bracketType              type de barème (FLAT = taux unique, PROGRESSIVE = par tranches) — Finding #14
     * @param bracketsJson             barème progressif par tranches au format JSON
     *                                 {@code [{"threshold":0,"rate":0},...]} — utilisé seulement si
     *                                 {@code bracketType = PROGRESSIVE}. Null sinon ou si barème vide.
     */
    record WithholdingRuleSnapshot(
        UUID id,
        String code,
        String label,
        BigDecimal rate,
        String applicableThirdPartyTypes,
        WithholdingBracketType bracketType,
        String bracketsJson
    ) {
        /**
         * Constructeur 6-args rétro-compatible (sans {@code id}) — conservé pour les callers
         * pré-v6-2 qui ne nécessitent pas l'identifiant de la règle. Met {@code id = null}.
         */
        public WithholdingRuleSnapshot(String code, String label, BigDecimal rate,
                                        String applicableThirdPartyTypes,
                                        WithholdingBracketType bracketType,
                                        String bracketsJson) {
            this(null, code, label, rate, applicableThirdPartyTypes, bracketType, bracketsJson);
        }

        /**
         * Constructeur de commodité pour la rétro-compatibilité — utilise {@link WithholdingBracketType#FLAT}
         * et un {@code bracketsJson} null. Les appelants qui créent des snapshots sans connaître le
         * type de barème obtiennent le comportement historique (FLAT).
         */
        public WithholdingRuleSnapshot(String code, String label, BigDecimal rate,
                                        String applicableThirdPartyTypes) {
            this(null, code, label, rate, applicableThirdPartyTypes,
                WithholdingBracketType.FLAT, null);
        }
    }
}

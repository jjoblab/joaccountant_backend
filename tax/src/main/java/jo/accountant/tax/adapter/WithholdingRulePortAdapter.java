package jo.accountant.tax.adapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.port.WithholdingRulePort;
import jo.accountant.core.tax.WithholdingBracketType;
import jo.accountant.tax.entity.WithholdingRule;
import jo.accountant.tax.repository.WithholdingRuleRepository;
import org.springframework.stereotype.Component;

/**
 * Adaptateur du port {@link WithholdingRulePort} — implémentation concrète côté {@code :tax}
 *suite).
 *
 * <p>Lit les {@link WithholdingRule} depuis la DB via {@link WithholdingRuleRepository} et
 * les mappe en {@link WithholdingRuleSnapshot} (DTO immuable du port). Permet à
 * {@code :purchasing} de consommer les règles de retenue à la source sans dépendre de
 * {@code :tax} compile-time — évite la dépendance circulaire Gradle.
 *
 * <p>Bean Spring détecté automatiquement via {@code @Component}. {@code :purchasing} injecte
 * le port via constructeur — Spring résout l'implémentation au runtime.
 *
 * <p><b></b> — les nouveaux champs {@code bracketType} et {@code bracketsJson} sont
 * désormais propagés dans le snapshot, pour que {@code PurchasingService} puisse calculer la
 * retenue progressive par tranches quand {@code bracketType = PROGRESSIVE}.
 *
 * <p><b>R-F-validation v6-2 — RS sur ventes</b> : nouvelle méthode
 * {@link #findActiveRuleByCode(UUID, String)} pour lookup par code (utilisé par
 * {@code :invoicing} sur les factures de ventes). Le snapshot inclut désormais {@code id}
 * (UUID) pour stocker la FK sur {@code SalesInvoice.withholdingRuleId}.
 
 *
 * @author jo@Dev


*/
@Component
public class WithholdingRulePortAdapter implements WithholdingRulePort {

 private final WithholdingRuleRepository withholdingRuleRepository;

 public WithholdingRulePortAdapter(WithholdingRuleRepository withholdingRuleRepository) {
 this.withholdingRuleRepository = withholdingRuleRepository;
 }

 @Override
 public List<WithholdingRuleSnapshot> findActiveRulesForThirdPartyType(UUID companyId, String thirdPartyType) {
 if (thirdPartyType == null || thirdPartyType.isBlank()) {
 return List.of();
 }
 String quotedType = "\"" + thirdPartyType + "\"";
 return withholdingRuleRepository.findByCompanyIdAndActiveTrue(companyId).stream()
 .filter(rule -> {
 String types = rule.getApplicableThirdPartyTypes();
 return types != null && !types.isBlank() && types.contains(quotedType);
 })
 .map(this::toSnapshot)
 .toList();
 }

 /**
 * R-F-validation v6-2 — Lookup d'une WithholdingRule par code.
 *
 * <p>Recherche par priorité :
 * <ol>
 * <li>Règle spécifique à l'entreprise ({@code companyId} non null, match exact du code).</li>
 * <li>Règle globale par pays ({@code companyId} null, match exact du code).</li>
 * </ol>
 *
 * <p>Une règle globale est identifiée par {@code company_id IS NULL}. On exclut les règles
 * inactives ({@code active = false}).
 *
 * @param companyId identifiant de l'entreprise (les règles globales sont incluses)
 * @param code code court de la règle (ex: "RS_HT_PRESTATIONS_LOCAL")
 * @return la règle trouvée, ou {@link Optional#empty()} si aucune ne correspond
 */
 @Override
 public Optional<WithholdingRuleSnapshot> findActiveRuleByCode(UUID companyId, String code) {
 if (code == null || code.isBlank()) {
 return Optional.empty();
 }
 String normalizedCode = code.trim();
 // 1. Règle spécifique à l'entreprise (company_id = companyId)
 Optional<WithholdingRuleSnapshot> companyRule = withholdingRuleRepository
 .findByCompanyIdAndActiveTrue(companyId).stream()
 .filter(rule -> normalizedCode.equalsIgnoreCase(rule.getCode()))
 .map(this::toSnapshot)
 .findFirst();
 if (companyRule.isPresent()) {
 return companyRule;
 }
 // 2. Règle globale par pays (company_id IS NULL)
 return withholdingRuleRepository.findByCompanyIdIsNullAndActiveTrue().stream()
 .filter(rule -> normalizedCode.equalsIgnoreCase(rule.getCode()))
 .map(this::toSnapshot)
 .findFirst();
 }

 /**
 * R-F-validation v6-2 — Lookup d'une WithholdingRule par identifiant.
 *
 * <p>Utilisé par {@code :invoicing} pour résoudre le code de la règle à exposer dans la
 * réponse {@code InvoiceResponse.withholdingRuleCode} (rétro-lookup depuis la FK
 * {@code SalesInvoice.withholdingRuleId}).
 */
 @Override
 public Optional<WithholdingRuleSnapshot> findRuleById(UUID ruleId) {
 if (ruleId == null) {
 return Optional.empty();
 }
 return withholdingRuleRepository.findById(ruleId).map(this::toSnapshot);
 }

 /**
 * Mappe une entité {@link WithholdingRule} en {@link WithholdingRuleSnapshot} en propageant
 * tous les champs (y compris l'identifiant — R-F-validation v6-2).
 */
 private WithholdingRuleSnapshot toSnapshot(WithholdingRule rule) {
 return new WithholdingRuleSnapshot(
 rule.getId(),
 rule.getCode(),
 rule.getLabel(),
 rule.getRate(),
 rule.getApplicableThirdPartyTypes(),
 rule.getBracketType() != null ? rule.getBracketType() : WithholdingBracketType.FLAT,
 rule.getBracketsJson()
 );
 }
}

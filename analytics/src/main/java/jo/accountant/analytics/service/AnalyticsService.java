package jo.accountant.analytics.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.analytics.entity.AnalyticalDimensionPlan;
import jo.accountant.analytics.entity.AnalyticalDimensionValue;
import jo.accountant.analytics.repository.AnalyticalDimensionPlanRepository;
import jo.accountant.analytics.repository.AnalyticalDimensionValueRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des dimensions analytiques (§5).
 *
 * <p>Le mécanisme générique qui permet à Commerce/Service/ONG de partager le même moteur
 * comptable sans branches de code spécifiques par secteur.
 *
 * <p><strong>Recommandation 2 à 4 plans actifs maximum</strong> — au-delà, un avertissement
 * est renvoyé (pas un blocage dur, conformément au §5).
 
 *
 * @author jo@Dev


*/
@Service
public class AnalyticsService {

 private static final long RECOMMENDED_MAX_ACTIVE_PLANS = 4;

 private final AnalyticalDimensionPlanRepository planRepository;
 private final AnalyticalDimensionValueRepository valueRepository;

 public AnalyticsService(AnalyticalDimensionPlanRepository planRepository,
 AnalyticalDimensionValueRepository valueRepository) {
 this.planRepository = planRepository;
 this.valueRepository = valueRepository;
 }

 @Transactional
 public AnalyticalDimensionPlan createPlan(UUID companyId, String code, String label) {
 if (code == null || code.isBlank()) {
 throw new ValidationException("PLAN_CODE_REQUIRED", "Le code du plan est requis");
 }
 if (label == null || label.isBlank()) {
 throw new ValidationException("PLAN_LABEL_REQUIRED", "Le libellé du plan est requis");
 }
 if (planRepository.findByCompanyIdAndCode(companyId, code.trim()).isPresent()) {
 throw new ConflictException("PLAN_CODE_ALREADY_EXISTS",
 "Un plan avec le code '" + code + "' existe déjà dans cette entreprise");
 }

 AnalyticalDimensionPlan plan = new AnalyticalDimensionPlan();
 plan.setCompanyId(companyId);
 plan.setCode(code.trim());
 plan.setLabel(label.trim());
 plan.setActive(true);
 return planRepository.save(plan);
 }

 @Transactional(readOnly = true)
 public List<AnalyticalDimensionPlan> listPlans(UUID companyId) {
 return planRepository.findByCompanyId(companyId);
 }

 /**
 * Crée une valeur dans un plan.
 *
 * @return la valeur créée
 */
 @Transactional
 public AnalyticalDimensionValue createValue(UUID companyId, UUID planId, String code,
 String label, UUID parentId) {
 AnalyticalDimensionPlan plan = planRepository.findById(planId)
 .orElseThrow(() -> new NotFoundException("AnalyticalDimensionPlan", planId));
 if (!plan.getCompanyId().equals(companyId)) {
 throw new NotFoundException("AnalyticalDimensionPlan", planId);
 }

 if (code == null || code.isBlank()) {
 throw new ValidationException("VALUE_CODE_REQUIRED", "Le code de la valeur est requis");
 }
 if (label == null || label.isBlank()) {
 throw new ValidationException("VALUE_LABEL_REQUIRED", "Le libellé de la valeur est requis");
 }
 if (valueRepository.findByPlanIdAndCode(planId, code.trim()).isPresent()) {
 throw new ConflictException("VALUE_CODE_ALREADY_EXISTS",
 "Une valeur avec le code '" + code + "' existe déjà dans ce plan");
 }
 if (parentId != null) {
 AnalyticalDimensionValue parent = valueRepository.findById(parentId)
 .orElseThrow(() -> new NotFoundException("AnalyticalDimensionValue", parentId));
 if (!parent.getPlanId().equals(planId)) {
 throw new ValidationException("PARENT_WRONG_PLAN",
 "Le parent doit appartenir au même plan");
 }
 }

 AnalyticalDimensionValue value = new AnalyticalDimensionValue();
 value.setCompanyId(companyId);
 value.setPlanId(planId);
 value.setParentId(parentId);
 value.setCode(code.trim());
 value.setLabel(label.trim());
 value.setActive(true);
 return valueRepository.save(value);
 }

 @Transactional(readOnly = true)
 public List<AnalyticalDimensionValue> listValues(UUID companyId, UUID planId) {
 // Vérifier que le plan appartient bien à l'entreprise
 planRepository.findById(planId).ifPresentOrElse(
 p -> {
 if (!p.getCompanyId().equals(companyId)) {
 throw new NotFoundException("AnalyticalDimensionPlan", planId);
 }
 },
 () -> { throw new NotFoundException("AnalyticalDimensionPlan", planId); }
 );
 return valueRepository.findByPlanIdOrderByCode(planId);
 }

 /**
 * Vérifie qu'une valeur analytique existe et appartient bien au plan attendu.
 * Utilisé par {@code :accounting-engine} au postage d'une écriture.
 */
 @Transactional(readOnly = true)
 public void validateValue(UUID companyId, UUID planId, UUID valueId) {
 AnalyticalDimensionValue value = valueRepository.findById(valueId)
 .orElseThrow(() -> new ValidationException("ANALYTICAL_VALUE_NOT_FOUND",
 "Valeur analytique introuvable : " + valueId));
 if (!value.getCompanyId().equals(companyId)) {
 throw new ValidationException("ANALYTICAL_VALUE_NOT_FOUND",
 "Valeur analytique introuvable : " + valueId); // §3.9 — pas de fuite
 }
 if (!value.getPlanId().equals(planId)) {
 throw new ValidationException("ANALYTICAL_VALUE_WRONG_PLAN",
 "La valeur " + valueId + " n'appartient pas au plan " + planId);
 }
 if (!value.isActive()) {
 throw new ValidationException("ANALYTICAL_VALUE_INACTIVE",
 "La valeur " + value.getCode() + " est désactivée");
 }
 }

 /**
 * Indique si l'entreprise a dépassé la recommandation de 4 plans actifs.
 * Utilisé par {@code :accounting-engine} pour émettre un avertissement (pas un blocage).
 */
 @Transactional(readOnly = true)
 public boolean hasTooManyActivePlans(UUID companyId) {
 return planRepository.countByCompanyIdAndActiveTrue(companyId) > RECOMMENDED_MAX_ACTIVE_PLANS;
 }

 /**
 * Récupère un plan par ID — utilisé par accounting-engine pour valider
 * les {@code requiresAnalyticalTagPlanIds} d'un compte.
 *
 * <p><b>IDOR CRITICAL architectural</b> : la signature originale
 * ne prenait pas {@code companyId} en paramètre, ce qui permettait à l'appelant
 * (AccountingEngineService.validateAnalyticalTags) de récupérer un plan d'une autre company.
 * Désormais, le filtre {@code companyId.equals(...)} est appliqué systématiquement.
 */
 @Transactional(readOnly = true)
 public Optional<AnalyticalDimensionPlan> findPlanById(UUID companyId, UUID planId) {
 if (companyId == null || planId == null) return Optional.empty();
 return planRepository.findById(planId)
 .filter(p -> p.getCompanyId().equals(companyId));
 }

 /**
 * @deprecated utiliser {@link #findPlanById(UUID, UUID)} avec companyId. Conservé pour
 * backward-compat pendant la migration des callers — sera supprimé .
 */
 @Deprecated
 @Transactional(readOnly = true)
 public Optional<AnalyticalDimensionPlan> findPlanById(UUID planId) {
 return planRepository.findById(planId);
 }
}

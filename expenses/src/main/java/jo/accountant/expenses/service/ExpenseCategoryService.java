package jo.accountant.expenses.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.expenses.dto.CreateExpenseCategoryRequest;
import jo.accountant.expenses.dto.ExpenseCategoryResponse;
import jo.accountant.expenses.dto.UpdateExpenseCategoryRequest;
import jo.accountant.expenses.entity.ExpenseCategory;
import jo.accountant.expenses.repository.ExpenseCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service dédié au CRUD des catégories de notes de frais (audit batch B).
 *
 * <p>Historiquement, les catégories étaient uniquement créées via le seed V54 (4 codes
 * standards TRAVEL/MEALS/SUPPLIES/OTHER) ou par insertion SQL directe. Aucun endpoint
 * n'existait pour qu'un admin configure les plafonds journaliers/mensuels via l'API.
 *
 * <p>Ce service expose les opérations CRUD minimales :
 * <ul>
 * <li>{@link #list(UUID)} — liste les catégories d'une entreprise ;</li>
 * <li>{@link #create(UUID, CreateExpenseCategoryRequest)} — crée une catégorie
 * personnalisée (ex: HOTEL, PARKING) avec plafonds optionnels ;</li>
 * <li>{@link #update(UUID, UUID, UpdateExpenseCategoryRequest)} — modifie les plafonds
 * d'une catégorie existante (le code n'est PAS modifiable — voir
 * {@link UpdateExpenseCategoryRequest}).</li>
 * </ul>
 *
 * <p><b>Multi-tenant</b> : toutes les méthodes prennent {@code companyId} en paramètre
 * et vérifient l'appartenance de la catégorie à l'entreprise avant modification
 * ({@link NotFoundException} sinon — on ne distingue jamais "n'existe pas" et "appartient
 * à un autre tenant", §3.9).
 *
 * <p><b>Conflits</b> : la contrainte d'unicité {@code (company_id, code)} est matérialisée
 * par {@code uc_expense_category_company_code} (V54). En cas de doublon, on lève
 * {@link ConflictException} (409) plutôt que de laisser remonter une
 * {@code DataIntegrityViolationException} générique.
 *
 * <p>Le module :expenses est <strong>toujours-actif</strong> (always-on — voir
 * {@code BusinessTypeModuleService.alwaysOnModules}), donc pas de {@code ModuleAccessGuard}
 * requise sur le contrôleur. Le contrôle d'accès se fait via {@code RoleChecker} côté
 * contrôleur.
 */
@Service
public class ExpenseCategoryService {

 private static final Logger LOG = LoggerFactory.getLogger(ExpenseCategoryService.class);

 private final ExpenseCategoryRepository categoryRepository;

 public ExpenseCategoryService(ExpenseCategoryRepository categoryRepository) {
 this.categoryRepository = categoryRepository;
 }

 /**
 * Liste toutes les catégories d'une entreprise, triées par code.
 *
 * @param companyId identifiant de l'entreprise
 * @return liste des catégories (codes standards seedés par V54 + codes personnalisés)
 */
 @Transactional(readOnly = true)
 public List<ExpenseCategoryResponse> list(UUID companyId) {
 return categoryRepository.findByCompanyIdOrderByCode(companyId).stream()
 .map(ExpenseCategoryService::toResponse)
 .toList();
 }

 /**
 * Crée une nouvelle catégorie de note de frais.
 *
 * @throws ConflictException si une catégorie avec le même code existe déjà pour
 * cette entreprise (contrainte {@code uc_expense_category_company_code}).
 */
 @Transactional
 public ExpenseCategoryResponse create(UUID companyId, CreateExpenseCategoryRequest req) {
 // Vérifier l'unicité du code en amont (la contrainte DB est le filet de sécurité
 // ultime, mais on préfère lever une ConflictException métier qu'une
 // DataIntegrityViolationException générique).
 Optional<ExpenseCategory> existing = categoryRepository
 .findByCompanyIdAndCode(companyId, req.code().toUpperCase());
 if (existing.isPresent()) {
 throw new ConflictException("EXPENSE_CATEGORY_CODE_ALREADY_EXISTS",
 "Une catégorie avec le code '" + req.code() + "' existe déjà pour cette entreprise.");
 }

 ExpenseCategory cat = new ExpenseCategory();
 cat.setCompanyId(companyId);
 cat.setCode(req.code().toUpperCase());
 cat.setLabel(req.label());
 cat.setDailyLimit(req.dailyLimit());
 cat.setMonthlyLimit(req.monthlyLimit());
 ExpenseCategory saved = categoryRepository.save(cat);

 LOG.info("Catégorie de note de frais créée : company={} code={} daily={} monthly={}",
 companyId, saved.getCode(), saved.getDailyLimit(), saved.getMonthlyLimit());
 return toResponse(saved);
 }

 /**
 * Modifie les plafonds d'une catégorie existante. Le code n'est PAS modifiable
 * (intégrité référentielle avec {@code expense_line.category}).
 *
 * @throws NotFoundException si la catégorie n'existe pas ou n'appartient pas à
 * l'entreprise.
 */
 @Transactional
 public ExpenseCategoryResponse update(UUID companyId, UUID categoryId,
 UpdateExpenseCategoryRequest req) {
 ExpenseCategory cat = loadForCompany(companyId, categoryId);

 // Le label est optionnellement modifiable.
 if (req.label() != null) {
 cat.setLabel(req.label());
 }
 // Pour les plafonds : on adopte la sémantique explicite « null dans le JSON =
 // désactiver le plafond ». Cela permet à un admin de lever un plafond existant
 // sans avoir à passer 0 (qui serait interprété comme "bloquer toute dépense").
 // Si l'admin veut conserver la valeur existante, il doit la renvoyer explicitement.
 cat.setDailyLimit(req.dailyLimit());
 cat.setMonthlyLimit(req.monthlyLimit());

 // Validation sémantique : monthlyLimit >= dailyLimit (sinon le plafond mensuel
 // est atteint avant le journalier, ce qui est absurde). On lève une
 // ConflictException (409) car c'est une incohérence de configuration, pas une
 // validation de format (422).
 validateLimitsCoherence(cat.getDailyLimit(), cat.getMonthlyLimit());

 ExpenseCategory saved = categoryRepository.save(cat);
 LOG.info("Catégorie de note de frais mise à jour : company={} id={} code={} daily={} monthly={}",
 companyId, categoryId, saved.getCode(), saved.getDailyLimit(), saved.getMonthlyLimit());
 return toResponse(saved);
 }

 private ExpenseCategory loadForCompany(UUID companyId, UUID categoryId) {
 ExpenseCategory cat = categoryRepository.findById(categoryId)
 .orElseThrow(() -> new NotFoundException("ExpenseCategory", categoryId));
 if (!cat.getCompanyId().equals(companyId)) {
 // §3.9 — on ne distingue jamais "n'existe pas" et "appartient à un autre tenant".
 throw new NotFoundException("ExpenseCategory", categoryId);
 }
 return cat;
 }

 private void validateLimitsCoherence(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
 if (dailyLimit == null || monthlyLimit == null) {
 return; // Pas de validation si l'un des deux est NULL (pas de plafond).
 }
 if (monthlyLimit.compareTo(dailyLimit) < 0) {
 throw new ConflictException("EXPENSE_CATEGORY_LIMITS_INCOHERENT",
 "Le plafond mensuel (" + monthlyLimit + ") ne peut pas être inférieur au "
 + "plafond journalier (" + dailyLimit + ").");
 }
 }

 private static ExpenseCategoryResponse toResponse(ExpenseCategory cat) {
 return new ExpenseCategoryResponse(
 cat.getId(),
 cat.getCompanyId(),
 cat.getCode(),
 cat.getLabel(),
 cat.getDailyLimit(),
 cat.getMonthlyLimit()
 );
 }
}

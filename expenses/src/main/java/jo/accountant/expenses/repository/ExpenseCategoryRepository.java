package jo.accountant.expenses.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.expenses.entity.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des catégories de notes de frais (audit batch 1).
 *
 * <p>Une catégorie est identifiée par le couple {@code (companyId, code)} — chaque entreprise
 * peut configurer ses propres plafonds journaliers/mensuels pour les codes standards
 * {@code TRAVEL/MEALS/SUPPLIES/OTHER} ou pour des codes personnalisés.
 
 *
 * @author jo@Dev


*/
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

 List<ExpenseCategory> findByCompanyIdOrderByCode(UUID companyId);

 Optional<ExpenseCategory> findByCompanyIdAndCode(UUID companyId, String code);
}

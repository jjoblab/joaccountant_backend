package jo.accountant.expenses.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Catégorie de ligne de note de frais paramétrable (+ * audit batch 1).
 *
 * <p>Historiquement (V36), {@code ExpenseCategory} était un simple enum {@code {TRAVEL, MEALS,
 * SUPPLIES, OTHER}}. Le code est désormais stocké en base (table {@code expense_category}) afin
 * de permettre à chaque entreprise de configurer des plafonds journaliers et mensuels par
 * catégorie (repas, km, hébergement, etc.).
 *
 * <p><b>Plafond notes de frais paramétrable</b> : ajout des champs
 * {@link #dailyLimit} et {@link #monthlyLimit} (nullable BigDecimal). Lors de la création ou
 * soumission d'une note de frais, {@code ExpensesService} valide que le total par catégorie
 * ne dépasse pas le plafond configuré. Si aucun plafond n'est configuré (NULL), la catégorie
 * n'est pas plafonnée (comportement historique).
 *
 * <p>Les codes standards ({@link #CODE_TRAVEL}, {@link #CODE_MEALS}, {@link #CODE_SUPPLIES},
 * {@link #CODE_OTHER}) sont conservés comme constantes publiques pour préserver la
 * compatibilité avec le code existant et la contrainte CHECK {@code chk_el_category} posée en V36.
 */
@Entity
@Table(name = "expense_category",
 uniqueConstraints = @UniqueConstraint(name = "uc_expense_category_company_code",
 columnNames = {"company_id", "code"}))
/**
 * ExpenseCategory.
 *
 * @author jo@Dev


 */

public class ExpenseCategory extends TenantAwareEntity {

 // --- Codes standards (compatibilité avec l'ancien enum) ---
 public static final String CODE_TRAVEL = "TRAVEL";
 public static final String CODE_MEALS = "MEALS";
 public static final String CODE_SUPPLIES = "SUPPLIES";
 public static final String CODE_OTHER = "OTHER";

 /** Code court de la catégorie (TRAVEL, MEALS, SUPPLIES, OTHER ou code personnalisé). */
 @Column(name = "code", nullable = false, length = 20)
 private String code;

 /** Libellé long optionnel (ex: "Repas", "Kilométrage", "Hébergement"). */
 @Column(name = "label", length = 100)
 private String label;

 /**
 * Plafond journalier par employé/tiers pour cette catégorie.
 * Null = pas de plafond (comportement historique).
 */
 @Column(name = "daily_limit", precision = 19, scale = 4)
 private BigDecimal dailyLimit;

 /**
 * Plafond mensuel par employé/tiers pour cette catégorie.
 * Null = pas de plafond (comportement historique).
 */
 @Column(name = "monthly_limit", precision = 19, scale = 4)
 private BigDecimal monthlyLimit;

 public String getCode() { return code; }
 public void setCode(String code) { this.code = code; }

 public String getLabel() { return label; }
 public void setLabel(String label) { this.label = label; }

 public BigDecimal getDailyLimit() { return dailyLimit; }
 public void setDailyLimit(BigDecimal dailyLimit) { this.dailyLimit = dailyLimit; }

 public BigDecimal getMonthlyLimit() { return monthlyLimit; }
 public void setMonthlyLimit(BigDecimal monthlyLimit) { this.monthlyLimit = monthlyLimit; }
}

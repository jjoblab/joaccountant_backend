package jo.accountant.tax.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.tax.entity.WithholdingRule;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA WithholdingRule.
 *
 * @author jo@Dev


 */

public interface WithholdingRuleRepository extends JpaRepository<WithholdingRule, UUID> {
 List<WithholdingRule> findByCompanyId(UUID companyId);

 /**
 * Règles de retenue à la source actives pour une entreprise.
 * Utilisé par :payroll pour calculer les retenues salariales
 * applicables aux employés (filtre sur {@code applicableThirdPartyTypes} contenant
 * {@code "EMPLOYEE"} côté Java).
 */
 List<WithholdingRule> findByCompanyIdAndActiveTrue(UUID companyId);

 /**
 * R-F-validation v6-2 — Règles globales actives (non rattachées à une entreprise).
 *
 * <p>Utilisé par {@link jo.accountant.tax.adapter.WithholdingRulePortAdapter#findActiveRuleByCode}
 * pour le lookup par code des règles globales par pays (ex : seeds Haïti V75 —
 * {@code RS_HT_PRESTATIONS_LOCAL}, {@code RS_HT_ROYALTIES}, etc.).
 *
 * <p>Note : Spring Data JPA interprète {@code findByCompanyIdAndActiveTrue(null)} comme
 * {@code WHERE company_id = NULL} (toujours faux en SQL car NULL ≠ NULL). Cette variante
 * {@code IsNull} génère correctement {@code WHERE company_id IS NULL}.
 */
 List<WithholdingRule> findByCompanyIdIsNullAndActiveTrue();
}

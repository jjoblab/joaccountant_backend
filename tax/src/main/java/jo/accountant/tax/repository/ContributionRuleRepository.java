package jo.accountant.tax.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.tax.entity.ContributionRule;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des règles de cotisation sociale (audit v4.7 §4.1 Finding #3).
 *
 * <p>Cache applicatif Caffeine (TTL 10 min) — les règles changent rarement (1× par an pour
 * les taux URSSAF). Sans cache, chaque calcul de paie mensuel ferait N SELECT par employé.
 */
public interface ContributionRuleRepository extends JpaRepository<ContributionRule, UUID> {

    /**
     * Règles actives pour une entreprise — utilisées par PayrollCalculator.
     */
    @Cacheable(value = "contributionRules", key = "#companyId.toString() + ':active'")
    List<ContributionRule> findByCompanyIdAndActiveTrue(UUID companyId);

    /**
     * Règles actives pour une entreprise + régime — filtrage additionnel (ex: FR_CADRE).
     */
    @Cacheable(value = "contributionRules", key = "#companyId.toString() + ':' + #regime.name() + ':active'")
    List<ContributionRule> findByCompanyIdAndRegimeAndActiveTrue(UUID companyId,
        jo.accountant.tax.entity.ContributionRule.ContributionRegime regime);
}

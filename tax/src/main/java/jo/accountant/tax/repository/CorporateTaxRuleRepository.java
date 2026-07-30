package jo.accountant.tax.repository;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.tax.entity.CorporateTaxRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository des règles d'IS (audit v4.7 §4.1 Finding #4).
 *
 * <p><b>v8-1 — IS Zone Franche 15% + ONG 0%</b> : ajout de 2 méthodes de lookup par pays
 * pour résoudre les règles globales ZF et ONG (Code Fiscal Haïti art. 195).
 */
@Repository
public interface CorporateTaxRuleRepository extends JpaRepository<CorporateTaxRule, UUID> {

    /** Récupère la règle active d'une entreprise (une seule active par entreprise). */
    Optional<CorporateTaxRule> findByCompanyIdAndActiveTrue(UUID companyId);

    /**
     * v8-1 — Récupère la règle globale ZF (zone franche) active pour un pays donné.
     *
     * <p>Utilisée par {@code TaxService.resolveCorporateTaxRule()} quand
     * {@code Company.isFreeZone == true} ou {@code taxExemptionStatus == FREE_ZONE}.
     *
     * @param countryCode code ISO 3166-1 alpha-2 (ex: "HT")
     * @return la règle ZF active pour ce pays, ou empty si aucune règle n'existe
     */
    Optional<CorporateTaxRule> findByCountryCodeAndFreeZoneRateTrueAndActiveTrue(String countryCode);

    /**
     * v8-1 — Récupère la règle globale ONG exonérée active pour un pays donné.
     *
     * <p>Utilisée par {@code TaxService.resolveCorporateTaxRule()} quand
     * {@code Company.taxExemptionStatus == NGO_EXEMPT}.
     *
     * @param countryCode code ISO 3166-1 alpha-2 (ex: "HT")
     * @return la règle ONG exonérée active pour ce pays, ou empty si aucune règle n'existe
     */
    Optional<CorporateTaxRule> findByCountryCodeAndNgoExemptRateTrueAndActiveTrue(String countryCode);
}


package jo.accountant.company.mapping;

import java.util.Map;
import java.util.Set;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.core.exception.ValidationException;
import org.springframework.stereotype.Component;

/**
 * Validateur croisé Nature ↔ Forme juridique (§4.2 — restructuration :company).
 *
 * <p>Centralise la règle de cohérence « un {@link LegalForm} n'est compatible qu'avec
 * certaines {@link OrganizationNature} » — auparavant il n'existait aucune validation
 * croisée (rien n'empêchait {@code SARL + ONG}).
 *
 * <p><b>Domaine {@link OrganizationNature} réduit à 2 valeurs</b> ({@code FOR_PROFIT},
 * {@code NON_PROFIT}). Les branches {@code PUBLIC_SECTOR}/{@code COOPERATIVE} ont été
 * retirées de l'enum (voir {@code V101__simplify_organization_nature.sql}). Ce validateur
 * reste correct : {@code LegalForm.OTHER} accepte désormais {@link OrganizationNature#values()}
 * qui ne contient plus que les 2 valeurs valides.
 *
 * <p><b>Dead code (audit `wizard-audit`)</b> : ce validateur est injecté dans {@code CompanyService}
 * mais jamais appelé. Conservé pour câblage futur (wire dans {@code applyWizardStep2} ou
 * {@code createCompany} quand le métier le demandera).
 *
 * <p>Règles :
 * <ul>
 * <li>{@code SOLE_PROPRIETORSHIP}, {@code SARL}, {@code SA}, {@code SAS} ⟹
 * {@code FOR_PROFIT} uniquement.</li>
 * <li>{@code NGO}, {@code ASSOCIATION} ⟹ {@code NON_PROFIT} uniquement.</li>
 * <li>{@code OTHER} ⟹ toute nature (confirmation explicite par le client).</li>
 * </ul>
 *
 * <p>Composant dédié plutôt que {@code if/else} éparpillé dans le service (principe déjà
 * appliqué par {@link BusinessTypeModuleService} : mapping centralisé).
 
 *
 * @author jo@Dev


*/
@Component
public class OrganizationNatureLegalFormValidator {

    /** Mapping forme juridique → natures compatibles. */
    private static final Map<LegalForm, Set<OrganizationNature>> COMPATIBLE = Map.of(
        LegalForm.SOLE_PROPRIETORSHIP, Set.of(OrganizationNature.FOR_PROFIT),
        LegalForm.SARL, Set.of(OrganizationNature.FOR_PROFIT),
        LegalForm.SA, Set.of(OrganizationNature.FOR_PROFIT),
        LegalForm.SAS, Set.of(OrganizationNature.FOR_PROFIT),
        LegalForm.NGO, Set.of(OrganizationNature.NON_PROFIT),
        LegalForm.ASSOCIATION, Set.of(OrganizationNature.NON_PROFIT),
        LegalForm.OTHER, Set.of(OrganizationNature.values())
    );

    /** Lève 422 {@code LEGAL_FORM_NATURE_MISMATCH} si la paire est invalide. */
    public void validate(OrganizationNature nature, LegalForm legalForm) {
        if (nature == null || legalForm == null) {
            return; // La validation @NotNull sur les DTOs gère les nulls.
        }
        Set<OrganizationNature> allowed = COMPATIBLE.get(legalForm);
        if (allowed == null || !allowed.contains(nature)) {
            throw new ValidationException("LEGAL_FORM_NATURE_MISMATCH",
                "La forme juridique " + legalForm + " n'est pas compatible avec la nature "
                + nature + ". Formes compatibles avec " + nature + " : "
                + compatibleForms(nature) + ".");
        }
    }

    /** Liste les {@link LegalForm} compatibles avec une nature donnée — pour aider l'UI. */
    public Set<LegalForm> compatibleForms(OrganizationNature nature) {
        return COMPATIBLE.entrySet().stream()
            .filter(e -> e.getValue().contains(nature))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }
}

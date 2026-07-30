package jo.accountant.company.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import jo.accountant.company.entity.Company;

/**
 * Mise à jour d'une étape du wizard (§5 — restructuration :company).
 *
 * <p>La nouvelle séquence du wizard est :
 * <ol>
 *   <li><strong>Identité</strong> — name, country, functionalCurrency (ré-éditable)</li>
 *   <li><strong>Nature + Forme juridique</strong> — validation croisée (§4.2)</li>
 *   <li><strong>Secteur d'activité</strong> — descriptif</li>
 *   <li><strong>Type métier</strong> — choix dans le catalogue BusinessType + aperçu des modules</li>
 *   <li><strong>Activité principale</strong> — libellé libre</li>
 *   <li><strong>Référentiel comptable + mois de clôture</strong></li>
 *   <li><strong>Champs spécifiques obligatoires</strong> — formulaire dynamique
 *       (BusinessTypeRequiredField pour le businessTypeCode choisi à l'étape 4)</li>
 *   <li><strong>Récapitulatif modules</strong> — auto-suggérés par le type métier + ajustement
 *       manuel (remplace l'étape 9 actuelle — correction du bug {@code MIXTE})</li>
 *   <li><strong>Confirmation finale</strong> → POST /wizard/complete</li>
 * </ol>
 *
 * <p>Le {@code step} est validé via la constante {@link Company#TOTAL_WIZARD_STEPS} —
 * ce magic number n'est plus dupliqué en dur (§4.3 du prompt).
 */
public record WizardStepRequest(
    @NotNull @Min(1) @Max(Company.TOTAL_WIZARD_STEPS) Integer step,
    Map<String, Object> payload
) {}

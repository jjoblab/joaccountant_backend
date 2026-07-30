package jo.accountant.company.dto;

import java.util.List;
import java.util.Map;

/**
 * V8.2 — Wizard étape 4 : Activation atomique (refondu).
 *
 * <p>Corps de la requête POST /wizard/complete. Toutes les sous-étapes
 * (modules, plan comptable, journaux, exercice, séquences, TVA) sont
 * exécutées en UNE SEULE transaction côté backend.
 */
public record CompleteWizardRequest(
    Integer mfaCode,  // requis si OWNER sans MFA
    List<Map<String, Object>> expenseCategories,  // optionnel — pré-seed
    List<Map<String, Object>> contributionRules   // optionnel — pré-seed
) {}

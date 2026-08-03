package jo.accountant.company.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * WizardActivation atomique (refondu).
 *
 * <p>Corps de la requête POST /wizard/complete. Toutes les sous-étapes
 * (modules, plan comptable, journaux, exercice, séquences, TVA) sont
 * exécutées en UNE SEULE transaction côté backend.
 
 *
 * @author jo@Dev


*/
public record CompleteWizardRequest(
    Integer mfaCode, // requis si OWNER sans MFA
    List<Map<String, Object>> expenseCategories, // optionnel — pré-seed
    List<Map<String, Object>> contributionRules, // optionnel — pré-seed
    // Fix Dim 4 P1 (audit v9.4) — Capital social initial optionnel.
    // Si fourni (> 0), une écriture OD est générée après provision() :
    //   Débit 512 (Banque) / Crédit 101 (Capital social)
    // Permet de démarrer l'exercice N avec le bon solde d'ouverture.
    BigDecimal initialCapital
) {
    /** Constructeur backward-compat sans initialCapital. */
    public CompleteWizardRequest(Integer mfaCode,
                                  List<Map<String, Object>> expenseCategories,
                                  List<Map<String, Object>> contributionRules) {
        this(mfaCode, expenseCategories, contributionRules, null);
    }
}

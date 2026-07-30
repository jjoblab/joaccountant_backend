package jo.accountant.chartofaccounts.entity;

/**
 * Sens normal du solde d'un compte (§13 Phase 3).
 *
 * <p>Définit si un compte augmente au débit ou au crédit — information nécessaire pour
 * interpréter le solde d'un compte et générer correctement le bilan et le compte de résultat
 * (Phase 6).
 *
 * <ul>
 *   <li>{@link #DEBIT} — compte de la classe ACTIF ou CHARGES : augmente au débit, diminue
 *       au crédit. Solde normal = débiteur.</li>
 *   <li>{@link #CREDIT} — compte de la classe PASSIF, CAPITAUX_PROPRES ou PRODUITS :
 *       augmente au crédit, diminue au débit. Solde normal = créditeur.</li>
 * </ul>
 *
 * <p>Exemples SYSCOHADA :
 * <ul>
 *   <li>Classe 2 (Actifs immobilisés) → {@link #DEBIT}</li>
 *   <li>Classe 1 (Ressources durables) → {@link #CREDIT}</li>
 *   <li>Classe 6 (Charges) → {@link #DEBIT}</li>
 *   <li>Classe 7 (Produits) → {@link #CREDIT}</li>
 * </ul>
 */
public enum NormalBalance {
    DEBIT,
    CREDIT
}

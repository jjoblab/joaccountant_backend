package jo.accountant.chartofaccounts.entity;

/**
 * Sous-catégorie de reporting universelle (§4).
 *
 * <p>Indépendante du référentiel comptable choisi — c'est cette classification (avec
 * {@link jo.accountant.core.framework.ReportingClass}) que consomme {@code financial-statements}
 *pour générer bilan et compte de résultat. Le moteur comptable n'a donc jamais
 * besoin de savoir si l'entreprise est en SYSCOHADA ou en IFRS.
 *
 * <ul>
 * <li>{@link #COURANT} — actif/passif dont l'utilisation prévue est ≤ 12 mois
 * (ex. stocks, créances clients, dettes fournisseurs)</li>
 * <li>{@link #NON_COURANT} — actif/passif dont l'utilisation prévue est &gt; 12 mois
 * (ex. immobilisations, capitaux permanents, emprunts LT)</li>
 * <li>{@link #N_A} — non applicable (ex. comptes de produits/charges qui ne se prêtent
 * pas à la distinction courant/non-courant, ou comptes de regroupement)</li>
 * <li>{@link #CTA} — V85 — v7-3 : Cumulative Translation Adjustment (IAS 21). Écart de
 * conversion isolé en capitaux propres lorsque le bilan est présenté dans une devise
 * différente de la devise fonctionnelle. Compte 108 PCN/SYSCOHADA.</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
public enum ReportingSubcategory {
    COURANT,
    NON_COURANT,
    N_A,
    CTA // V85 — v7-3 : Cumulative Translation Adjustment (IAS 21)
}

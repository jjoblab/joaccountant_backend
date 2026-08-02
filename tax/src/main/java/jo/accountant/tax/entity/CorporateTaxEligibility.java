package jo.accountant.tax.entity;

/**
 * Éligibilité au taux réduit d'IS pour PME.
 *
 * <p>En France (CGI art. 219), le taux réduit de 15% s'applique aux PME dont le chiffre
 * d'affaires est inférieur à 10 M€ et dont le capital est entièrement libéré et détenu
 * pour 75% au moins par des personnes physiques.
 *
 * <p><b>v8-1 — IS Zone Franche 15% + ONG 0% (Code Fiscal Haïti art. 195)</b> :
 * extension de l'énumération pour couvrir 2 nouveaux régimes fiscaux :
 * <ul>
 * <li>{@link #FREE_ZONE} — Entreprise agréée en zone franche (CODEVI/SONAPI) :
 * IS réduit à 15% sur la totalité du résultat fiscal (pas de seuil).</li>
 * <li>{@link #NGO_EXEMPT} — ONG / association exonérée d'IS :
 * IS = 0 (Code Fiscal art. 195 sous conditions d'agrément).</li>
 * </ul>
 * Ces 2 valeurs sont mappées sur des CorporateTaxRule globales par pays (migration V90)
 * et activées via {@code Company.isFreeZone} ou {@code Company.taxExemptionStatus}
 * (migration V91).
 
 *
 * @author jo@Dev


*/
public enum CorporateTaxEligibility {
 /** PME éligible au taux réduit (15% jusqu'à 42 500 €, 25% au-delà). */
 SME,
 /** Grande entreprise : taux normal 25% sur la totalité du résultat fiscal. */
 LARGE,
 /** Non déterminé — utilise le taux normal par défaut. */
 UNKNOWN,
 /**
 * v8-1 — Entreprise agréée en zone franche (Code Fiscal Haïti art. 195).
 * IS réduit à 15% sur la totalité du résultat fiscal (pas de seuil PME).
 */
 FREE_ZONE,
 /**
 * v8-1 — ONG / association exonérée d'IS (Code Fiscal Haïti art. 195).
 * IS = 0 (exonération totale sous conditions d'agrément DGI).
 */
 NGO_EXEMPT
}

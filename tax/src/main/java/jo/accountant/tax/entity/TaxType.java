package jo.accountant.tax.entity;

/**
 * Type de taxe modélisé par une {@link TaxRule} (Lot B — fiscalité Haïti).
 *
 * <p>Avant la R-07, toutes les {@link TaxRule} étaient implicitement considérées comme de la TVA.
 * Le Code Fiscal haïtien distingue en réalité plusieurs taxes sur le chiffre d'affaires :
 * <ul>
 *   <li><b>TVA</b> — Taxe sur la Valeur Ajoutée (10% en Haïti, art. 191 ; 20%/10%/5,5% en France).</li>
 *   <li><b>TCA</b> — Taxe sur le Chiffre d'Affaires (Haïti) : 2% banques (art. 197),
 *       5% télécoms, 10% autres services (art. 196). <em>Ne pas confondre avec la TVA :</em>
 *       la TCA est cumulable avec la TVA sur une même opération.</li>
 *   <li><b>TURNOVER_TAX</b> — Taxe sur le chiffre d'affaires générique (hors TVA/TCA,
 *       ex: taxe minimum forfaitaire OHADA).</li>
 *   <li><b>EXCISE</b> — Accises / droits de consommation (alcool, tabac, carburant).</li>
 * </ul>
 *
 * <p>La valeur par défaut {@link #VAT} préserve la rétro-compatibilité : toutes les règles
 * existantes (créées avant la R-07) sont interprétées comme de la TVA, ce qui correspond
 * au comportement historique.
 */
public enum TaxType {
    /** Taxe sur la Valeur Ajoutée (défaut — préserve le comportement historique). */
    VAT,
    /** Taxe sur le Chiffre d'Affaires (Haïti — art. 196/197, cumulable avec la TVA). */
    TCA,
    /** Taxe sur le chiffre d'affaires générique hors TVA/TCA (ex: minimum forfaitaire). */
    TURNOVER_TAX,
    /** Accises / droits de consommation (alcool, tabac, carburant). */
    EXCISE
}

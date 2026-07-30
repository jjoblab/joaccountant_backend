package jo.accountant.invoicing.entity;

/**
 * Type de taxe appliqué à une ligne de facture (v6-1-multi-tax-invoice-line + V8-6 ZF).
 *
 * <p>Miroir local de {@code jo.accountant.tax.entity.TaxType} (lot B — fiscalité Haïti) pour
 * éviter une dépendance circulaire Gradle {@code :invoicing → :tax}. Les valeurs sont
 * strictement identiques : la conversion est assurée par {@code TaxService} (module :tax) via
 * {@code TaxType.valueOf(InvoiceLineTaxType.name())}.
 *
 * <ul>
 *   <li><b>VAT</b> — Taxe sur la Valeur Ajoutée (10% Haïti, art. 191 ; 20%/10%/5,5% France).</li>
 *   <li><b>TCA</b> — Taxe sur le Chiffre d'Affaires (Haïti — art. 196/197). <em>Cumulable avec
 *       la TVA sur une même opération</em> : une ligne de prestation de services Haïti porte en
 *       général 1 entrée VAT 10% + 1 entrée TCA 10%.</li>
 *   <li><b>TURNOVER_TAX</b> — Taxe sur le chiffre d'affaires générique hors TVA/TCA.</li>
 *   <li><b>EXCISE</b> — Accises / droits de consommation (alcool, tabac, carburant).</li>
 *   <li><b>VAT_EXEMPT_ZF</b> — V8-6 : Exonération TVA zone franche (Code Fiscal art. 195).
 *       Pour les imports en franchise douanière d'une entreprise agréée ZF : la ligne porte
 *       un taux 0% explicite + le code VAT_EXEMPT_ZF qui est filtré de la déclaration TVA
 *       mensuelle. Permet de tracer les imports en franchise sans générer de TVA déductible
 *       fictive.</li>
 *   <li><b>VAT_EXEMPT_NGO</b> — V8-6 : Exonération TVA ONG (Code Fiscal art. 195). Même
 *       mécanisme pour les achats d'une ONG exonérée.</li>
 * </ul>
 */
public enum InvoiceLineTaxType {
    /** Taxe sur la Valeur Ajoutée (défaut — préserve le comportement historique taxRate). */
    VAT,
    /** Taxe sur le Chiffre d'Affaires (Haïti — art. 196/197, cumulable avec la TVA). */
    TCA,
    /** Taxe sur le chiffre d'affaires générique hors TVA/TCA (ex: minimum forfaitaire). */
    TURNOVER_TAX,
    /** Accises / droits de consommation (alcool, tabac, carburant). */
    EXCISE,
    /** V8-6 — Exonération TVA zone franche (Code Fiscal art. 195). Taux 0% explicite + filtrage déclaration. */
    VAT_EXEMPT_ZF,
    /** V8-6 — Exonération TVA ONG (Code Fiscal art. 195). Taux 0% explicite + filtrage déclaration. */
    VAT_EXEMPT_NGO
}

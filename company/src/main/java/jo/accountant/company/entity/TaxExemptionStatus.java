package jo.accountant.company.entity;

/**
 * v8-1 — Statut d'exonération fiscale d'une Company (Code Fiscal Haïti art. 195).
 *
 * <p>Détermine le régime d'Impôt sur les Sociétés (IS) applicable à l'entreprise :
 * <ul>
 *   <li>{@link #STANDARD} — entreprise standard, IS au taux normal (30% Haïti / 25% France).</li>
 *   <li>{@link #FREE_ZONE} — entreprise agréée en zone franche (CODEVI/SONAPI),
 *       IS réduit à 15% sur la totalité du résultat fiscal (pas de seuil PME).</li>
 *   <li>{@link #NGO_EXEMPT} — ONG / association exonérée d'IS (IS = 0,
 *       sous conditions d'agrément DGI).</li>
 * </ul>
 *
 * <p>Ce champ est alimenté par le wizard de création d'entreprise (en fonction de la
 * {@code OrganizationNature} et du {@code BusinessType}) ou par une mise à jour manuelle
 * via l'API {@code PATCH /companies/{id}/legal-fields}. Il alimente ensuite
 * {@code TaxService.resolveCorporateTaxRule()} pour router vers la bonne
 * {@code CorporateTaxRule}.
 *
 * <p>Stocké en base dans la colonne {@code companies.tax_exemption_status} (migration V91).
 */
public enum TaxExemptionStatus {
    /** Entreprise standard — IS au taux normal (30% Haïti / 25% France). */
    STANDARD,
    /** Entreprise agréée zone franche — IS réduit 15% (Code Fiscal art. 195). */
    FREE_ZONE,
    /** ONG / association exonérée — IS = 0 (Code Fiscal art. 195). */
    NGO_EXEMPT
}

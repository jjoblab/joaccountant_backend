package jo.accountant.company.entity;

/**
 * Nature de l'organisation (§4.1 — restructuration du module :company).
 *
 * <p>Axe conceptuel distinct du secteur d'activité et de la forme juridique : décrit la
 * <em>nature</em> de l'organisation.
 *
 * <p><b>Simplification à 2 valeurs.</b>
 * Initialement 4 valeurs ({@code FOR_PROFIT}, {@code NON_PROFIT}, {@code PUBLIC_SECTOR},
 * {@code COOPERATIVE}), le domaine a été réduit aux 2 valeurs effectivement utilisées par
 * le wizard refonte :
 * <ul>
 * <li>{@link #FOR_PROFIT} — « à but lucratif » (entreprises commerciales, cabinets,
 * sociétés de services...).</li>
 * <li>{@link #NON_PROFIT} — « non lucratif » (ONG, associations, fondations, écoles
 * privées à but non lucratif, hôpitaux...).</li>
 * </ul>
 *
 * <p>Les anciennes valeurs {@code PUBLIC_SECTOR} et {@code COOPERATIVE} ont été retirées
 * car (a) aucun seed {@code BusinessType} ne les utilisait et (b) le wizard refonte ne
 * propose que 2 choix à l'utilisateur. La migration Flyway {@code V101__simplify_organization_nature.sql}
 * re-saupoudre les éventuelles lignes existantes vers {@code FOR_PROFIT} (safe default)
 * et remplace les contraintes {@code CHECK} ({@code chk_companies_organization_nature}
 * et {@code chk_business_type_nature}) pour n'accepter que ces 2 valeurs.
 *
 * <p>Pilote la validation croisée avec {@link LegalForm} (ex. {@code ASSOCIATION}/{@code NGO}
 * ⟹ {@code NON_PROFIT} uniquement) via {@link jo.accountant.company.mapping.OrganizationNatureLegalFormValidator}.
 *
 * <p>Enum Java (2 valeurs stables) — pas une table de référence en base, contrairement à
 * {@code BusinessType}.
 
 *
 * @author jo@Dev


*/
public enum OrganizationNature {
    FOR_PROFIT,
    NON_PROFIT
}

package jo.accountant.company.entity;

/**
 * Nature de l'organisation (§4.1 — restructuration du module :company).
 *
 * <p>Axe conceptuel distinct du secteur d'activité et de la forme juridique : décrit la
 * <em>nature</em> de l'organisation (lucratif, non-lucratif, public, coopératif). Pilote la
 * validation croisée avec {@link LegalForm} (ex. {@code ASSOCIATION}/{@code NGO} ⟹
 * {@code NON_PROFIT} uniquement) et peut orienter le régime fiscal.
 *
 * <p>Enum Java (4 valeurs stables) — pas une table de référence en base, contrairement à
 * {@code BusinessType}.
 */
public enum OrganizationNature {
    FOR_PROFIT,
    NON_PROFIT,
    PUBLIC_SECTOR,
    COOPERATIVE
}

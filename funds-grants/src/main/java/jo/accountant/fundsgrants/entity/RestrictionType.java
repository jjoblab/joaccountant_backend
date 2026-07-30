package jo.accountant.fundsgrants.entity;

/**
 * Type de restriction d'une subvention (§13 Phase 14).
 *
 * <ul>
 *   <li>{@link #RESTRICTED} — subvention affectée à un usage précis (ex. "Subvention CRS 2026
 *       pour le projet d'eau potable"). Déclenche le mécanisme des fonds dédiés à la clôture.</li>
 *   <li>{@link #UNRESTRICTED} — subvention non affectée. L'ONG peut l'utiliser librement.
 *       Pas de fonds dédiés à la clôture.</li>
 * </ul>
 */
public enum RestrictionType {
    RESTRICTED,
    UNRESTRICTED
}

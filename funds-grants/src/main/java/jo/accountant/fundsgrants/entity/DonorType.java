package jo.accountant.fundsgrants.entity;

/**
 * Type de bailleur institutionnel (v6-3 — formats bailleurs structurés).
 *
 * <p>Détermine le format d'export attendu via {@code DonorReportExporter} :
 * <ul>
 *   <li>{@link #USAID} — format SF-425 (Federal Financial Report) trimestriel.</li>
 *   <li>{@link #EU} — format PRAG (Annual Financial Report) annuel.</li>
 *   <li>{@link #WORLD_BANK} — format Quarterly Financial Report.</li>
 *   <li>{@link #CRS} — Catholic Relief Services (format interne, futur).</li>
 *   <li>{@link #OTHER} — bailleur non listé (export générique, futur).</li>
 * </ul>
 *
 * <p>Codé en dur dans la contrainte CHECK de la table {@code donor_report_line}
 * (migration V69) — toute extension doit passer par une migration complémentaire.
 */
public enum DonorType {
    USAID,
    EU,
    WORLD_BANK,
    CRS,
    OTHER
}

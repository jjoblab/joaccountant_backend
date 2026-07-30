package jo.accountant.fundsgrants.entity;

/**
 * Catégorie de coût standardisée (v6-3 — formats bailleurs structurés).
 *
 * <p>Alignée sur la Section B du formulaire USAID SF-425 (Federal Financial Report)
 * et sur les catégories attendues par la Banque Mondiale (Quarterly Financial Report).
 *
 * <ul>
 *   <li>{@link #PERSONNEL} — salaires et traitements du personnel directement affecté
 *       au projet (correspond à "Personnel" SF-425, "Staff costs" PRAG, "Personnel" BM).</li>
 *   <li>{@link #FRINGE} — charges sociales et avantages sociaux liés au personnel
 *       (Fringe Benefits SF-425).</li>
 *   <li>{@link #TRAVEL} — déplacements et missions (Travel SF-425).</li>
 *   <li>{@link #EQUIPMENT} — équipements > seuil de capitalisation (Equipment SF-425).</li>
 *   <li>{@link #SUPPLIES} — fournitures et consommables (Supplies SF-425).</li>
 *   <li>{@link #CONTRACTUAL} — sous-traitance et services (Contractual SF-425).</li>
 *   <li>{@link #OTHER} — autres charges directes (Other SF-425).</li>
 *   <li>{@link #INDIRECT_COST} — frais indirects / overheads (Indirect Charges SF-425,
 *       "Overheads" BM). Le taux de NIHA (Negotiated Indirect Cost Rate Agreement) USAID
 *       s'applique ici — configurable par grant en v7.</li>
 * </ul>
 *
 * <p>Codé en dur dans la contrainte CHECK de la table {@code donor_report_line}
 * (migration V69).
 */
public enum CostCategory {
    PERSONNEL,
    FRINGE,
    TRAVEL,
    EQUIPMENT,
    SUPPLIES,
    CONTRACTUAL,
    OTHER,
    INDIRECT_COST
}

package jo.accountant.company.entity;

/**
 * Secteur d'activité (§4.2 — restructuration du module :company).
 *
 * <p>Depuis la restructuration, ce champ est <strong>purement descriptif</strong> — il ne
 * pilote <em>plus</em> l'activation des modules (rôle désormais tenu par
 * {@link BusinessType} via la table {@code business_type_module}). Conservé sur
 * {@link Company} pour la classification statistique et la lisibilité métier.
 *
 * <p>Valeurs élargies (10 au total) pour couvrir plus de cas que les 4 anciens secteurs
 * (qui confondaient « nature de l'organisation » et « secteur d'activité »). L'ancienne
 * valeur {@code MIXTE} est retirée — son rôle fonctionnel est repris par le type métier
 * {@code CUSTOM} (sélection manuelle de modules à l'du wizard).
 
 *
 * @author jo@Dev


*/
public enum Sector {
    COMMERCE,
    SERVICE,
    SANTE,
    EDUCATION,
    AGRICULTURE,
    INDUSTRIE,
    ADMINISTRATION_PUBLIQUE,
    ONG_HUMANITAIRE,
    CABINET_COMPTABLE,
    AUTRE
}

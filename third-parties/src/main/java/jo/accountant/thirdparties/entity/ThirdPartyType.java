package jo.accountant.thirdparties.entity;

/**
 * Type de tiers (§13.
 *
 * <p>Détermine le compte collectif par défaut auquel rattaché le tiers (clients = classe 41x,
 * fournisseurs = classe 40x, etc. — mais la configuration exacte dépend du plan comptable
 * de l'entreprise).
 *
 * <p>Le type {@link #DONOR} est utilisé par le module {@code funds-grants}pour
 * les bailleurs de subventions.
 
 *
 * @author jo@Dev


*/
public enum ThirdPartyType {
    CLIENT,
    SUPPLIER,
    DONOR,
    EMPLOYEE,
    OTHER
}

package jo.accountant.app.search;

/**
 * v2.5.0 — Task 16 : un résultat de recherche globale (Ctrl+K).
 *
 * <p>Représente une seule entité trouvée parmis les 5 modules recherchés
 * (tiers, factures, écritures, comptes, employés). Le client mobile l'utilise
 * pour :
 * <ul>
 *   <li>afficher une ligne dans le {@code RecyclerView} de
 *       {@code GlobalSearchFragment} ({@code label} + {@code subtitle}) ;</li>
 *   <li>naviguer vers l'écran de détail correspondant au {@code type}
 *       ({@code THIRD_PARTY} → fiche tiers, {@code INVOICE} → détail facture, etc.).</li>
 * </ul>
 *
 * @param type            "THIRD_PARTY" | "INVOICE" | "ENTRY" | "ACCOUNT" | "EMPLOYEE"
 * @param id              UUID de l'entité (sous forme de String pour JSON)
 * @param label           nom d'affichage principal (ex. « Boulangerie du Marché »,
 *                        « FAC-2025-001 », « 401000 — Fournisseurs »)
 * @param subtitle        information secondaire (ex. « Client », « 12 500,00 HTG »,
 *                        « Écriture du 2025-04-12 »)
 * @param navigationHint  indice d'action optionnel (ex. « Ouvrir la fiche tiers »,
 *                        « Voir la facture »). Affiché sous le libellé par le mobile.
 */
public record GlobalSearchResult(
    String type,
    String id,
    String label,
    String subtitle,
    String navigationHint
) {}
